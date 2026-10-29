import { Lingara } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
});
const canvasId = "6b1f0c3e-2a4d-4e8f-9b7a-1c2d3e4f5a6b";

// lingara:begin getCanvas
// The draft and the active revision, inline; either may be null.
const canvas = await client.canvases.getCanvas({ canvasId });
if (canvas.draft) console.log("draft", canvas.draft.draft_version, "has", canvas.draft.definition.nodes.length, "nodes");
if (canvas.active) console.log("active revision", canvas.active.revision, "made", Object.keys(canvas.active.objects));
// lingara:end
