package com.kineticdata.bridgehub.adapter.sharepoint;

import com.kineticdata.bridgehub.adapter.BridgeAdapter;
import com.kineticdata.bridgehub.adapter.BridgeError;
import com.kineticdata.bridgehub.adapter.BridgeRequest;
import com.kineticdata.bridgehub.adapter.Count;
import com.kineticdata.bridgehub.adapter.Record;
import com.kineticdata.bridgehub.adapter.RecordList;
import com.kineticdata.commons.v1.config.ConfigurableProperty;
import com.kineticdata.commons.v1.config.ConfigurablePropertyMap;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.slf4j.LoggerFactory;


public class SharepointAdapter implements BridgeAdapter {
    /*----------------------------------------------------------------------------------------------
     * PROPERTIES
     *--------------------------------------------------------------------------------------------*/

    /** Defines the adapter display name */
    public static final String NAME = "Sharepoint Bridge";

    /** Defines the logger */
    protected static final org.slf4j.Logger logger = LoggerFactory.getLogger(SharepointAdapter.class);

    /** Adapter version constant. */
    public static String VERSION;
    /** Load the properties version from the version.properties file. */
    static {
        try {
            java.util.Properties properties = new java.util.Properties();
            properties.load(SharepointAdapter.class.getResourceAsStream("/"+SharepointAdapter.class.getName()+".version"));
            VERSION = properties.getProperty("version");
        } catch (IOException e) {
            logger.warn("Unable to load "+SharepointAdapter.class.getName()+" version properties.", e);
            VERSION = "Unknown";
        }
    }

    /** Defines the collection of property names for the adapter */
    public static class Properties {
        public static final String USERNAME = "Username";
        public static final String PASSWORD = "Password";
        public static final String SERVER_URL = "Server URL";
    }

    private final ConfigurablePropertyMap properties = new ConfigurablePropertyMap(
        new ConfigurableProperty(SharepointAdapter.Properties.USERNAME).setIsRequired(true),
        new ConfigurableProperty(SharepointAdapter.Properties.PASSWORD).setIsRequired(true).setIsSensitive(true),
        new ConfigurableProperty(SharepointAdapter.Properties.SERVER_URL).setIsRequired(true)
    );

    /**
     * Structures that are valid to use in the bridge
     */
    public static final List<String> VALID_STRUCTURES = Arrays.asList(new String[] {
        "Lists"
    });

    private String username;
    private String password;
    private String serverUrl;

    /*---------------------------------------------------------------------------------------------
     * SETUP METHODS
     *-------------------------------------------------------------------------------------------*/

