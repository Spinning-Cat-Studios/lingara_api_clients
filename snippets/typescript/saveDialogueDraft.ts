import { Lingara, type DialogueDefinition } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
});
const dialogueId = "4c8e2f1a-9b3d-4e7f-8a6c-2d1e0f9b8a71";
const definition: DialogueDefinition = { schema: "dialogue.v1", id: "night_market_keepers", start: "greet", npcs: [], checkpoints: [], facts: [] };
const draftVersion = 4;

// lingara:begin saveDialogueDraft
import { ApiError } from "@lingara/api";

try {
  const saved = await client.dialogues.saveDialogueDraft({ dialogueId }, { definition, draft_version: draftVersion });
  console.log("saved; the next save sends", saved.draft?.draft_version);
} catch (e) {
  if (!(e instanceof ApiError && e.code === "dialogue_conflict")) throw e;
  // The draft moved since you read it, perhaps by your own retried save.
  // It is never retried for you: re-read, compare, and decide. With no
  // draft at all, send draft_version 0 to open one.
  const current = await client.dialogues.getDialogue({ dialogueId });
  console.log("changed elsewhere; the draft is now at", current.draft?.draft_version ?? 0);
}
// lingara:end
