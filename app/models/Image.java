package models;

import play.libs.Files;
import com.fasterxml.jackson.annotation.JsonIgnore;

public class Image {
    public static final Image EMPTY = new Image ();

    public String sha1;    
    public String mime;
    public String enc;
    @JsonIgnore
    public byte[] data;
    @JsonIgnore
    public Files.TemporaryFile file;

    public Image () {
    }
    
    public Image (byte[] data) {
        this.data = data;
    }

    public int getSize () { return data != null ? data.length : -1; }
}
