import { Lingara, type LingaraOptions, type TokenSource } from "@lingara/api";

// lingara:begin linkEmbedPlayer
// The link is called with the player's own delegated lgr_at_ token: the one
// your server received when the player's Lingara account granted your app
// access (the authorization-code flow, which this library does not run yet).
// A client built from your clientId and clientSecret is refused
// 403 embed_link_needs_grant. This source hands back the same token every
// time, so a revoked grant surfaces as a 401 after one repeat.
function delegatedClient(accessToken: string, options: LingaraOptions = {}): Lingara {
  const tokenSource: TokenSource = { token: async () => accessToken, invalidate: () => undefined };
  return new Lingara({ ...options, tokenSource });
}

async function linkEmbedPlayer(client: Lingara): Promise<void> {
  const link = await client.linkEmbedPlayer({ playerRef: "player-1001" });
  // "existing" is a success too. Call again while kept.lesson_plans is
  // non-zero: a plan still being written moves on a later call.
  console.log(`player-1001 is ${link.status} as ${link.subject}`);
}
// lingara:end

await linkEmbedPlayer(delegatedClient(process.env.LINGARA_PLAYER_ACCESS_TOKEN!));
