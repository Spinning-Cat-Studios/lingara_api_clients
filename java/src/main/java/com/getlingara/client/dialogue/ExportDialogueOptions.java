package com.getlingara.client.dialogue;

/**
 * What {@code exportDialogue} sends beyond the id (ADR 29.10.26c D6). Each is sent only when set,
 * so {@link #of()} is the active revision with its layout and no {@code If-None-Match}.
 *
 * @param revision {@code active} (the server's default) or {@code draft}, or null
 * @param layout {@code keep} (the server's default) or {@code omit}, which drops every {@code x-}
 *     member and has its own {@code ETag}, or null
 * @param ifNoneMatch the {@code etag} of the copy you hold, sent back exactly as received, or null
 */
public record ExportDialogueOptions(String revision, String layout, String ifNoneMatch) {
  /**
   * Options with every field unset.
   *
   * @return the options
   */
  public static ExportDialogueOptions of() {
    return new ExportDialogueOptions(null, null, null);
  }

  /**
   * Chooses the revision.
   *
   * @param revision {@code active} or {@code draft}
   * @return a copy with {@code revision} set
   */
  public ExportDialogueOptions revision(String revision) {
    return new ExportDialogueOptions(revision, layout, ifNoneMatch);
  }

  /**
   * Chooses the layout.
   *
   * @param layout {@code keep} or {@code omit}
   * @return a copy with {@code layout} set
   */
  public ExportDialogueOptions layout(String layout) {
    return new ExportDialogueOptions(revision, layout, ifNoneMatch);
  }

  /**
   * Revalidates the copy you hold.
   *
   * @param ifNoneMatch its {@code etag}, unchanged
   * @return a copy with {@code ifNoneMatch} set
   */
  public ExportDialogueOptions ifNoneMatch(String ifNoneMatch) {
    return new ExportDialogueOptions(revision, layout, ifNoneMatch);
  }
}
