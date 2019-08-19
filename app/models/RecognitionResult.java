package models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

public class RecognitionResult {
    @JsonIgnore
    public Image image;
    public String engine;
    public Double elapsed; // in seconds
    public String status;
    public String molfile;

    public RecognitionResult () {
    }
    public RecognitionResult (Image image) {
        this.image = image;
    }

    @JsonProperty(value="image")
    public String getImage () {
        return image != null ? image.sha1 : null;
    }
}
