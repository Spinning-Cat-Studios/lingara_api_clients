import { describe, expect, it } from "vitest";

import { Lingara } from "../client.js";
import { TransportError } from "../errors.js";
import { fakeFetch, json, tokenOk } from "./fixtures/fakeFetch.js";

const CREDS = { clientId: "lgr_cid_unit", clientSecret: "lgr_cs_unit" };

const DEFINITION = { schema: "dialogue.v1", id: "night_market_keepers", start: "greet", "x-layout": { greet: { x: 1, y: 2 } } };
const IR = '{"checkpoints":[],"facts":[],"id":"night_market_keepers","schema":"dialogue.v1","start":"greet"}';
const ETAG = '"9f2c"';

const DIALOGUE = {
  id: "d-1",
  name: "Market",
  created_at: "2026-10-28T09:00:00Z",
  updated_at: "2026-10-28T09:00:00Z",
  draft: { revision: 1, draft_version: 4, definition: DEFINITION },
  active: null,
  warnings: [],
};

function page(id: string, nextCursor: string | null, hasMore: boolean): unknown {
  const item = { id, name: "Market", ir_id: "market", created_at: "2026-10-28T09:00:00Z", updated_at: "2026-10-28T09:00:00Z" };
  return { items: [{ ...item, has_draft: true, active_revision: null, npc_count: 1, checkpoint_count: 2 }], next_cursor: nextCursor, has_more: hasMore };
}

function exported(status: number, headers: Record<string, string>, body: string | null): Response {
  return new Response(body, { status, headers });
}

describe("dialogues", () => {
  /**
   * 29.10.26c AC16: through `client.dialogues`, PUT and PATCH reach the fake with their JSON bodies and
   * `content-type: application/json`; the export answers `body` with the bytes unparsed and both
   * headers, and `notModified` on a `304`, and a missing revision is `malformed_response`; and
   * `pageDialogues` ends on `has_more: false` with its filters resent.
   */
  it("put and patch are sent, the export reads both arms and paging ends", async () => {
    const headers = { "content-type": "application/json", etag: ETAG, "lingara-dialogue-revision": "3" };
    const fake = fakeFetch(
      tokenOk("t"),
      () => json(200, DIALOGUE),
      () => json(200, DIALOGUE),
      () => json(200, DIALOGUE),
      () => exported(200, headers, IR),
      () => exported(304, { etag: ETAG, "lingara-dialogue-revision": "3" }, null),
      () => exported(200, { etag: ETAG }, IR),
      () => json(200, page("d-1", "dl1", true)),
      () => json(200, page("d-2", null, false)),
    );
    const dialogues = new Lingara({ ...CREDS, fetch: fake.fetch }).dialogues;

    const read = await dialogues.getDialogue({ dialogueId: "d-1" });
    await dialogues.saveDialogueDraft({ dialogueId: "d-1" }, { definition: read.draft!.definition, draft_version: read.draft!.draft_version });
    await dialogues.renameDialogue({ dialogueId: "d-1" }, { name: "Renamed" });
    const body = await dialogues.exportDialogue({ dialogueId: "d-1", revision: "draft", layout: "omit" });
    expect(body).toMatchObject({ kind: "body", revision: 3, etag: ETAG, irJson: IR });
    const unchanged = await dialogues.exportDialogue({ dialogueId: "d-1", ifNoneMatch: ETAG });
    expect(unchanged).toMatchObject({ kind: "notModified", revision: 3, etag: ETAG });
    await expect(dialogues.exportDialogue({ dialogueId: "d-1" })).rejects.toThrow(TransportError);
    const ids: string[] = [];
    for await (const item of dialogues.pageDialogues({ limit: 1, name: "market" })) ids.push(item.id);
    expect(ids).toEqual(["d-1", "d-2"]);

    const [, , put, patch, drafted, conditional, , first, second] = fake.calls;
    expect([put!.method, put!.url]).toEqual(["PUT", "https://api.getlingara.com/v1/dialogues/d-1/draft"]);
    expect(JSON.parse(put!.body!)).toEqual({ definition: DEFINITION, draft_version: 4 });
    expect([patch!.method, patch!.url]).toEqual(["PATCH", "https://api.getlingara.com/v1/dialogues/d-1"]);
    expect(JSON.parse(patch!.body!)).toEqual({ name: "Renamed" });
    for (const sent of [put!, patch!]) expect(sent.headers.get("content-type")).toBe("application/json");
    expect(drafted!.url).toBe("https://api.getlingara.com/v1/dialogues/d-1/export?revision=draft&layout=omit");
    expect([drafted!.headers.get("if-none-match"), conditional!.headers.get("if-none-match")]).toEqual([null, ETAG]);
    expect(first!.url).toBe("https://api.getlingara.com/v1/dialogues?limit=1&name=market");
    expect(second!.url).toBe("https://api.getlingara.com/v1/dialogues?cursor=dl1&limit=1&name=market");
    expect(fake.calls).toHaveLength(9);
  });
});
