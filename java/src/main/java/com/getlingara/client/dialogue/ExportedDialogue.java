package com.getlingara.client.dialogue;

/**
 * What {@code exportDialogue} answers (ADR 29.10.26c D6): the {@code dialogue.v1} document,
 * unparsed, or {@link NotModified} on a {@code 304}. Both arms carry the revision and the {@code
 * ETag} the server sent.
 */
public sealed interface ExportedDialogue {
  /**
   * The revision exported.
   *
   * @return its number
   */
  long revision();

  /**
   * The {@code ETag}, exactly as received: send it back as {@code ifNoneMatch}.
   *
   * @return the tag
   */
  String etag();

  /**
   * A {@code 200}: the canonical bytes as text, never parsed by the library, so the text you store
   * is the text {@code etag} names (its SHA-256).
   *
   * @param revision the revision exported
   * @param etag the {@code ETag}
   * @param irJson the document as received
   */
  record Body(long revision, String etag, String irJson) implements ExportedDialogue {}

  /**
   * A {@code 304}: unchanged since the {@code etag} you sent, so keep the copy you hold.
   *
   * @param revision the revision still current
   * @param etag the {@code ETag}
   */
  record NotModified(long revision, String etag) implements ExportedDialogue {}
}
