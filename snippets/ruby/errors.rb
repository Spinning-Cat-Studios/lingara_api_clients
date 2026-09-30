# frozen_string_literal: true

module LingaraSnippets
  def self.errors(client)
    # lingara:begin errors
    begin
      client.get_usage
    rescue Lingara::ApiError => e
      # A refusal from the API: status, code (stable) and message (localised).
      puts "#{e.status} #{e.code}: #{e.message}"
      puts "retry after #{e.retry_after}s" if e.retry_after
    rescue Lingara::OAuthError => e
      # The token endpoint refused the credentials or the scopes.
      puts "#{e.status} #{e.error}: #{e.description}"
    rescue Lingara::MaintenanceError => e
      puts "under maintenance"
      puts "retry after #{e.retry_after}s" if e.retry_after
    rescue Lingara::TransportError => e
      # No usable answer: :connect, :tls, :reset, :timeout, and so on.
      puts "transport: #{e.kind}"
    end
    # lingara:end
  end
end
