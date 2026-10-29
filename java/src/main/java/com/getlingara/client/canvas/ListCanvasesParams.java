package com.getlingara.client.canvas;

/**
 * {@code listCanvases}' query: the filters and the page to start from (ADR 16.10.26ak).
 *
 * @param cursor a previous page's {@code next_cursor}, or null for the first page
 * @param filters what to keep; null is {@link CanvasFilters#of()}
 */
public record ListCanvasesParams(String cursor, CanvasFilters filters) {
  /** Null filters are every canvas. */
  public ListCanvasesParams {
    filters = filters == null ? CanvasFilters.of() : filters;
  }

  /**
   * The first page with these filters.
   *
   * @param filters what to keep
   * @return the params
   */
  public static ListCanvasesParams of(CanvasFilters filters) {
    return new ListCanvasesParams(null, filters);
  }
}
