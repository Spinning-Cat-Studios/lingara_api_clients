# frozen_string_literal: true

module LingaraSnippets
  def self.unlink_embed_player(client)
    # lingara:begin unlinkEmbedPlayer
    # On your ordinary client holding embed:mint. A 404 embed_link_not_found
    # after a retried call means the link had already ended.
    client.unlink_embed_player("guild-42/player-1001")
    puts "player-1001 unlinked"
    # lingara:end
  end
end
