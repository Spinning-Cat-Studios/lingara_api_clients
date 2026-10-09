import { Lingara } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
});

// lingara:begin createEmbedToken
// On your server, from a metered client holding embed:mint. The token
// (lgr_et_…) lives 900 s and is never refreshed: mint again when the
// player's device asks.
const minted = await client.createEmbedToken({ player_ref: "player-1001", scopes: ["embed:play", "events:read"] });
// Store the subject beside your player: it is how an event names them.
console.log("player-1001 is", minted.subject);
// Hand the token and its expiry to the player's device, and log neither.
const forDevice = JSON.stringify({ token: minted.exposeToken(), expires_at: minted.expiresAt, expires_in: minted.expiresIn });
// lingara:end
