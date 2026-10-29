import { Lingara, type CanvasDefinition } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
});
const canvasId = "6b1f0c3e-2a4d-4e8f-9b7a-1c2d3e4f5a6b";
const definition: CanvasDefinition = { schema: 2, nodes: [{ id: "n1", kind: "client", position: [0, 0] }] };
const draftVersion = 4;

// lingara:begin saveCanvasDraft
import { ApiError } from "@lingara/api";

try {
  const saved = await client.canvases.saveCanvasDraft({ canvasId }, { definition, draft_version: draftVersion });
  console.log("saved; the next save sends", saved.draft?.draft_version);
} catch (e) {
  if (!(e instanceof ApiError && e.code === "canvas_conflict")) throw e;
  // The draft moved since you read it, perhaps by your own retried save.
  // It is never retried for you: re-read, compare the draft with the one you
  // sent, and decide which to keep.
  const current = await client.canvases.getCanvas({ canvasId });
  const same = JSON.stringify(current.draft?.definition) === JSON.stringify(definition);
  console.log(same ? "already saved" : "changed elsewhere", "at", current.draft?.draft_version);
  // To keep yours, save again with current.draft.draft_version.
}
// lingara:end
