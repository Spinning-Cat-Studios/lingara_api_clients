import { Lingara } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
});
const canvasId = "6b1f0c3e-2a4d-4e8f-9b7a-1c2d3e4f5a6b";

// lingara:begin activateCanvas
// After your apply: the draft_version you applied, whether every op landed,
// and the object each node handle made. Replaying an activate that already
// landed answers 409 canvas_conflict and writes nothing.
const active = await client.canvases.activateCanvas(
  { canvasId },
  { draft_version: 4, outcome: "complete", objects: { n1: { id: "0b9e4c2d-7a1f-4e3b-9d8c-5f6a7b8c9d0e" } } },
);
console.log("active revision", active.active?.revision);
// lingara:end
