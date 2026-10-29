import { Lingara } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
});
const dialogueId = "4c8e2f1a-9b3d-4e7f-8a6c-2d1e0f9b8a71";

// lingara:begin getDialogue
// The draft and the active revision, inline; either may be null.
const dialogue = await client.dialogues.getDialogue({ dialogueId });
if (dialogue.draft) console.log("draft", dialogue.draft.draft_version);
if (dialogue.active) console.log("active revision", dialogue.active.revision, "since", dialogue.active.activated_at);
// lingara:end
