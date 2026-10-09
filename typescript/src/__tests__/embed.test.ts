import { inspect } from "node:util";

import { describe, expect, it } from "vitest";

import { MintedToken, mintedToken } from "../embed.js";
import { TransportError } from "../errors.js";
import { encodeSegment } from "../transport.js";

const TOKEN = "lgr_et_unit0000000000000000000000000000000000000";

const ANSWER = {
  token: TOKEN,
  expires_at: "2026-10-01T09:27:44Z",
  expires_in: 900,
  subject: "lgr_sub_unit0f1e2d3c4b5a6978",
  scopes: ["embed:play", "events:read"],
  account_linked: false,
};

describe("embedding", () => {
  /**
   * 1.10.26w AC13: a MintedToken renders [REDACTED] in JSON.stringify,
   * String() and util.inspect while exposeToken() returns the value; an
   * answer missing `subject` is refused as malformed_response with nothing of
   * the body; and encodeSegment writes every reserved byte as upper-case %XX.
   */
  it("a minted token renders redacted and a segment encodes every reserved byte", () => {
    const minted = mintedToken(ANSWER);
    expect(minted).toBeInstanceOf(MintedToken);
    for (const rendering of [JSON.stringify(minted), String(minted), `${minted}`, inspect(minted, { depth: 10 })]) {
      expect(rendering).toContain("[REDACTED]");
      expect(rendering).not.toContain(TOKEN);
    }
    expect(minted.exposeToken()).toBe(TOKEN);
    expect(minted).toMatchObject({ expiresAt: ANSWER.expires_at, expiresIn: 900, subject: ANSWER.subject, accountLinked: false });
    expect(minted.scopes).toEqual(ANSWER.scopes);

    const missing: Record<string, unknown> = { ...ANSWER };
    delete missing["subject"];
    const refused = (() => {
      try {
        return mintedToken(missing);
      } catch (e) {
        return e;
      }
    })();
    expect(refused).toBeInstanceOf(TransportError);
    expect((refused as TransportError).kind).toBe("malformed_response");
    expect((refused as TransportError).cause).toBeUndefined();
    expect(inspect(refused, { depth: 10 })).not.toContain(TOKEN);
    expect(() => mintedToken({ ...ANSWER, token: "lgr_at_not_an_embed_token" })).toThrow(TransportError);
    expect(() => mintedToken({ ...ANSWER, expires_in: "900" })).toThrow(TransportError);

    expect(encodeSegment("guild/42 (é)*")).toBe("guild%2F42%20%28%C3%A9%29%2A");
    expect(encodeSegment("A-z0_9.~!'")).toBe("A-z0_9.~%21%27");
  });
});
