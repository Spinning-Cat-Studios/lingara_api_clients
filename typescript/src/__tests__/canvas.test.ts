import { describe, expect, it } from "vitest";

import { Lingara } from "../client.js";
import { TransportError } from "../errors.js";
import { fakeFetch, json, tokenOk } from "./fixtures/fakeFetch.js";

const CREDS = { clientId: "lgr_cid_unit", clientSecret: "lgr_cs_unit" };

const DEFINITION = {
  schema: 2,
  nodes: [{ id: "n1", kind: "client" as const, position: [0.5, 1.5], fields: { name: "Lesson server", x_editor_hint: { collapsed: true } } }],
};

const CANVAS = {
  id: "c-1",
  name: "Hooks",
  created_at: "2026-10-20T09:00:00Z",
  updated_at: "2026-10-20T09:00:00Z",
  draft: { revision: 1, draft_version: 4, definition: DEFINITION, objects: {} },
  active: null,
};

function page(id: string, nextCursor: string | null, hasMore: boolean): unknown {
  const item = { id, name: "Hooks", recipe: "blank", created_at: "2026-10-20T09:00:00Z", updated_at: "2026-10-20T09:00:00Z" };
  const rest = { has_draft: true, active_revision: null, node_count: 1, node_kinds: ["client"], scopes: [], objects: [] };
  return { items: [{ ...item, ...rest }], total: null, next_cursor: nextCursor, has_more: hasMore };
}

describe("canvases", () => {
  /**
   * 16.10.26ak AC9: through `client.canvases`, PUT and PATCH reach the fake with their JSON bodies and
   * `content-type: application/json`; a definition read then saved keeps an unknown `fields` key; and
   * `pageCanvases` ends on `has_more: false` with its filters resent, and raises `malformed_response`
   * on a page with `has_more` but no cursor.
   */
  it("put and patch are sent, the definition round-trips and paging ends", async () => {
    const fake = fakeFetch(
      tokenOk("t"),
      () => json(200, CANVAS),
      () => json(200, CANVAS),
      () => json(200, CANVAS),
      () => json(200, page("c-1", "cv1", true)),
      () => json(200, page("c-2", null, false)),
      () => json(200, page("c-3", null, true)),
    );
    const canvases = new Lingara({ ...CREDS, fetch: fake.fetch }).canvases;

    const read = await canvases.getCanvas({ canvasId: "c-1" });
    await canvases.saveCanvasDraft({ canvasId: "c-1" }, { definition: read.draft!.definition, draft_version: read.draft!.draft_version });
    await canvases.renameCanvas({ canvasId: "c-1" }, { name: "Renamed" });
    const ids: string[] = [];
    for await (const item of canvases.pageCanvases({ limit: 1, kinds: ["client", "webhook"] })) ids.push(item.id);
    expect(ids).toEqual(["c-1", "c-2"]);
    await expect(canvases.pageCanvases().next()).rejects.toThrow(TransportError);

    const [, , put, patch, first, second] = fake.calls;
    expect([put!.method, put!.url]).toEqual(["PUT", "https://api.getlingara.com/v1/canvases/c-1/draft"]);
    expect(JSON.parse(put!.body!)).toEqual({ definition: DEFINITION, draft_version: 4 });
    expect([patch!.method, patch!.url]).toEqual(["PATCH", "https://api.getlingara.com/v1/canvases/c-1"]);
    expect(JSON.parse(patch!.body!)).toEqual({ name: "Renamed" });
    for (const sent of [put!, patch!]) expect(sent.headers.get("content-type")).toBe("application/json");
    expect(first!.url).toBe("https://api.getlingara.com/v1/canvases?limit=1&kinds=client%2Cwebhook");
    expect(second!.url).toBe("https://api.getlingara.com/v1/canvases?cursor=cv1&limit=1&kinds=client%2Cwebhook");
    expect(fake.calls).toHaveLength(7);
  });
});
