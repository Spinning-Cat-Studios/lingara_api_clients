import { Lingara } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
});
const dialogueId = "4c8e2f1a-9b3d-4e7f-8a6c-2d1e0f9b8a71";

// lingara:begin renameDialogue
const renamed = await client.dialogues.renameDialogue({ dialogueId }, { name: "Night market keepers" });
console.log(renamed.name);
// lingara:end
