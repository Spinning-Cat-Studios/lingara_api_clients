package com.getlingara.client.canvas;

import java.util.List;

/**
 * What {@code listCanvases} filters by, and what {@code pageCanvases} resends on every page (ADR
 * 16.10.26ak D5). Start from {@link #of()}; each list is sent comma-separated as one value, and an
 * empty one is left out.
 *
 * @param limit the page size, 1 to 50, or null for the server's default
 * @param name a substring of the canvas name, or null
 * @param status {@code all}, {@code live}, {@code changed} or {@code unapplied}, or null
 * @param recipes only these recipes; empty means every recipe
 * @param kinds only canvases using these node kinds; empty means every kind
 * @param scopes only canvases using these scopes; empty means every scope
 */
public record CanvasFilters(
    Integer limit,
    String name,
    String status,
    List<String> recipes,
    List<String> kinds,
    List<String> scopes) {
  /** Copies the lists; null is empty. */
  public CanvasFilters {
    recipes = recipes == null ? List.of() : List.copyOf(recipes);
    kinds = kinds == null ? List.of() : List.copyOf(kinds);
    scopes = scopes == null ? List.of() : List.copyOf(scopes);
  }

  /**
   * Filters with every field unset: every canvas, the server's page size.
   *
   * @return the filters
   */
  public static CanvasFilters of() {
    return new CanvasFilters(null, null, null, List.of(), List.of(), List.of());
  }

  /**
   * Sets the page size.
   *
   * @param limit 1 to 50
   * @return a copy with {@code limit} set
   */
  public CanvasFilters limit(int limit) {
    return new CanvasFilters(limit, name, status, recipes, kinds, scopes);
  }

  /**
   * Keeps canvases whose name contains {@code name}.
   *
   * @param name the substring
   * @return a copy with {@code name} set
   */
  public CanvasFilters name(String name) {
    return new CanvasFilters(limit, name, status, recipes, kinds, scopes);
  }

  /**
   * Keeps canvases in one state.
   *
   * @param status {@code all}, {@code live}, {@code changed} or {@code unapplied}
   * @return a copy with {@code status} set
   */
  public CanvasFilters status(String status) {
    return new CanvasFilters(limit, name, status, recipes, kinds, scopes);
  }

  /**
   * Keeps canvases made from these recipes.
   *
   * @param recipes the recipes
   * @return a copy with {@code recipes} set
   */
  public CanvasFilters recipes(String... recipes) {
    return new CanvasFilters(limit, name, status, List.of(recipes), kinds, scopes);
  }

  /**
   * Keeps canvases using these node kinds.
   *
   * @param kinds the kinds
   * @return a copy with {@code kinds} set
   */
  public CanvasFilters kinds(String... kinds) {
    return new CanvasFilters(limit, name, status, recipes, List.of(kinds), scopes);
  }

  /**
   * Keeps canvases using these scopes.
   *
   * @param scopes the scopes
   * @return a copy with {@code scopes} set
   */
  public CanvasFilters scopes(String... scopes) {
    return new CanvasFilters(limit, name, status, recipes, kinds, List.of(scopes));
  }
}
