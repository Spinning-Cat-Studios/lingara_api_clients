package com.getlingara.client.dialogue;

/**
 * What {@code listDialogues} filters by, and what {@code pageDialogues} resends on every page (ADR
 * 29.10.26c D5). Start from {@link #of()}.
 *
 * @param limit the page size, or null for the server's default
 * @param name a substring of the dialogue name, or null
 */
public record DialogueFilters(Integer limit, String name) {
  /**
   * Filters with every field unset: every dialogue, the server's page size.
   *
   * @return the filters
   */
  public static DialogueFilters of() {
    return new DialogueFilters(null, null);
  }

  /**
   * Sets the page size.
   *
   * @param limit the page size
   * @return a copy with {@code limit} set
   */
  public DialogueFilters limit(int limit) {
    return new DialogueFilters(limit, name);
  }

  /**
   * Keeps dialogues whose name contains {@code name}.
   *
   * @param name the substring
   * @return a copy with {@code name} set
   */
  public DialogueFilters name(String name) {
    return new DialogueFilters(limit, name);
  }
}
