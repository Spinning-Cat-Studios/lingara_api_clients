import { Lingara } from "@lingara/api";

// No credentials: this operation needs no token.
const client = new Lingara();

// lingara:begin getOpenApiDocument
const document = await client.getOpenApiDocument();
console.log(document.servedVersion, Object.keys(document));
// lingara:end
