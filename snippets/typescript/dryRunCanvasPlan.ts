import { Lingara } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
});

// lingara:begin dryRunCanvasPlan
// Judges a plan without applying it. A refused op is a verdict in a 200
// answer, never an error: branch on its code, and show its English error
// only as a fallback.
const run = await client.canvases.dryRunCanvasPlan({
  ops: [
    { handle: "c1", kind: "client", op: "create", body: { name: "Lesson server", scopes: ["events:read"] } },
    { handle: "w1", kind: "webhook", op: "create", client: { handle: "c1" }, body: { url: "https://hooks.example.com/lingara", event_types: ["lesson_plan.ready"] } },
  ],
});
for (const v of run.verdicts) {
  console.log(v.handle, v.verdict, v.verdict === "refused" ? `${v.code}: ${v.error}` : (v.changes ?? []).join(","));
}
if (!run.ok) console.log("fix the refused ops before you apply");
// lingara:end
