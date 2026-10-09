import { Lingara } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
});

// lingara:begin deleteEmbedPlayer
// Deletes the player and revokes their tokens. An unknown player is still a
// success, so it is safe to repeat.
const deleted = await client.deleteEmbedPlayer({ playerRef: "player-1001" });
console.log("deleted under API version", deleted.servedVersion);
// lingara:end
