# frozen_string_literal: true

module LingaraSnippets
  # client needs no credentials here, since this operation needs no token:
  # Lingara::Client.new is enough.
  def self.list_api_versions(client)
    # lingara:begin listApiVersions
    list = client.list_api_versions.value
    puts "current: #{list.current}"
    list.versions.each { |version| puts "#{version.id} #{version.state}" }
    # lingara:end
  end
end
