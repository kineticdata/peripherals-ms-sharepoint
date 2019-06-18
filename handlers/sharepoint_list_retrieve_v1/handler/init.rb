# Require the dependencies file to load the vendor libraries
require File.expand_path(File.join(File.dirname(__FILE__), 'dependencies'))

class SharepointListRetrieveV1
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
  end

  def execute()
    # Ensure the hompage_url configuration value ends with a trailing slash
    @info_values['homepage_url'] += '/' if @info_values['homepage_url'][-1..-1] != '/'

    # Initialize the Savon client
    client = configure_savon(
      @info_values['homepage_url'],
      @info_values['domain'],
      @info_values['username'],
      @info_values['password'],
      @info_values['self-signed_cert']
    )

    begin
      # Retrieve the list ID for use in SOAP call to get list contents
      id = get_list_id(client,@parameters['list_name'])

      # SOAP request to get list contents
      response = client.request(:get_list_items) do
        soap.body = {:listName => id}
      end
    # Wrapping the errors for human readability
    rescue Savon::HTTP::Error => error
      if error.http.code == 401
        raise StandardError, "Invalid Username/Password Combination and/or insufficient privileges"
      else
        raise
      end
    rescue Exception => ex
      if ex.class.to_s == "SocketError"
        raise StandardError, "Connection Failed: Invalid URL"
      else
        raise
      end
    end

    # Parsing the SOAP response, which returns the final JSON table
    result = parse_response(response)
    
    # Return the results
    <<-RESULTS
    <results>
      <result name="JSON">#{escape(result.to_json)}</result>
    </results>
    RESULTS
  end

  # Configures Savon while also checking to make sure that the homepage url 
  # is a valid value
  def configure_savon(homepage_url,domain,username,password,self_signed_cert)
    puts "Configuring Savon" if @enable_debug_logging
    if !@enable_debug_logging
      Savon.configure do |config|
        config.log = false
        config.log_level = :error
        HTTPI.log = false
      end
    end

    # Create the client
    site = homepage_url + "_vti_bin/Lists.asmx?wsdl"
    username = domain + "\\" + username
    client = Savon::Client.new do |wsdl,http,wsse|
      http.auth.basic username, password
      wsdl.document = site
    end

    # A self-signed certificate will not verify, so this ssl verify is turned
    # off so that openssl doesn't throw on excpetion when testing with a
    # self-signed certificate
    client.http.auth.ssl.verify_mode = :none if @info_values['self-signed_cert'].downcase == "true"

    return client
  end

  # This methods gets the list ID for a list using the list title that the 
  # user passes to the handler.
  def get_list_id(client,list_name)
    puts "Getting the listName" if @enable_debug_logging
    # Send the request to get the list info
    begin
      response = client.request(:get_list) do
        soap.body = {:listName => list_name}
      end
    rescue Savon::SOAP::Fault
      raise StandardError, "The list '#{@parameters['list_name']}' cannot be found on the Sharepoint site"
    rescue Exception => ex
      raise
    end

    # Retrieving the from the response
    return response.body[:get_list_response][:get_list_result][:list][:@name]
  end

  # This helper method parses through the response and cleans up the row data, 
  # while also compiling an array containing every default column and all custom columns
  # that have data in at least one row. This is all returned as a JSON table.
  def parse_response(response)
    puts "Parsing/Cleaning up the response" if @enable_debug_logging
    data = response.body[:get_list_items_response][:get_list_items_result][:listitems][:data]

    # initialize needed variables
    items = []
    # retrieving the number of list items
    list_length = data[:@item_count].to_i

    # If there is only one item in the list, the JSON response is not warpped 
    # in an array, so the record can be added directly
    if list_length == 1
      # Sort the keys
      keys = data[:row].keys.sort {|a,b| a.to_s <=> b.to_s}
      # Build up the item by 
      item = keys.inject({}) do |hash, key|
        hash.merge!(key.to_s.gsub(/^@ows_/,"").gsub("_x0020_","_") => data[:row][key])
      end
      # Add the item to the list
      items.push(item)
    # If these are multiple items in the list, this iterates through the list
    # items, cleaning each one up
    elsif list_length > 0
      data[:row].each do |row|
        # Sort the keys
        keys = row.keys.sort {|a,b| a.to_s <=> b.to_s}
        # Build up the item
        item = keys.inject({}) do |hash, key|
          hash.merge!(key.to_s.gsub(/^@ows_/,"").gsub("_x0020_","_") => row[key])
        end
        # Add the item to the list
        items.push(item)
      end
    end

    # Build the results
    return {:list_length => items.length, :items => items}
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