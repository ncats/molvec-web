package controllers;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.time.Duration;
import java.util.concurrent.*;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import java.security.MessageDigest;
import java.util.stream.Collectors;

import javax.inject.*;
import com.typesafe.config.Config;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import play.mvc.*;
import play.libs.ws.*;
import play.libs.Json;
import play.libs.Files;
import static play.libs.Files.*;
import play.Logger;
import play.Application;
import play.Environment;
import play.server.ApplicationProvider;
import play.cache.AsyncCacheApi;
import play.libs.concurrent.HttpExecutionContext;
import play.routing.JavaScriptReverseRouter;

import akka.actor.ActorSystem;
import akka.actor.AbstractActor;
import akka.actor.ActorSelection;
import akka.actor.AbstractActor.Receive;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.Terminated;
import akka.actor.PoisonPill;
import akka.actor.Inbox;
import akka.pattern.Patterns;
import akka.util.Timeout;
import akka.util.ByteString;
import static akka.dispatch.Futures.sequence;
import static scala.compat.java8.FutureConverters.*;

import models.*;
import gov.nih.ncats.molvec.Molvec;

public class MolVecApp extends Controller {

    static abstract class RecognizeActor extends AbstractActor {
        MolVecApp app;
        RecognizeActor (MolVecApp app) {
            this.app = app;
        }

        @Override
        public void preStart () {
            Logger.debug("### "+self ()+ "...initialized!");
        }

        @Override
        public void postStop () {
            Logger.debug("### "+self ()+"...stopped!");
        }

        @Override
        public Receive createReceive () {
            return receiveBuilder()
                .match(Image.class, this::run)
                .build();
        }

        abstract void recognize (RecognitionResult result, File file)
            throws Exception;

        void run (Image image) {
            Logger.debug(self()+": "+image.sha1);
            RecognitionResult result = new RecognitionResult (image);
            try {
                File file = app.getImageFile(image);
                if (file != null) {
                    long start = System.currentTimeMillis();
                    recognize (result, file);
                    result.elapsed = 1e-3*(System.currentTimeMillis()-start);
                }
                else {
                    result.status = "Image "+image.sha1+" not found!";
                }
            }
            catch (Exception ex) {
                result.status = ex.getMessage();
                Logger.error(self()+": molvec failed", ex);
            }
            
            getSender().tell(result, getSelf ());
        }
    }

    static class MolVecActor extends RecognizeActor {
        static Props props (MolVecApp app) {
            return Props.create
                (MolVecActor.class, () -> new MolVecActor (app));
        }
        
        MolVecActor (MolVecApp app) {
            super (app);
        }
        
        void recognize (RecognitionResult result, File file) throws Exception {
            result.engine = self().path().name();
            result.molfile = Molvec.ocr(file);
            result.status = "SUCCESS";
            Logger.debug(self()+": "+file+" => "+result.molfile);
        }
    }
        
    final HttpExecutionContext ec;    
    public final AsyncCacheApi cache;
    public final Config config;
    public final Environment env;
    public final WSClient wsclient;
    
    final TemporaryFileCreator fileCreator;
    final MessageDigest md;
    final Path work;
    final Duration timeout;
    final ActorSystem actorSystem;
    final List<ActorRef> recognizers = new ArrayList<>();
    
    @Inject
    public MolVecApp (Config config, Environment env, AsyncCacheApi cache,
                      WSClient wsclient, ActorSystem actorSystem,
                      HttpExecutionContext ec) {
        this.config = config;
        this.cache = cache;
        this.env = env;
        this.wsclient = wsclient;
        this.ec = ec;
        this.actorSystem = actorSystem;

        timeout = Duration.ofSeconds(60);
        Config conf = config.getConfig("imago");
        if (conf != null) {
            String exec = conf.getString("exec");
            File file = env.getFile(exec);
            Logger.debug("IMAGO: "+file+" "+file.exists());
        }
        
        conf = config.getConfig("osra");
        if (conf != null) {
            String exec = conf.getString("exec");
            File file = env.getFile(exec);
            Logger.debug("OSRA: "+file+" "+file.exists());
        }

        recognizers.add(actorSystem.actorOf(MolVecActor.props(this), "molvec"));

        String file = config.getString("work");
        if (file == null)
            file = "work";
        File f = new File (env.rootPath(), file);
        f.mkdirs();
        work = f.toPath();

        fileCreator = Files.singletonTemporaryFileCreator();
        try {
            md = MessageDigest.getInstance("sha1");
        }
        catch (Exception ex) {
            throw new RuntimeException ("SHA1 not available");
        }

        Logger.debug("MolVecApp initialized!");
    }

