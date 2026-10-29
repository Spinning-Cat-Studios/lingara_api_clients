import { Lingara } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
});
const canvasId = "6b1f0c3e-2a4d-4e8f-9b7a-1c2d3e4f5a6b";

// lingara:begin renameCanvas
// A PATCH: only the name changes, and no draft_version is needed.
const renamed = await client.canvases.renameCanvas({ canvasId }, { name: "Lesson plan webhook" });
console.log("now called", renamed.name);
// lingara:end
