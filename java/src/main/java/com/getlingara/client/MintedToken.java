package com.getlingara.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.client.model.EmbedToken;
import com.getlingara.client.model.UpdateAvailable;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * A player's embed token, minted by {@link LingaraClient#createEmbedToken} (ADR 1.10.26w D3). Its
 * {@link #token()} is a bearer credential for one player's data for 900 seconds, so it renders as
 * {@code [REDACTED]} like every other token (CONTRACT.md K1); {@code token().exposeSecret()} is the
 * one way to read it, to hand it to the player kit.
 *
 * <p>A final class and not a record, for the reason {@link ClientSecret} gives: a record's
 * accessors and {@code toString} would print every component.
 */
public final class MintedToken {
  private static final String PREFIX = "lgr_et_";
  // The schema's maxLength, in code points (never UTF-16 units), so every library agrees on a
  // notice near the limit (ADR 31.10.26g D3).
  private static final int MESSAGE_MAX = 280;

  private final AccessToken token;
  private final String expiresAt;
  private final Duration expiresIn;
  private final String subject;
  private final List<String> scopes;
  private final boolean accountLinked;
  private final UpdateAvailable updateAvailable;

  private MintedToken(EmbedToken answer, UpdateAvailable updateAvailable) {
    this.token = new AccessToken(answer.getToken());
    this.expiresAt = answer.getExpiresAt();
    this.expiresIn = Duration.ofSeconds(answer.getExpiresIn());
    this.subject = answer.getSubject();
    this.scopes = List.copyOf(answer.getScopes());
    this.accountLinked = answer.getAccountLinked();
    this.updateAvailable = updateAvailable;
  }

  /**
   * Builds a minted token from a mint's decoded answer. Jackson accepts a missing field and coerces
   * a mistyped one, so each of the six is checked here: present, of its JSON type, and the token
   * starting {@code lgr_et_}. A failure is {@code malformed_response} with no cause, so the body
   * (and the token it may hold) cannot leak through one. The notice is removed from a copy before
   * the typed decode, so a malformed one cannot fail it, and is converted apart (ADR 31.10.26g D3).
   */
  static MintedToken of(JsonNode answer, ObjectMapper mapper) {
    if (!wellFormed(answer)) {
      throw malformed();
    }
    ObjectNode rest = ((ObjectNode) answer).deepCopy();
    UpdateAvailable notice = updateFrom(rest.remove("update_available"));
    try {
      return new MintedToken(mapper.treeToValue(rest, EmbedToken.class), notice);
    } catch (java.io.IOException | IllegalArgumentException e) {
      throw malformed();
    }
  }

  private static boolean wellFormed(JsonNode answer) {
    return answer != null
        && answer.path("token").isTextual()
        && answer.path("token").asText().startsWith(PREFIX)
        && answer.path("expires_at").isTextual()
        && answer.path("expires_in").isIntegralNumber()
        && answer.path("subject").isTextual()
        && answer.path("account_linked").isBoolean()
        && allText(answer.path("scopes"));
  }

  private static boolean allText(JsonNode scopes) {
    if (!scopes.isArray()) {
      return false;
    }
    for (JsonNode scope : scopes) {
      if (!scope.isTextual()) {
        return false;
      }
    }
    return true;
  }

  /**
   * The notice, when it is an object whose {@code deprecated} is a boolean and whose {@code latest}
   * and {@code message} are each present and a string or null, {@code message} at most 280 code
   * points; otherwise null. It reads the raw node by hand, since Jackson coerces a mistyped value;
   * unknown keys are ignored.
   */
  private static UpdateAvailable updateFrom(JsonNode notice) {
    if (notice == null || !notice.isObject()) {
      return null;
    }
    JsonNode latest = notice.get("latest");
    JsonNode message = notice.get("message");
    if (!notice.path("deprecated").isBoolean() || !nullableText(latest) || !nullableText(message)) {
      return null;
    }
    String text = message.isNull() ? null : message.textValue();
    if (text != null && text.codePointCount(0, text.length()) > MESSAGE_MAX) {
      return null;
    }
    return new UpdateAvailable()
        .latest(latest.isNull() ? null : latest.textValue())
        .deprecated(notice.get("deprecated").booleanValue())
        .message(text);
  }

  private static boolean nullableText(JsonNode value) {
    return value != null && (value.isNull() || value.isTextual());
  }

  private static TransportException malformed() {
    return new TransportException(TransportKind.MALFORMED_RESPONSE, null);
  }

  /**
   * Returns the token, which renders as {@code [REDACTED]}; read it with {@code exposeSecret()}.
   *
   * @return the token
   */
  public AccessToken token() {
    return token;
  }

  /**
   * Returns when the token expires, as the server's RFC 3339 string.
   *
   * @return the expiry
   */
  public String expiresAt() {
    return expiresAt;
  }

  /**
   * Returns how long the token lives from this answer (900 seconds). A caller that hands the token
   * to a device whose clock cannot be trusted uses this rather than {@link #expiresAt()}.
   *
   * @return the lifetime
   */
  public Duration expiresIn() {
    return expiresIn;
  }

  /**
   * Returns the player's pairwise {@code lgr_sub_}, stable across mints. Store it beside the
   * player: it is how an event names them.
   *
   * @return the subject
   */
  public String subject() {
    return subject;
  }

  /**
   * Returns the scopes granted, every handable scope the client holds when the request named none.
   *
   * @return the scopes
   */
  public List<String> scopes() {
    return scopes;
  }

  /**
   * Returns whether the player has linked a Lingara account.
   *
   * @return whether the account is linked
   */
  public boolean accountLinked() {
    return accountLinked;
  }

  /**
   * Returns Lingara's notice that the {@code embed_version} the request named is behind, deprecated
   * or withdrawn, copied verbatim. Empty when the request named none, the version is current, or
   * the notice was malformed: a notice is advisory, so a bad one is dropped and never costs the
   * token (ADR 31.10.26g D3).
   *
   * @return the notice, if any
   */
  public Optional<UpdateAvailable> updateAvailable() {
    return Optional.ofNullable(updateAvailable);
  }

  @Override
  public String toString() {
    return "MintedToken{token="
        + token
        + ", expiresAt="
        + expiresAt
        + ", expiresIn="
        + expiresIn
        + ", subject="
        + subject
        + ", scopes="
        + scopes
        + ", accountLinked="
        + accountLinked
        + ", updateAvailable="
        + noticeString()
        + "}";
  }

  private String noticeString() {
    if (updateAvailable == null) {
      return "null";
    }
    return "{latest="
        + updateAvailable.getLatest()
        + ", deprecated="
        + updateAvailable.getDeprecated()
        + ", message="
        + updateAvailable.getMessage()
        + "}";
  }
}
