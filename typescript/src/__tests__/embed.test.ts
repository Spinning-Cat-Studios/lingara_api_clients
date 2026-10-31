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

  /**
   * 31.10.26g AC2: a well-formed notice is copied field for field, nulls
   * kept, and a message of exactly 280 astral code points (560 UTF-16 units)
   * is kept; none, absent or null, is null; a malformed notice is null and the
   * token is still minted; a malformed token field still throws; toJSON shows
   * updateAvailable while the token stays redacted.
   */
  it("31.10.26g AC2 update_available is copied or dropped", () => {
    const smileys = "\u{1F600}".repeat(280);
    for (const notice of [
      { latest: null, deprecated: true, message: "Use 1.2.0" },
      { latest: "1.2.0", deprecated: false, message: smileys },
    ]) {
      const minted = mintedToken({ ...ANSWER, update_available: notice });
      expect(minted.updateAvailable).toEqual(notice);
      const rendering = JSON.stringify(minted);
      expect(JSON.parse(rendering).updateAvailable).toEqual(notice);
      expect(rendering).toContain("[REDACTED]");
      expect(rendering).not.toContain(TOKEN);
    }
    for (const none of [ANSWER, { ...ANSWER, update_available: null }]) {
      expect(mintedToken(none).updateAvailable).toBeNull();
    }
    for (const malformed of [
      5,
      { latest: "1.2.0", message: null },
      { latest: 7, deprecated: true, message: null },
      { latest: null, deprecated: true, message: `${smileys}x` },
    ]) {
      const minted = mintedToken({ ...ANSWER, update_available: malformed });
      expect(minted.updateAvailable).toBeNull();
      expect(minted.exposeToken()).toBe(TOKEN);
    }
    expect(() => mintedToken({ ...ANSWER, expires_in: "900", update_available: null })).toThrow(TransportError);
  });
});