    Image createImage (String mime, File file) {
        try {
            return createImage
                (mime, java.nio.file.Files.readAllBytes(file.toPath()));
        }
        catch (IOException ex) {
            Logger.error("Can't read file: "+file, ex);
        }
        return null;
    }
    
    Image createImage (String mime, byte[] data) {
        Image image = new Image (data);
        image.mime = mime;
        image.sha1 = sha1 (data);
        File f = new File (work.toFile(), image.sha1+".image");
        if (!f.exists()) {
            try {
                Path p = java.nio.file.Files.write(f.toPath(), data);
                Logger.debug(image.sha1+" => "+p);
                // write meta data
                f = new File (work.toFile(), image.sha1+".json");
                FileOutputStream fos = new FileOutputStream (f);
                Image clone = image.clone();
                clone.data = null;
                Json.mapper().writeValue(fos, clone);
                fos.close();
            }
            catch (Exception ex) {
                Logger.error("Can't write image data", ex);
            }
        }
        else {
            Logger.debug("** cached "+f);
        }
        return image;
    }

    Image createImage (String value) {
        // data:[<mime type>][;charset=<charset>][;base64],<encoded data>
        String mime = null, enc = null;
        StringBuilder buf = new StringBuilder ();
        for (int pos = 5; pos < value.length(); ++pos) {
            char ch = value.charAt(pos);
            switch (ch) {
            case ';':
                if (mime == null)
                    mime = buf.toString();
                buf.setLength(0);
                break;
            case ',':
                if (enc == null)
                    enc = buf.toString();
                buf.setLength(0);
                break;
            default:
                buf.append(ch);
            }
        }
        
        Logger.debug("mime:"+mime+" enc:"+enc);
        Image image = Image.EMPTY;
        if ("base64".equalsIgnoreCase(enc)) {
            Base64.Decoder dec = Base64.getDecoder();
            image = createImage (mime, dec.decode(buf.toString()));
            image.enc = enc;
        }
        else {
            Logger.warn("Unknown image encoding: "+enc);
        }
        return image;
    }

    File getImageFile (Image image) {
        File f = new File (work.toFile(), image.sha1+".image");
        if (f.exists())
            return f;
        return null;
    }
    
    Image getImage (String id) {
        Image image = Image.EMPTY;
        try {
            File file = new File (work.toFile(), id+".json");
            if (!file.exists())
                return image;
            
            image = Json.mapper().readValue(file, Image.class);
            
            file = new File (work.toFile(), id+".image");
            if (!file.exists())
                return Image.EMPTY;
            
            Logger.debug(id+" => "+Json.toJson(image));
            image.data = java.nio.file.Files.readAllBytes(file.toPath());
        }
        catch (Exception ex) {
            Logger.error("Can't retrieve image: "+id, ex);
        }
        return image;
    }

    public Result index() {
        return ok(views.html.index.render(null));
    }

    public Result results (String id) {
        Image image = getImage (id);
        if (image != Image.EMPTY) {
            return ok(views.html.index.render(id));
        }
        return ok(views.html.index.render(null));
    }
    
    public Result data (String id, String format) {
        Image image = getImage (id);
        if (image != Image.EMPTY) {
            switch (format) {
            case "image":
                return ok(image.data).as(image.mime);
            case "json":
                return ok(Json.toJson(image));
            }
            return badRequest ("Unknown data format: "+format);
        }
        return notFound ("Unknown image: "+id);
    }

    CompletionStage<Image> parseImage (String value) {
        if (value.startsWith("http")) {
            // assume this is url..
            return wsclient.url(value).get()
                .thenApplyAsync(res -> createImage
                                (res.getContentType(), res.asByteArray()),
                                ec.current());
        }
        else if (value.startsWith("data:")) {
            return supplyAsync (()->createImage (value), ec.current());
        }
        else {
            Logger.error(value+": unknown image encoding!");
        }
        return supplyAsync (()->Image.EMPTY);
    }

