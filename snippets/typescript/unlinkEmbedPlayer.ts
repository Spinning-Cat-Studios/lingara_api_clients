import { Lingara } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
});

// lingara:begin unlinkEmbedPlayer
// On your ordinary client holding embed:mint. A 404 embed_link_not_found
// after a retried call means the link had already ended.
const unlinked = await client.unlinkEmbedPlayer({ playerRef: "player-1001" });
console.log("unlinked under API version", unlinked.servedVersion);
// lingara:end
