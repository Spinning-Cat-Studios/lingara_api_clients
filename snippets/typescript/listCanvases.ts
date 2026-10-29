import { Lingara } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
});

// lingara:begin listCanvases
// One page, newest first; pass next_cursor back as cursor for the next one,
// or let pageCanvases walk them all.
const page = await client.canvases.listCanvases({ status: "live", kinds: ["webhook"], limit: 20 });
for (const canvas of page.items) {
  console.log(canvas.name, canvas.recipe, canvas.active_revision ?? "never activated");
}
if (page.has_more) console.log("more from", page.next_cursor);
// lingara:end
