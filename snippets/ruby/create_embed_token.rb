# frozen_string_literal: true

module LingaraSnippets
  def self.create_embed_token(client)
    # lingara:begin createEmbedToken
    # On your server, never on a player's device: client is a metered
    # client holding embed:mint.
    minted = client.create_embed_token(player_ref: "guild-42/player-1001", scopes: ["embed:play", "events:write"]).value
    # subject names this player in every event and webhook: store it beside them.
    puts "player-1001 is #{minted.subject}"
    # Hand the lgr_et_… token and its expiry to the player kit. It lives 900 s
    # and is never refreshed: mint again when the kit asks.
    handoff = {token: minted.token.expose_secret, expires_at: minted.expires_at.utc.iso8601, expires_in: minted.expires_in}
    # lingara:end
    handoff
  end
end
