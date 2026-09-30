# frozen_string_literal: true

module LingaraSnippets
  # client needs no credentials here, since this operation needs no token:
  # Lingara::Client.new is enough.
  def self.get_api_version(client, version_id)
    # lingara:begin getApiVersion
    version = client.get_api_version(version_id).value
    puts "#{version.id}: #{version.state}#{", sunset #{version.sunset_at}" if version.sunset_at}"
    # lingara:end
  end
end
