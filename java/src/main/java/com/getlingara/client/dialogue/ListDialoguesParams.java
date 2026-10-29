package com.getlingara.client.dialogue;

/**
 * {@code listDialogues}' query: the filters and the page to start from (ADR 29.10.26c).
 *
 * @param cursor a previous page's {@code next_cursor}, or null for the first page
 * @param filters what to keep; null is {@link DialogueFilters#of()}
 */
public record ListDialoguesParams(String cursor, DialogueFilters filters) {
  /** Null filters are every dialogue. */
  public ListDialoguesParams {
    filters = filters == null ? DialogueFilters.of() : filters;
  }

  /**
   * The first page with these filters.
   *
   * @param filters what to keep
   * @return the params
   */
  public static ListDialoguesParams of(DialogueFilters filters) {
    return new ListDialoguesParams(null, filters);
  }
}
