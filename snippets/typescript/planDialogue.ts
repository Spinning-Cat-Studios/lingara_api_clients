import { Lingara, type DialogueDefinition } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
});
declare const ferryman: DialogueDefinition; // your dialogue.v2 Procedural document

// lingara:begin planDialogue
// The first plan of a Procedural dialogue. Inside the embed kits the kit calls
// this for you. Never retried: each call spends one embed:play unit, a keep too.
const answer = await client.planDialogue({
  document: ferryman,
  npc: "ma_ren",
  reason: "start",
  id_prefix: "p1",
  turns: 0,
  beats: 0,
  history: [{ speaker: "player", text: "Can you take me across?" }],
});
if (answer.status === "planned") {
  for (const beat of answer.fragment.beats) console.log(beat.id, beat.atom, beat.angle ?? "");
} else {
  // A keep is a result: your frontier stands (on a first plan, walk the seed).
  console.log("kept:", answer.reason);
}
// lingara:end
