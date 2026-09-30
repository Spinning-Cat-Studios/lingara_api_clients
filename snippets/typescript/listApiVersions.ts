import { Lingara } from "@lingara/api";

// No credentials: this operation needs no token.
const client = new Lingara();

// lingara:begin listApiVersions
const list = await client.listApiVersions();
console.log("current:", list.current);
for (const v of list.versions) console.log(v.id, v.state);
// lingara:end