    @Override
    public void initialize() throws BridgeError {
        this.username = properties.getValue(Properties.USERNAME);
        this.password = properties.getValue(Properties.PASSWORD);
        this.serverUrl = properties.getValue(Properties.SERVER_URL);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getVersion() {
        return VERSION;
    }

    @Override
    public void setProperties(Map<String,String> parameters) {
        properties.setValues(parameters);
    }

    @Override
    public ConfigurablePropertyMap getProperties() {
        return properties;
    }

    @Override
    public Count count(BridgeRequest request) throws BridgeError {
        if (!VALID_STRUCTURES.contains(request.getStructure())) {
            throw new BridgeError("Invalid Structure: '" + request.getStructure() + "' is not a valid structure");
        }

        SharepointQualificationParser parser = new SharepointQualificationParser();
        String query = parser.parse(request.getQuery(), request.getParameters());

        JSONArray lists = getSharepointLists(this.serverUrl, this.username, this.password,
            request.getFields(), query);

        return new Count(lists.size());
    }

    @Override
    public Record retrieve(BridgeRequest request) throws BridgeError {
        if (!VALID_STRUCTURES.contains(request.getStructure())) {
            throw new BridgeError("Invalid Structure: '" + request.getStructure() + "' is not a valid structure");
        }

        SharepointQualificationParser parser = new SharepointQualificationParser();
        String query = parser.parse(request.getQuery(),request.getParameters());

        JSONArray lists = getSharepointLists(this.serverUrl, this.username, this.password,
            request.getFields(), query);

        JSONObject list = null;
        if (lists.size() > 1) {
            throw new BridgeError("Multiple results matched an expected single match query.");
        } else if (!lists.isEmpty()) {
            list = (JSONObject)lists.get(0);
        }

        return new Record(list, request.getFields());
    }

    @Override
    public RecordList search(BridgeRequest request) throws BridgeError {
        if (!VALID_STRUCTURES.contains(request.getStructure())) {
            throw new BridgeError("Invalid Structure: '" + request.getStructure() + "' is not a valid structure");
        }

        SharepointQualificationParser parser = new SharepointQualificationParser();
        String query = parser.parse(request.getQuery(),request.getParameters());

        JSONArray lists = getSharepointLists(this.serverUrl, this.username, this.password,
            request.getFields(), query);

        List<Record> records = new ArrayList<Record>();
        List<String> fields = request.getFields();
        for (Object o : lists) {
            // If fields is empty, add all non map fields to the field list
            if (fields == null || fields.isEmpty()) {
                fields = new ArrayList<String>();
                Map<String,Object> list = (Map<String,Object>)o;
                for (Map.Entry<String,Object> entry : list.entrySet()) {
                    if (!(entry.getValue() instanceof Map)) fields.add(entry.getKey());
                }
            }
            records.add(new Record((JSONObject)o));
        }

        // Returning the response
        return new RecordList(fields, records);
    }


    /**************************************************************************
    * HELPER METHODS
    **************************************************************************/

    private JSONArray getSharepointLists(String sharepointUrl, String username, String password,
        List<String> fields, String query) throws BridgeError
    {
        // Build the SharePoint URL
        StringBuilder url = new StringBuilder();
        url.append(sharepointUrl.replaceAll("/\\z", ""));
        url.append("/_api/web/lists");

        // Attempt to retrieve the listName from the query if it has been included
        String listName = null;
        Pattern pattern = Pattern.compile("^listName eq '(\\S*)'");
        Matcher matcher = pattern.matcher(query);
        if (matcher.find()) {
            listName = matcher.group(1);
            // Removing the listName and the and,or,not if it is at the front of the query
            query = query.replaceAll("^listName eq '(\\S*)'", "")
                .replaceAll("(^ and |^ or |^ not )", "");
        }
        if (listName != null) url.append("/GetByTitle('").append(listName).append("')/items");
        url.append("?");

        // Append the necessary query parameters
        if (fields != null && !fields.isEmpty()) {
            url.append("$select=").append(StringUtils.join(fields,",")).append("&");
        }
        if (query  != null && !query.isEmpty()) {
            url.append("$filter=").append(URLEncoder.encode(query));
        }

        logger.debug(url.toString());

        HttpClient client = HttpClients.createDefault();
        HttpGet get = new HttpGet(url.toString());
        // Append the username/password as a Basic Authorization Header
        String credentials = String.format("%s:%s", this.username, this.password);
        byte[] basicAuthBytes = Base64.encodeBase64(credentials.getBytes());
        get.setHeader("Authorization", "Basic " + new String(basicAuthBytes));
        // Set the content type to application/json
        get.setHeader("Accept","application/json; odata=verbose");
        get.setHeader("Content-Type", "application/json");

        String output = "";
        try {
            HttpResponse response = client.execute(get);
            HttpEntity entity = response.getEntity();
            output = EntityUtils.toString(entity);

            if (response.getStatusLine().getStatusCode() == 401) {
                logger.error(output);
                throw new BridgeError("401 Unauthorized: Unable to authenticate with the SharePoint server with the given username/password.");
            } else if (response.getStatusLine().getStatusCode() != 200) {
                logger.error(output);
                throw new BridgeError("Unexpected Error (Code: "+String.valueOf(response.getStatusLine().getStatusCode())+
                    "): Check the Bridgehub logs for more details");
            }
        }
        catch (IOException e) {
            throw new BridgeError("Unable to make a connection to properly execute the query to Sharepoint",e);
        }

        JSONObject json = (JSONObject)JSONValue.parse(output);
        JSONObject d = (JSONObject)json.get("d");
        JSONArray results = (JSONArray)d.get("results");
        return results;
    }
}
