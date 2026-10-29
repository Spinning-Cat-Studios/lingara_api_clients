# frozen_string_literal: true

module LingaraSnippets
  def self.list_canvases(client)
    # lingara:begin listCanvases
    # One page of your canvases, newest first. Each list filter is sent
    # comma-separated; pass page.next_cursor back as cursor: for the next
    # page, or let page_canvases walk them all.
    page = client.canvases.list_canvases(status: "live", kinds: ["webhook"], limit: 20).value
    page.items.each { |canvas| puts "#{canvas.name}: #{canvas.recipe}, #{canvas.objects.size} object(s)" }
    puts "#{page.total} canvases in all" if page.total
    # lingara:end
  end
end
