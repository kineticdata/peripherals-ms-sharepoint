# Require the dependencies file to load the vendor libraries
require File.expand_path(File.join(File.dirname(__FILE__), 'dependencies'))

class SharepointDiscussionBoardPostV1
  def initialize(input)
    # Set the input document attribute
    @input_document = REXML::Document.new(input)

    # Store the info values in a Hash of info names to values.
    @info_values = {}
    REXML::XPath.each(@input_document,"/handler/infos/info") { |item|
      @info_values[item.attributes['name']] = item.text
    }

    @enable_debug_logging = @info_values['enable_debug_logging'] == 'Yes'

    # Store parameters values in a Hash of parameter names to values.
    @parameters = {}
    REXML::XPath.match(@input_document, '/handler/parameters/parameter').each do |node|
      @parameters[node.attribute('name').value] = node.text.to_s
    end

    puts "Checking for various input errors" if @enable_debug_logging
    if @parameters['subject'] == ''
      raise StandardError, "Invalid Entry: Missing Discussion Post Subject"
    end
  end

  def execute()
    # Initialize the Savon client
    client = configure_savon(
      @info_values['homepage_url'], 
      @info_values['domain'], 
      @info_values['username'], 
      @info_values['password']
    )

    # Build the SOAP document
    post = build_new_post_xml(
      'Title' => @parameters['subject'],
      'Body' => @parameters['body']
    )

    # Send the request
    begin
      puts "Making the SOAP call to create the new post" if @enable_debug_logging
      # Define a block local variable so that it is included in the bindings for the 
      # block below
      discussion_list = @parameters['discussion_list']
      response = client.request(:update_list_items) do
        soap.body = Gyoku.xml({:"ins0:listName!" => discussion_list, :"ins0:updates!" => post})
      end

      # Retrieve the result
      result = response.body[:update_list_items_response][:update_list_items_result][:results][:result]

      # Wrap the errors for human readability
      if result[:error_code] != "0x00000000"
        if result[:error_code] == "0x81020014"
          raise StandardError, "Invalid List: '#{@parameters['discussion_list']}' is not a valid discussion board list"
        else
          raise StandardError, result[:error_text]
        end
      end 

    rescue Savon::HTTP::Error => error
      if error.http.code == 401
        raise StandardError, "Invalid Username/Password Combination and/or insufficient privileges"
      elses
        raise
      end

    rescue Exception => ex
      raise
    end

    # Return the results
    return "<results/>"
  end

  # Configures Savon while also checking to make sure that the base url is
  # a valid value
  def configure_savon(homepage_url, domain, username, password)
    # Configure Savon logging
    puts "Configuring Savon" if @enable_debug_logging
    if !@enable_debug_logging
      Savon.configure do |config|
        config.log = false
        config.log_level = :error
        HTTPI.log = false
      end
    end

    # Checking to make sure that the URL uses SSL so that the username and 
    # password are securely sent
    if homepage_url.match(/^https/).nil?
      raise StandardError, "Invalid Url: Homepage Url must use SSL for security purposes."
    end

    # Create the client
    site = homepage_url.gsub(/\/$/,"") + "/_vti_bin/Lists.asmx?wsdl"
    username = domain + "\\" + username
    client = Savon::Client.new do |wsdl,http,wsse|
      http.auth.basic username, password
      wsdl.document = site
    end
    
    # A self-signed certificate will not verify, so this ssl verify is turned
    # off for developemnt purposes. THIS SHOULD ONLY BE USED FOR PURELY
    # DEVELOPMENT ACCOUNTS BECAUSE THE INFORMATION IS NOT GUARANTEED TO BE SECURE.
    # client.http.auth.ssl.verify_mode = :none

    # Return the client
    return client
  end

  def build_new_post_xml(field_values)
    puts "Building the Batch XML that contains the information for the new post" if @enable_debug_logging
    # Create the base document
    doc = REXML::Document.new
    # Create the Batch root element and add it to the document
    batch = REXML::Element.new("Batch")
    doc.add_element(batch)
    # Create the Method element and add it to the Batch
    method = REXML::Element.new("Method")
    method.add_attributes({"ID" => "1", "Cmd" => "New"})
    batch.add_element(method)

    field_values.each_pair do |key,value|
      if value != ""
        field = REXML::Element.new("Field")
        field.add_attribute("Name",key)
        field.text = value
        method.add_element(field)
      end
    end

    puts "Converting the REXML object to a string object" if @enable_debug_logging
    return doc.root.to_s
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