import { Lingara } from "@lingara/api";

// No credentials: this operation needs no token.
const client = new Lingara();

// lingara:begin getApiVersion
const version = await client.getApiVersion({ id: "2026-09-knowing-tenpounder" });
console.log(version.id, version.state, version.summary);
// lingara:end
