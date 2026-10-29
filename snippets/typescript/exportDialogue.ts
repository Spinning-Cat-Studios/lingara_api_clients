import { Lingara } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
});
const dialogueId = "4c8e2f1a-9b3d-4e7f-8a6c-2d1e0f9b8a71";
let cached: { etag: string; irJson: string } | undefined;

// lingara:begin exportDialogue
// Cache by ETag: the body is canonical bytes, so store it exactly as
// received. A 304 still spends one unit of the rate budget.
const exported = await client.dialogues.exportDialogue({ dialogueId, ifNoneMatch: cached?.etag });
if (exported.kind === "body") {
  cached = { etag: exported.etag, irJson: exported.irJson };
  console.log("revision", exported.revision, "is new");
} else {
  console.log("revision", exported.revision, "is unchanged");
}
// lingara:end
