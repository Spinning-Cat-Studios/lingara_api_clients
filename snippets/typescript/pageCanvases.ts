import { Lingara } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
});

// lingara:begin pageCanvases
// Every canvas the filters match: the helper follows next_cursor and resends
// the filters on every page. It never sleeps or polls.
for await (const canvas of client.canvases.pageCanvases({ scopes: ["events:read"] })) {
  const made = canvas.objects.map((o) => `${o.handle}=${o.id}`).join(", ");
  console.log(canvas.id, canvas.name, made || "nothing applied yet");
}
// lingara:end
