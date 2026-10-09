# frozen_string_literal: true

module LingaraSnippets
  def self.delete_embed_player(client)
    # lingara:begin deleteEmbedPlayer
    # Deletes the player and revokes its tokens. An unknown player_ref is a
    # success too, so calling it twice is safe.
    client.delete_embed_player("guild-42/player-1001")
    puts "player-1001 deleted"
    # lingara:end
  end
end
