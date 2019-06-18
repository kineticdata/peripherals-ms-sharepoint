# Require the dependencies file to load the vendor libraries
require File.expand_path(File.join(File.dirname(__FILE__), 'dependencies'))

class SharepointSiteCreateV1
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
    if @parameters['title'] == ''
      raise StandardError, "Invalid Entry: Missing Site Title"
    elsif @parameters['url'] == ''
      raise StandardError, "Invalid Entry: Missing New Site Url"
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

    # A hash that contains the values of most of the possible Site types to keep
    # handler input as readable as possible.
    site_template_values = {'Team Site' => 'STS#0', 'Blank Site' => 'STS#1', 'Document Workspace' => 'STS#2',
      'Basic Meeting Workspace' => 'MPS#0', 'Blank Meeting Workspace' => 'MPS#1', "Decision Meeting Workspace" => 'MPS#2',
      'Social Meeting Workspace' => 'MPS#3', 'Multipage Meeting Workspace' => 'MPS#4', "Blog Site" => 'BLOG#0',
      'Wiki Site' => 'WIKI#0', 'Group Work Site' => 'SGS#0'}

    # The Locale ID as assigned by Microsoft. This ID is used to specifiy what the location and
    # locale of the new Site is. Right now set as 'English - United States'. More LCID values 
    # can be found at http://msdn.microsoft.com/en-us/goglobal/bb964664.aspx
    lcid = 1033

    # Send the request
    puts "Making the SOAP call to create the new task" if @enable_debug_logging
    begin
      # Sends the request using a helper method so that the parameters are changed to
      # local variables so they are included in the bindings of the Savon request block
      response = send_request(
        client,
        @parameters['url'],
        @parameters['title'],
        @parameters['description'],
        lcid,
        site_template_values[@parameters['site_template']]
      )
    rescue Exception => error
      if error.http.code == 401
        raise StandardError, "Invalid Username/Password Combination and/or insufficient privileges"
      elsif error.http.code == 500
        # Takes the error from the XML error response and wraps it as a StandardError
        # for increased readability
        doc = REXML::Document.new(error.http.body)
        REXML::XPath.match(doc, "soap:Envelope/soap:Body/soap:Fault/detail/errorstring").each do |node|
          raise StandardError, node.text
        end
      else
        raise
      end
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
    site = homepage_url.gsub(/\/$/,"") + "/_vti_bin/Sites.asmx?wsdl"
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

  # This helper method sends the Savon SOAP request
  def send_request(client, url, name, description, lcid, site_template)
    response = client.request(:create_web) do
        soap.body = {:url => url, :title => name, :description => description, :templateName => site_template, 
          :language => lcid, :locale => lcid, :collationLocale => lcid, :unique_permissions => "false",
          :anonymous => "false", :presence => "false"}
    end
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