package models;

import java.util.Base64;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

public class Image {
    public static final Image EMPTY = new Image ();

    public String sha1;    
    public String mime;
    public String enc;
    @JsonIgnore
    public byte[] data;

    public Image () {
    }
    
    public Image (byte[] data) {
        this.data = data;
    }

    public int getSize () { return data != null ? data.length : -1; }
    
    @JsonProperty(value="data")
    public String getData () {
        if (data != null)
            return "data:"+mime+";base64,"
                +Base64.getEncoder().encodeToString(data);
        return null;
    }

    public Image clone () {
        Image img = new Image ();
        img.sha1 = sha1;
        img.mime = mime;
        img.enc = enc;
        img.data = data;
        return img;
    }
}
