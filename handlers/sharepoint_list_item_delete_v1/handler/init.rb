 # Require the dependencies file to load the vendor libraries
require File.expand_path(File.join(File.dirname(__FILE__), 'dependencies'))

class SharepointListItemDeleteV1
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

    # Delete the Item
    puts "Attempting to delete the item '#{@parameters['item_id']}'" if @enable_debug_logging
    begin
      response = resource["/_api/web/lists/GetByTitle('#{URI.encode(@parameters['list_name'])}')/items(#{URI.encode(@parameters['item_id'])})"].post(
        "",
        {
          "X-RequestDigest" => form_digest_value,
          "IF-MATCH" => "*",
          "X-HTTP-Method" => "DELETE"
        }
      )
    rescue RestClient::Exception => e
      puts e.response.body
      raise e.inspect
    end
    puts "Item '#{@parameters['item_id']}' successfully deleted" if @enable_debug_logging

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