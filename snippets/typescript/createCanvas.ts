import { Lingara } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
});

// lingara:begin createCanvas
// A client node wired to a webhook node. The canvas starts as a draft; the
// answer does not echo the recipe.
const canvas = await client.canvases.createCanvas({
  name: "Tell my server when a lesson plan is ready",
  recipe: "lesson_plan_ready",
  definition: {
    schema: 2,
    nodes: [
      { id: "n1", kind: "client", position: [0, 0] },
      { id: "n2", kind: "webhook", position: [1, 0] },
    ],
    edges: [{ from: "n1", to: "n2" }],
  },
});
console.log("created", canvas.id, "at draft_version", canvas.draft?.draft_version);
// lingara:end