    RecognitionResult[] recognize (String engine, Image image) {
        if (image == null || image == Image.EMPTY) {
            Logger.warn("Recognition not performed on empty or null image!");
            return new RecognitionResult[0];
        }
            
        List<CompletableFuture> futures = new ArrayList<>();
        if (engine == null || "all".equalsIgnoreCase(engine)
            || "*".equals(engine)) {
            for (ActorRef actorRef : recognizers)
                futures.add(Patterns.ask(actorRef, image, timeout)
                            .toCompletableFuture());
        }
        else {
            ActorSelection selection =
                actorSystem.actorSelection("/user/"+engine);
            CompletionStage<ActorRef> actor =
                selection.resolveOne(Duration.ofSeconds(5));
            try {
                ActorRef aref = actor.toCompletableFuture().get();
                Logger.debug("** resolved engine: "+engine+" => "+aref);
                futures.add(Patterns.ask(aref, image, timeout)
                            .toCompletableFuture());
            }
            catch (Exception ex) {
                //ex.printStackTrace();
                Logger.error("Unknown engine specified: "+engine, ex);
                return new RecognitionResult[0];
            }
        }
        
        CompletableFuture.allOf
            (futures.toArray(new CompletableFuture[0])).join();
        // there must be a prettier way to do this..
        List<RecognitionResult> results = futures.stream()
            .map(r -> {
                    try {
                        return (RecognitionResult)r.get();
                    }
                    catch (Exception ex) {
                        throw new RuntimeException (ex);
                    }
                }).collect(Collectors.toList());
        /*        
        for (RecognitionResult r : results) {
            Logger.debug(">> "+Json.toJson(r));
        }
        */
        return results.toArray(new RecognitionResult[0]);
    }

    JsonNode toJson (RecognitionResult... results) {
        Image image = null;
        ObjectNode result = Json.newObject();
        for (RecognitionResult r : results) {
            if (image == null) {
                image = r.image;
                //result.put("image", routes.MolVecApp.results(image.sha1).url());
                result.put("image", image.sha1);
            }
            ObjectNode json = (ObjectNode)Json.toJson(r);
            json.remove("image");
            json.remove("engine");
            result.put(r.engine, json);
        }
        return result;
    }
    
    @BodyParser.Of(BodyParser.AnyContent.class)
    public CompletionStage<Result> submit (final String engine,
                                           Http.Request request) {
        Logger.debug(">> "+request.uri());
        Http.MultipartFormData data = request.body().asMultipartFormData();
        if (data != null) {
            Map<String, String[]> form = data.asFormUrlEncoded();
            Logger.debug("form: "+form);
            
            String[] values = form.get("image-data");
            if (values == null) {
                values = form.get("image-src");
            }
            
            if (values == null || values.length == 0)
                return supplyAsync
                    (()->badRequest ("No image data submitted!"), ec.current());
            
            String value = values[0];
            //Logger.debug("** data: "+value);
            final String key = engine+"/"+sha1 (value.getBytes());
            return cache.getOrElseUpdate(key, () -> {
                    Logger.debug("** cache missed: "+key);
                    return parseImage(value).thenApplyAsync
                        (image -> recognize(engine, image), ec.current())
                        .thenApplyAsync(r->ok(toJson (r)), ec.current());
                });
        }
        else {
            Http.RawBuffer raw = request.body().asRaw();
            if (raw != null) {
                File file = raw.asFile();
                Optional<String> mime = request.contentType();
                if (mime.isPresent()) {
                    Logger.debug("raw buffer: "+mime);
                    Image image = createImage (mime.get(), file);
                    if (image != null) {
                        final String key = engine+"/"+image.sha1;
                        return cache.getOrElseUpdate(key, () -> {
                                Logger.debug("** cache missed: "+key);
                                return supplyAsync(()->recognize(engine, image))
                                    .thenApplyAsync
                                    (results -> ok (toJson(results)),
                                     ec.current());
                            });
                    }
                }
                return supplyAsync
                    (()->internalServerError ("Can't process image!"),
                     ec.current());
            }
        }
        
        return supplyAsync (()->badRequest ("Unknown data encoding!"),
                            ec.current());
    }

    String sha1 (byte[] buf) {
        byte[] digest = md.digest(buf);
        StringBuilder sb = new StringBuilder ();
        for (int i = 0; i < 5 /*digest.length*/; ++i)
            sb.append(String.format("%1$02x", digest[i] & 0xff));
        return sb.toString();
    }

    public Result jsRoutes () {
        return ok (JavaScriptReverseRouter.create
                   ("jsRoutes",
                    routes.javascript.MolVecApp.results()
                    )).as("text/javascript");
    }
}

