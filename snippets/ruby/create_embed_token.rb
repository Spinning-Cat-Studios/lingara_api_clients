# frozen_string_literal: true

module LingaraSnippets
  def self.create_embed_token(client)
    # lingara:begin createEmbedToken
    # On your server, never on a player's device: client is a metered
    # client holding embed:mint. Pass on the Lingara-Embed-Version header the
    # player kit sent, to be told when that build is out of date.
    embed_version = "1.1.0"
    minted = client.create_embed_token(player_ref: "guild-42/player-1001", scopes: ["embed:play", "events:write"],
      embed_version: embed_version).value
    # subject names this player in every event and webhook: store it beside them.
    puts "player-1001 is #{minted.subject}"
    # A notice is for you, the developer: the build is behind or deprecated.
    puts "embed #{embed_version} is out of date: #{minted.update_available}" if minted.update_available
    # Hand the lgr_et_… token and its expiry to the player kit. It lives 900 s
    # and is never refreshed: mint again when the kit asks.
    handoff = {token: minted.token.expose_secret, expires_at: minted.expires_at.utc.iso8601, expires_in: minted.expires_in}
    # lingara:end
    handoff
  end
end
