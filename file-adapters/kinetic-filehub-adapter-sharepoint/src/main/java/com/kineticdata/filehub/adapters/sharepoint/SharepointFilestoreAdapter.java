package com.kineticdata.filehub.adapters.sharepoint;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.kineticdata.commons.v1.config.ConfigurableProperty;
import com.kineticdata.commons.v1.config.ConfigurablePropertyMap;
import com.kineticdata.filehub.adapter.FilestoreAdapter;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SharepointFilestoreAdapter implements FilestoreAdapter {
    
    /** Static class logger. */
    private static final Logger LOGGER = LoggerFactory.getLogger(SharepointFilestoreAdapter.class);

    /** Defines the adapter display name. */
    public static final String NAME = "Microsoft Sharepoint";
    
    /** Defines the collection of property names for the adapter. */
    public static class Properties {
        public static final String SHAREPOINT_SITE = "Sharepoint Site Location";
        public static final String USERNAME = "Username";
        public static final String PASSWORD = "Password";
        public static final String FOLDER_PATH = "Relative Folder Path";
    }
    
    /** Adapter version constant. */
    public static String VERSION;
    /** Load the properties version from the version.properties file. */
    static {
        try (
            InputStream inputStream = SharepointFilestoreAdapter.class.getResourceAsStream(
                "/"+SharepointFilestoreAdapter.class.getName()+".version")
        ) {
            java.util.Properties properties = new java.util.Properties();
            properties.load(inputStream);
            VERSION = properties.getProperty("version")+"-"+properties.getProperty("build");
        } catch (IOException e) {
            LOGGER.warn("Unable to load "+SharepointFilestoreAdapter.class.getName()+" version properties.", e);
            VERSION = "Unknown";
        }
    }
    
    /** 
     * Specifies the configurable properties for the adapter.  These are populated as part of object
     * construction so that the collection of properties (default values, menu options, etc) are 
     * available before the adapter is configured.  These initial properties can be used to 
     * dynamically generate the list of configurable properties, such as when the Kinetic Filehub
     * application prepares the new Filestore display.
     */
    private final ConfigurablePropertyMap properties = new ConfigurablePropertyMap(
        new ConfigurableProperty(Properties.SHAREPOINT_SITE)
            .setIsRequired(true)
            .setDescription("The URL path to the SharePoint site (ie. https://sharepoint.kineticdata.com/sites/subsite)"),
        new ConfigurableProperty(Properties.USERNAME)
            .setIsRequired(true),
        new ConfigurableProperty(Properties.PASSWORD)
            .setIsRequired(true)
            .setIsSensitive(true),
        new ConfigurableProperty(Properties.FOLDER_PATH)
            .setIsRequired(true)
            .setDescription("Server Relative Url for a SharePoint Document folder (ie. /Shared Documents/Filehub)")
    );

    // Create a basic credentials provider that should be populated with the user/pass on initialize
    private CredentialsProvider credsProvider = new BasicCredentialsProvider();
    
    /*----------------------------------------------------------------------------------------------
     * CONFIGURATION
     *--------------------------------------------------------------------------------------------*/
    
    /**
     * Initializes the filestore adapter.  This method will be called when the properties are first
     * specified, and when the properties are updated.
     * 
     * @param propertyValues 
     */
    @Override
    public void initialize(Map<String, String> propertyValues) {
        // Set the configurable properties
        properties.setValues(propertyValues);
        credsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(
            properties.getValue(Properties.USERNAME),properties.getValue(Properties.PASSWORD)
        ));

        validateProperties();
    }

    /**
     * Returns the display name for the adapter.
     * 
     * @return 
     */
    @Override
    public String getName() {
        return NAME;
    }

    /**
     * Returns the collection of configurable properties for the adapter.
     * 
     * @return 
     */
    @Override
    public ConfigurablePropertyMap getProperties() {
        return properties;
    }
    
    
    /*----------------------------------------------------------------------------------------------
     * IMPLEMENTATION METHODS
     *--------------------------------------------------------------------------------------------*/

    @Override
    public void deleteDocument(String path) {
        // Calculate the folder location
        String folderLocation = properties.getValue(Properties.FOLDER_PATH)+"/"+path;
        // Make the call to Sharepoint
        HttpClient client = buildAuthenticatedHttpClient();
        String formDigestValue = retrieveFormDigestValue();
        HttpPost post = new HttpPost(buildFileUrl(folderLocation));
        post.addHeader("X-RequestDigest", formDigestValue);
        post.addHeader("X-HTTP-METHOD", "DELETE");

        try {
            HttpResponse response = client.execute(post);

            // Validate based on the returned error codes that the file was successfully uploaded
            if (response.getStatusLine().getStatusCode() != 200) {
                LOGGER.error("Error Code: "+response.getStatusLine().getStatusCode());
                LOGGER.error(EntityUtils.toString(response.getEntity()));
                throw new RuntimeException("An Unknown Error has occurred. See the Filehub logs for more details.");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public SharepointDocument getDocument(String path) {
        // Calculate the file location
        String fileLocation = properties.getValue(Properties.FOLDER_PATH)+"/"+path;
        // Get Document will always be a file (because clicking on a folder calls getDocuments again)
        HttpClient client = buildAuthenticatedHttpClient();
        HttpGet getDetails = new HttpGet(buildFileUrl(fileLocation));
        HttpGet getContent = new HttpGet(buildFileUrl(fileLocation)+"/$value");
        
        JSONObject details;
        InputStream content;
        try {
            HttpResponse detailsResp = client.execute(getDetails);
            HttpResponse contentResp = client.execute(getContent);

            // Validate based on the returned error codes that the file was successfully uploaded
            for (HttpResponse response : new HttpResponse[] { detailsResp, contentResp }) {
                if (response.getStatusLine().getStatusCode() != 200) {
                    LOGGER.error("Error Code: "+response.getStatusLine().getStatusCode());
                    LOGGER.error(EntityUtils.toString(response.getEntity()));
                    throw new RuntimeException("An Unknown Error has occurred. See the Filehub logs for more details.");
                }
            }

            InputStream tempInputStream = contentResp.getEntity().getContent();
            ByteArrayOutputStream outStream = new ByteArrayOutputStream();
            
            byte[] buffer = new byte[8 * 1024];
            int bytesRead;
            while ((bytesRead = tempInputStream.read(buffer)) != -1) {
                outStream.write(buffer, 0, bytesRead);
            }
            content = new ByteArrayInputStream(outStream.toByteArray());
            IOUtils.closeQuietly(tempInputStream);
            IOUtils.closeQuietly(outStream);

            JSONObject json = (JSONObject)JSONValue.parse(EntityUtils.toString(detailsResp.getEntity()));
            details = (JSONObject)json.get("d");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return new SharepointDocument(properties.getValue(Properties.FOLDER_PATH),details,content);
    }

    @Override
    public List<SharepointDocument> getDocuments(String path) throws IOException {
        // Initialize the result
        List<SharepointDocument> results = new ArrayList<>();
        // Calculate the folder location
        String folderLocation = properties.getValue(Properties.FOLDER_PATH)+"/"+path;

        // Retrieve the files and folders in the current directory
        HttpClient client = buildAuthenticatedHttpClient();

        HttpGet getFiles = new HttpGet(buildFolderUrl(folderLocation)+"/files");
        HttpGet getFolders = new HttpGet(buildFolderUrl(folderLocation)+"/folders?$expand=Properties");

        try {
            HttpResponse filesResp = client.execute(getFiles);
            HttpResponse foldersResp = client.execute(getFolders);

            for (HttpResponse response : new HttpResponse[] { filesResp,foldersResp }) {
                if (response.getStatusLine().getStatusCode() != 200) {
                    LOGGER.error("Error Code: "+response.getStatusLine().getStatusCode());
                    LOGGER.error(EntityUtils.toString(response.getEntity()));
                    throw new RuntimeException("An Unknown Error has occurred. See the Filehub logs for more details.");
                }

                JSONObject json = (JSONObject)JSONValue.parse(EntityUtils.toString(response.getEntity()));
                JSONObject d = (JSONObject)json.get("d");
                JSONArray array = (JSONArray)d.get("results");
                for (Object o : array) {
                    results.add(new SharepointDocument(
                        properties.getValue(Properties.FOLDER_PATH),
                        (JSONObject)o)
                    );
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return results;
    }
    
    @Override
    public String getRedirectDelegationUrl(String path, String friendlyFilename) {
        throw new RuntimeException("Redirect delegation is not supported.");
    }

    @Override
    public String getVersion() {
        return VERSION;
    }

    @Override
    public void putDocument(String path, InputStream inputStream, String contentType) {
        // Calculate the folder location
        String subfolders = path.substring(0,path.lastIndexOf("/"));
        String name = path.substring(path.lastIndexOf("/")+1);
        String folderLocation = properties.getValue(Properties.FOLDER_PATH)+"/"+subfolders;

        // Create any folders along the folder location path that haven't been already created
        // because the Sharepoint call doesn't automatically create subfolders if they don't exist
        createFolderPath(folderLocation);

        // Attempt to upload the file to the folder using the built URL
        HttpClient client = buildAuthenticatedHttpClient();
        String formDigestValue = retrieveFormDigestValue();
        StringBuilder postUrl = new StringBuilder();
        try {
            postUrl.append(buildFolderUrl(folderLocation));
            postUrl.append("/files/add(url=");
            postUrl.append("'").append(URLEncoder.encode(name,"UTF-8")).append("'");
            postUrl.append(")");
        } catch (Exception e) {
            throw new RuntimeException("Error building the upload url",e);
        }
        HttpPost post = new HttpPost(postUrl.toString());
        post.addHeader("X-RequestDigest", formDigestValue);

        try {
            // Convert the input stream to a byte array before passing to Sharepoint because it was
            // not handling stream uploads well originally (wasn't uploading the whole file)
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            IOUtils.copyLarge(inputStream, baos);
            byte[] payload = baos.toByteArray();
            IOUtils.closeQuietly(baos);
            
            post.setEntity(new ByteArrayEntity(payload,ContentType.parse(contentType)));
            HttpResponse response = client.execute(post);

            // Validate based on the returned error codes that the file was successfully uploaded
            if (response.getStatusLine().getStatusCode() != 200) {
                LOGGER.error("Error Code: "+response.getStatusLine().getStatusCode());
                LOGGER.error(EntityUtils.toString(response.getEntity()));
                throw new RuntimeException("An Unknown Error has occurred. See the Filehub logs for more details.");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean supportsRedirectDelegation() {
        return false;
    }

    /*----------------------------------------------------------------------------------------------
     * HELPER METHODS
     *--------------------------------------------------------------------------------------------*/

    private void validateProperties() {
        HttpClient client = buildAuthenticatedHttpClient();

        // Attempt to retrieve the folder using the inputted creds and paths
        HttpGet get = new HttpGet(buildFolderUrl(properties.getValue(Properties.FOLDER_PATH)));
        try {
            HttpResponse response = client.execute(get);
            
            // Validate based on the returned error codes that the creds are correct and the
            // sharepoint site and folder exist
            if (response.getStatusLine().getStatusCode() == 401) {
                throw new RuntimeException("401 Unauthorized: The inputted Username & Password aren't valid for the Sharepoint Site and/or Folder location.");
            } else if (response.getStatusLine().getStatusCode() == 404) {
                LOGGER.error(EntityUtils.toString(response.getEntity()));
                throw new RuntimeException("404 Not Found: The Sharepoint site or folder location couldn't be found. See the Filehub logs for more details.");
            } else if (response.getStatusLine().getStatusCode() != 200) {
                LOGGER.error("Error Code: "+response.getStatusLine().getStatusCode());
                LOGGER.error(EntityUtils.toString(response.getEntity()));
                throw new RuntimeException("An Unknown Error has occurred. See the Filehub logs for more details.");
            }

            // Consume the response to close the request
            EntityUtils.consume(response.getEntity());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private HttpClient buildAuthenticatedHttpClient() {
        // Make the request to upload the file to the given location
        // Create the Http Client
        List<Header> headers = new ArrayList<Header>();
        // Append the username/password as a Basic Authorization Header
        String credentials = String.format("%s:%s",
            properties.getValue(Properties.USERNAME),
            properties.getValue(Properties.PASSWORD)
        );
        byte[] basicAuthBytes = Base64.encodeBase64(credentials.getBytes());
        headers.add(new BasicHeader("Authorization", "Basic " + new String(basicAuthBytes)));
        headers.add(new BasicHeader("Accept","application/json; odata=verbose"));

        return HttpClients.custom()
                .setDefaultHeaders(headers)
                .build();
    }

    private String retrieveFormDigestValue() {
        // Attempt to upload the file to the folder using the built URL
        HttpClient client = buildAuthenticatedHttpClient();
        HttpPost post = new HttpPost(properties.getValue(Properties.SHAREPOINT_SITE.replaceAll("/\\z",""))
            +"/_api/contextinfo");
        String formDigestValue = "";
        try {
            HttpResponse response = client.execute(post);
            JSONObject json = (JSONObject)JSONValue.parse(EntityUtils.toString(response.getEntity()));
            JSONObject result = (JSONObject)json.get("d");
            JSONObject contextInfo = (JSONObject)result.get("GetContextWebInformation");
            formDigestValue = (String)contextInfo.get("FormDigestValue");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return formDigestValue;
    }

    private String buildFolderUrl(String relativeFolderPath) {
        // Build the URL to a folder given a relative path
        StringBuilder url;
        try {
            url = new StringBuilder();
            url.append(properties.getValue(Properties.SHAREPOINT_SITE.replaceAll("/\\z","")));
            url.append("/_api/web/GetFolderByServerRelativeUrl('");
            url.append(URLEncoder.encode(relativeFolderPath,"UTF-8").replace("+","%20"));
            url.append("')");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("Error building the Sharepoint URL folder path.",e);
        }

        return url.toString();
    }

    private String buildFileUrl(String relativeFilePath) {
        // Build the URL to a folder given a relative path
        StringBuilder url;
        try {
            url = new StringBuilder();
            url.append(properties.getValue(Properties.SHAREPOINT_SITE.replaceAll("/\\z","")));
            url.append("/_api/web/GetFileByServerRelativeUrl('");
            url.append(URLEncoder.encode(relativeFilePath,"UTF-8").replace("+","%20"));
            url.append("')");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("Error building the Sharepoint URL folder path.",e);
        }

        return url.toString();
    }

    private void createFolderPath(String folderLocation) {
        // Create the Http Client
        List<Header> headers = new ArrayList<Header>();
        // Append the username/password as a Basic Authorization Header
        String credentials = String.format("%s:%s",
            properties.getValue(Properties.USERNAME),
            properties.getValue(Properties.PASSWORD)
        );
        byte[] basicAuthBytes = Base64.encodeBase64(credentials.getBytes());
        headers.add(new BasicHeader("Authorization", "Basic " + new String(basicAuthBytes)));
        headers.add(new BasicHeader("Accept","application/json; odata=verbose"));

        HttpClient client = HttpClients.custom()
                .setDefaultHeaders(headers)
                .build();

        createFolderPath(folderLocation,client);
    }

    private void createFolderPath(String folderLocation, HttpClient client) {
        String[] folders = folderLocation.split("/");
        for (int i=folders.length-1; i>0; i--) {
            HttpGet get = new HttpGet(buildFolderUrl(folderLocation));
            try {
                HttpResponse response = client.execute(get);
                EntityUtils.consumeQuietly(response.getEntity());
                if (response.getStatusLine().getStatusCode() == 404) {
                    // Check to see if the previous folders exist as well
                    createFolderPath(folderLocation.substring(0, folderLocation.lastIndexOf(folders[i]) - 1), client);
                    // Create the new folder
                    HttpPost digestPost = new HttpPost(properties.getValue(Properties.SHAREPOINT_SITE.replaceAll("/\\z",""))
                        +"/_api/contextinfo");
                    response = client.execute(digestPost);
                    JSONObject json = (JSONObject)JSONValue.parse(EntityUtils.toString(response.getEntity()));
                    JSONObject result = (JSONObject)json.get("d");
                    JSONObject contextInfo = (JSONObject)result.get("GetContextWebInformation");
                    String formDigestValue = (String)contextInfo.get("FormDigestValue");

                    // Attempt to upload the file to the folder using the built URL
                    LOGGER.debug("Creating the new folder at "+folderLocation);
                    StringBuilder url = new StringBuilder();
                    url.append(properties.getValue(Properties.SHAREPOINT_SITE.replaceAll("/\\z","")));
                    url.append("/_api/web/folders/add('");
                    url.append(URLEncoder.encode(folderLocation,"UTF-8").replace("+","%20"));
                    url.append("')");
                    HttpPost post = new HttpPost(url.toString());
                    post.addHeader("X-RequestDigest",formDigestValue);
                    client.execute(post);
                } else {
                    break;
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}