 # Require the dependencies file to load the vendor libraries
require File.expand_path(File.join(File.dirname(__FILE__), 'dependencies'))

class SharepointListItemUpdateV1

  def initialize(input)
    # Set the input document attribute
    @input_document = REXML::Document.new(input)

    # Store the info values in a Hash of info names to values.
    @info_values = {}
    REXML::XPath.each(@input_document,"/handler/infos/info") { |item|
      @info_values[item.attributes['name']] = item.text
    }

    @enable_debug_logging = @info_values['enable_debug_logging'].downcase == 'yes' ||
                            @info_values['enable_debug_logging'].downcase == 'true'

    # Retrieve all of the handler parameters and store them in a hash attribute
    # named @parameters.
    @parameters = {}
    REXML::XPath.match(@input_document, '/handler/parameters/parameter').each do |node|
      # Associate the attribute name to the String value (stripping leading and
      # trailing whitespace)
      @parameters[node.attribute('name').value] = node.text.to_s.strip
    end

    # Initialize the field values hash
    @attachment_field_values = {}
    # For each of the fields in the node.xml file, add them to the hash
    REXML::XPath.match(@input_document, '/handler/attachment_fields/field').each do |node|
      @attachment_field_values[node.attribute('name').value] = node.text
    end
  end
  # This is a required method that is automatically called by the Kinetic Task
  # Engine.
  def execute()
    username = @info_values["username"]
    username = @info_values["domain"]+"\\"+username if !@info_values["domain"].to_s.empty?
    resource = RestClient::Resource.new(
      "#{@info_values['sharepoint_location'].chomp("/")}",
      {
        :user => username,
        :password => @info_values["password"],
        :headers => {
          "Accept" => "application/json; odata=verbose",
          "Content-Type" => "application/json; odata=verbose"
        }
      }
    )

    # Get the FormDigestValue that will be passed at the X-RequestDigest header in the create call
    puts "Retrieving the Form Digest Value" if @enable_debug_logging
    response = resource["/_api/contextinfo"].post("")
    json = JSON.parse(response.body)
    form_digest_value = json["d"]["GetContextWebInformation"]["FormDigestValue"]
    puts "Form Digest Value successfully retrieved and parsed" if @enable_debug_logging

    # Retrieve the Item type of the list
    puts "Attempting to retrieve the type of item that should be created for the list" if @enable_debug_logging
    begin
      response = resource["/_api/web/lists/GetByTitle('#{URI.encode(@parameters['list_name'])}')/ListItemEntityTypeFullName"].get
    rescue RestClient::Exception => e
      puts e.response.body
      raise e.inspect
    end
    type = JSON.parse(response.body)["d"]["ListItemEntityTypeFullName"]
    puts "List items on '#{@parameters['list_name']}' found to be of type '#{type}'" if @enable_debug_logging

    ## Create the ListItem JSON object
    begin
      list_item = JSON.parse(@parameters['item'])
    rescue
      raise "An error was encountered attempting to parse the following JSON: #{@parameters['item']}"
    end

    # Add the list type to the list item metadata
    if list_item.has_key? "__metadata"
      list_item["__metadata"] = list_item["__metadata"].merge({"type" => type})
    else
      list_item["__metadata"] = {"type" => type}
    end

    # Switch out any spaces in the keys for _x0020_
    list_item.keys.each do |k|
      if k.include?(" ")
        list_item[k.gsub(" ","_x0020_")] = list_item[k]
        list_item.delete(k)
      end
    end

    puts "Attempting to update the following item: #{list_item}" if @enable_debug_logging
    begin
      response = resource["/_api/web/lists/GetByTitle('#{URI.encode(@parameters['list_name'])}')/items(#{URI.encode(@parameters['item_id'])})"].post(
        list_item.to_json,
        {
          "X-RequestDigest" => form_digest_value,
          "IF-MATCH" => "*",
          "X-HTTP-Method" => "MERGE"
        }
      )
    rescue RestClient::Exception => e
      puts e.response.body
      raise e.inspect
    end
    puts "Item '#{@parameters['item_id']}' successfully updated" if @enable_debug_logging

    return "<results />"
  end

  # This is a template method that is used to escape results values (returned in
  # execute) that would cause the XML to be invalid.  This method is not
  # necessary if values do not contain character that have special meaning in
  # XML (&, ", <, and >), however it is a good practice to use it for all return
  # variable results in case the value could include one of those characters in
  # the future.  This method can be copied and reused between handlers.
  def escape(string)
    # Globally replace characters based on the ESCAPE_CHARACTERS constant
    string.to_s.gsub(/[&"><]/) { |special| ESCAPE_CHARACTERS[special] } if string
  end
  # This is a ruby constant that is used by the escape method
  ESCAPE_CHARACTERS = {'&'=>'&amp;', '>'=>'&gt;', '<'=>'&lt;', '"' => '&quot;'}
end