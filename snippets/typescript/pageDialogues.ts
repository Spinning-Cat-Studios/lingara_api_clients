import { Lingara } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
});

// lingara:begin pageDialogues
// Every dialogue the filters match: the helper follows next_cursor and
// resends the filters on every page. It never sleeps or polls.
for await (const d of client.dialogues.pageDialogues({ name: "market" })) {
  console.log(d.id, d.name, d.active_revision ?? "never activated");
}
// lingara:end
