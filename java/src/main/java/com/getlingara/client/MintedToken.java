package com.getlingara.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getlingara.client.model.EmbedToken;
import java.time.Duration;
import java.util.List;

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

  private final AccessToken token;
  private final String expiresAt;
  private final Duration expiresIn;
  private final String subject;
  private final List<String> scopes;
  private final boolean accountLinked;

  private MintedToken(EmbedToken answer) {
    this.token = new AccessToken(answer.getToken());
    this.expiresAt = answer.getExpiresAt();
    this.expiresIn = Duration.ofSeconds(answer.getExpiresIn());
    this.subject = answer.getSubject();
    this.scopes = List.copyOf(answer.getScopes());
    this.accountLinked = answer.getAccountLinked();
  }

  /**
   * Builds a minted token from a mint's decoded answer. Jackson accepts a missing field and coerces
   * a mistyped one, so each of the six is checked here: present, of its JSON type, and the token
   * starting {@code lgr_et_}. A failure is {@code malformed_response} with no cause, so the body
   * (and the token it may hold) cannot leak through one.
   */
  static MintedToken of(JsonNode answer, ObjectMapper mapper) {
    if (!wellFormed(answer)) {
      throw malformed();
    }
    try {
      return new MintedToken(mapper.treeToValue(answer, EmbedToken.class));
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
        + "}";
  }
}
