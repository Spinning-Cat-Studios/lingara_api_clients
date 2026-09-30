# frozen_string_literal: true

# The Ruby examples the documentation site shows. Each marked region is
# vendored at a released tag; ruby/test/snippets_test.rb runs every method
# here against the unit-test fake.

# lingara:begin auth
require "lingara"
# lingara:end

module LingaraSnippets
  def self.auth(client_id, client_secret)
    # lingara:begin auth
    client = Lingara::Client.new(client_id: client_id, client_secret: client_secret)
    # lingara:end
    client
  end
end
