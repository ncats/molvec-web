package controllers;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import java.security.MessageDigest;

import javax.inject.*;
import com.typesafe.config.Config;
import com.fasterxml.jackson.databind.JsonNode;

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

import models.*;

public class MolVecApp extends Controller {
    final HttpExecutionContext ec;    
    public final AsyncCacheApi cache;
    public final Config config;
    public final Environment env;
    public final WSClient wsclient;

    TemporaryFileCreator fileCreator;
    MessageDigest md;
    Path work;
    
    @Inject
    public MolVecApp (Config config, Environment env, AsyncCacheApi cache,
                      WSClient wsclient, HttpExecutionContext ec) {
        this.config = config;
        this.cache = cache;
        this.env = env;
        this.wsclient = wsclient;
        this.ec = ec;

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

    Image createImage (String mime, byte[] data) {
        Image image = new Image ();
        image.mime = mime;
        return createImage (image, data);
    }
    
    Image createImage (Image image, byte[] data) {
        image.data = data;
        image.sha1 = sha1 (data);
        File f = new File (work.toFile(), image.sha1);
        image.file = fileCreator.create(f.toPath());
        if (!f.exists()) {
            try {
                Path p = java.nio.file.Files.write(f.toPath(), data);
                Logger.debug(image.sha1+" => "+p);
                // write meta data
                f = new File (work.toFile(), image.sha1+".json");
                FileOutputStream fos = new FileOutputStream (f);
                Json.mapper().writeValue(fos, image);
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
        Image image = new Image ();
        StringBuilder buf = new StringBuilder ();
        for (int pos = 5; pos < value.length(); ++pos) {
            char ch = value.charAt(pos);
            switch (ch) {
            case ';':
                if (image.mime == null)
                    image.mime = buf.toString();
                buf.setLength(0);
                break;
            case ',':
                if (image.enc == null)
                    image.enc = buf.toString();
                buf.setLength(0);
                break;
            default:
                buf.append(ch);
            }
        }
        
        Logger.debug("mime:"+image.mime+" enc:"+image.enc);
        if ("base64".equalsIgnoreCase(image.enc)) {
            Base64.Decoder dec = Base64.getDecoder();
            image = createImage (image, dec.decode(buf.toString()));
        }
        else {
            Logger.warn("Unknown image encoding: "+image.enc);
            image = Image.EMPTY;
        }
        return image;
    }

    Image getImage (String id) {
        Image image = Image.EMPTY;
        try {
            File file = new File (work.toFile(), id+".json");
            if (!file.exists())
                return image;
            
            image = Json.mapper().readValue(file, Image.class);
            
            file = new File (work.toFile(), id);
            if (!file.exists())
                return Image.EMPTY;

            image.data = java.nio.file.Files.readAllBytes(file.toPath());
            Logger.debug(id+" => "+image);
        }
        catch (Exception ex) {
            Logger.error("Can't retrieve image: "+id, ex);
        }
        return image;
    }

    public Result index() {
        return ok(views.html.index.render());
    }

    public Result image (String id) {
        Image image = getImage (id);
        if (image != Image.EMPTY) {
            return ok(image.data).as(image.mime);
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

    @BodyParser.Of(BodyParser.MultipartFormData.class)
    public CompletionStage<Result> submit (final String engine,
                                           Http.Request request) {
        Logger.debug(">> "+request.uri());
        Http.MultipartFormData data = request.body().asMultipartFormData();
        if (data == null)
            return supplyAsync
                    (()->badRequest ("No image data submitted!"));
            
        Map<String, String[]> form = data.asFormUrlEncoded();
        Logger.debug("form: "+form);
        
        String[] values = form.get("image-data");
        if (values == null) {
            values = form.get("image-src");
        }
        
        if (values == null || values.length == 0)
            return supplyAsync
                (()->badRequest ("No image data submitted!"));

        String value = values[0];
        //Logger.debug("** data: "+value);
        final String key = "data/"+sha1 (value.getBytes());
        return cache.getOrElseUpdate(key, () -> {
                Logger.debug("** cache missed: "+key);

                return parseImage(value)
                    .thenApplyAsync(image -> ok (Json.toJson(image)),
                                    ec.current());
            });
    }

    String sha1 (byte[] buf) {
        byte[] digest = md.digest(buf);
        StringBuilder sb = new StringBuilder ();
        for (int i = 0; i < 5 /*digest.length*/; ++i)
            sb.append(String.format("%1$02x", digest[i] & 0xff));
        return sb.toString();
    }
}

