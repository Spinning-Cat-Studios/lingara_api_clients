# frozen_string_literal: true

module LingaraSnippets
  # lingara:begin linkEmbedPlayer
  # The link is called with the player's own delegated lgr_at_ token: the one
  # your server received when the player's Lingara account granted your app
  # access (the authorization-code flow, which this library does not run yet).
  # A client built from your client_id: and client_secret: is refused
  # 403 embed_link_needs_grant. Any object with #token and #invalidate(token)
  # is a token source; this one hands back the same token every time, so a
  # revoked grant surfaces as a 401 after one repeat.
  DelegatedToken = Struct.new(:access_token) do
    def token = access_token
    def invalidate(_token) = nil
  end

  def self.delegated_client(access_token, **options)
    Lingara::Client.new(token_source: DelegatedToken.new(Lingara::AccessToken.new(access_token)), **options)
  end

  def self.link_embed_player(client)
    link = client.link_embed_player("guild-42/player-1001").value
    # "existing" is a success too. Call again while kept["lesson_plans"] is
    # non-zero: a plan still being written moves on a later call.
    puts "player-1001 is #{link.status} as #{link.subject}"
  end
  # lingara:end
end
