# frozen_string_literal: true

module LingaraSnippets
  def self.page_canvases(client)
    # lingara:begin pageCanvases
    # Every canvas the filters match, page by page, the filters resent each
    # time. It never waits, and stops on the first error.
    client.canvases.page_canvases(kinds: ["webhook"]).each do |canvas|
      puts "#{canvas.name}: #{canvas.node_count} node(s)"
    end
    # lingara:end
  end
end
