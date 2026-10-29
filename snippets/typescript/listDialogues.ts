import { Lingara } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
});

// lingara:begin listDialogues
// One page; pass its next_cursor back as `cursor` for the next.
const page = await client.dialogues.listDialogues({ name: "market", limit: 20 });
for (const d of page.items) console.log(d.id, d.name, d.ir_id, d.npc_count, "NPCs", d.checkpoint_count, "checkpoints");
if (page.has_more) console.log("more from", page.next_cursor);
// lingara:end
