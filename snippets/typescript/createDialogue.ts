import { Lingara, type DialogueDefinition } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
});

// lingara:begin createDialogue
// The definition is a dialogue.v1 document; the server validates it on
// every save. Warnings are advice, never a refusal.
const definition: DialogueDefinition = {
  schema: "dialogue.v1",
  id: "night_market_keepers",
  start: "greet",
  npcs: [{ id: "old_wei", name: "Old Wei" }],
  checkpoints: [{ id: "greet", goal: "Greet the stranger", may_disclose: [], edges: [{ id: "thanked", to: "success", when: "the player says thank you" }] }],
  facts: [],
};
const created = await client.dialogues.createDialogue({ name: "Night market", definition });
console.log(created.id, "draft", created.draft?.draft_version);
for (const w of created.warnings) console.log("warning", w.code, "at", w.path);
// lingara:end
