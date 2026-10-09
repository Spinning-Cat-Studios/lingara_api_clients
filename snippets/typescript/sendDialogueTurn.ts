import { Lingara, type DialogueEntry } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
});
let history: DialogueEntry[] = [];

// lingara:begin sendDialogueTurn
// One turn: at most 12 history entries, each and the line at most 500
// characters. A turn is never retried, since each attempt spends NPC cells.
const line = "饺子多少钱？";
const turn = client.sendDialogueTurn({
  npc: { name: "Auntie Lin", persona: "a street-food vendor who likes to haggle" },
  source_lang: "en",
  target_lang: "zh",
  level: 3,
  line,
  history,
});
let reply = "";
for await (const ev of turn) {
  if (ev.event === "delta") {
    process.stdout.write(ev.data.text);
    reply += ev.data.text;
  }
}
// Send the reply back next turn, cut to its first 500 characters.
const said: DialogueEntry[] = [
  { speaker: "player", text: line },
  { speaker: "npc", text: [...reply].slice(0, 500).join("") },
];
history = [...history, ...said].slice(-12);
// lingara:end
