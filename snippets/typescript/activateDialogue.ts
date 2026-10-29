import { Lingara } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
});
const dialogueId = "4c8e2f1a-9b3d-4e7f-8a6c-2d1e0f9b8a71";

// lingara:begin activateDialogue
// Promote the draft you read: a stale or missing draft is 409 dialogue_conflict.
const current = await client.dialogues.getDialogue({ dialogueId });
if (current.draft) {
  const activated = await client.dialogues.activateDialogue({ dialogueId }, { draft_version: current.draft.draft_version });
  console.log("active revision", activated.active?.revision);
}
// lingara:end
