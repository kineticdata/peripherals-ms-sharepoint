# Require the dependencies file to load the vendor libraries
require File.expand_path(File.join(File.dirname(__FILE__), 'dependencies'))

class SharepointDocumentCreateV1
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
    if @info_values['homepage_url'][-1..-1] != '/'
      @info_values['homepage_url'] += '/'
    end

    # Checks to see if the appropriate extension is in the document name.
    # If it is missing, it is appended onto the name.
    ext = ".txt"
    if @parameters['document_name'][-ext.length..-1] != ext
      puts "Adding the appropriate extension to the document name" if @enable_debug_logging
      @parameters['document_name'] += ext
    end

    # A URL is configured so that the file will be put in the correct document
    # library. Also, the authentication is then appended to that URL to make
    # up the final URL.
    puts "Formatting the URL for the HTTP Put call" if @enable_debug_logging
    destination_url = @info_values['homepage_url'] + @parameters['doc_library_name'] + '/' + @parameters['document_name']
    destination_url.gsub!("https://","")
    destination_url.gsub!(" ","%20")

    auth_url = "https://" + @info_values['username'] + ":" + @info_values['password']

    final_url = auth_url + "@" + destination_url

    # The actual call is made and the document is uploaded. If there is a file with
    # the same name in the library, it will be overwritten by this new file.
    puts "Uploading the file" if @enable_debug_logging
    begin
      RestClient.put final_url,@parameters['document_contents']
    rescue RestClient::Conflict => error
      raise StandardError, "Invalid Document Library Location: Please check the spelling and location of your document library and try again"
    end

    # Returns the nil results
    "<results/>"
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