# frozen_string_literal: true

module LingaraSnippets
  # client needs no credentials here, since this operation needs no token:
  # Lingara::Client.new is enough.
  def self.get_open_api_document(client)
    # lingara:begin getOpenApiDocument
    document = client.get_open_api_document
    puts "#{document.value["openapi"]} #{document.served_version}"
    # lingara:end
  end
end
