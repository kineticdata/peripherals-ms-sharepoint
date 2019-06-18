package com.kineticdata.filehub.adapters.sharepoint;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

import com.kineticdata.filehub.adapter.Document;

import org.apache.tika.Tika;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SharepointDocument implements Document {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(SharepointDocument.class);
    
    private static final String UNKNOWN_CONTENT_TYPE = "application/octet-stream";
    
    private String baseFolder;
    private String url;
    private Boolean isFile;
    private InputStream fileContent;
    private Long fileSize = 0L;
    private Date createdAt;
    private Date updatedAt;

    /*----------------------------------------------------------------------------------------------
     * CONSTRUCTORS
     *--------------------------------------------------------------------------------------------*/

    public SharepointDocument(String baseFolder, JSONObject spResourceJson) {
        this(baseFolder,spResourceJson,null);
    }
    
    public SharepointDocument(String baseFolder, JSONObject spResourceJson, InputStream fileContent) {
        this.baseFolder = baseFolder;
        this.fileContent = fileContent;
        // Find out if the inputted JSONObject is a file or a folder
        JSONObject metadata = (JSONObject)spResourceJson.get("__metadata");
        this.isFile = "SP.File".equals((String)metadata.get("type"));
        // Parse the values from the inputted JSON into variables
        this.url = (String)spResourceJson.get("ServerRelativeUrl");
        // Parse all the file variables
        if (this.isFile) {
            this.fileSize = Long.valueOf((String)spResourceJson.get("Length"));
            this.createdAt = parseDate((String)spResourceJson.get("TimeCreated"));
            this.updatedAt = parseDate((String)spResourceJson.get("TimeLastModified"));
        } 
        // Parse all the folder variables
        else {
            JSONObject properties = (JSONObject)spResourceJson.get("Properties");
            if (properties.containsKey("vti_x005f_timecreated")) {
                this.createdAt = parseDate((String)properties.get("vti_x005f_timecreated"));
                this.updatedAt = parseDate((String)properties.get("vti_x005f_timelastmodified"));
            } else {
                this.createdAt = new Date();
                this.updatedAt = new Date();
            }
        }
    }

    /*----------------------------------------------------------------------------------------------
     * IMPLEMENTATION METHODS
     *--------------------------------------------------------------------------------------------*/
    
    @Override
    public String getContentType() {
        String result;
        try {
            Tika tika = new Tika();
            result = fileContent != null ? tika.detect(this.fileContent) : tika.detect(this.url);
        } catch (IOException e) {
            LOGGER.trace("Unable to determine file content type.", e);
            result = UNKNOWN_CONTENT_TYPE;
        }
        return result;
    }

    @Override
    public Date getCreatedAt() {
        return this.createdAt;
    }

    @Override
    public String getName() {
        return url.substring(url.lastIndexOf('/')+1);
    }

    @Override
    public String getPath() {
        // The path the the substring of the baseFolder plus 1 to remove the trailing / as well
        return url.substring(baseFolder.length()+1);
    }

    @Override
    public Long getSizeInBytes() {
        return this.fileSize;
    }

    @Override
    public Date getUpdatedAt() {
        return this.updatedAt;
    }

    @Override
    public boolean isFile() {
        return isFile;
    }

    @Override
    public InputStream openStream() throws FileNotFoundException {
        if (fileContent != null) {
            return this.fileContent;
        } else {
            return new ByteArrayInputStream("".getBytes());
        }
    }
    
    /*----------------------------------------------------------------------------------------------
     * METHODS
     *--------------------------------------------------------------------------------------------*/

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
    private Date parseDate(String dateTimeString) {
        // Remove the trailing 'Z' off the date time string if it is present
        String strippedDT = dateTimeString.replaceAll("Z\\z","");
        // Parse the stripped date time
        try {
            return DATE_FORMAT.parse(strippedDT);
        } catch (ParseException e) {
            throw new RuntimeException("There was an error parsing the created or modified date for "+
                "this Sharepoint Resource: "+this.url,e);
        }
    }
}
