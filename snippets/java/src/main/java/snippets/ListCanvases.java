package snippets;

import com.getlingara.client.LingaraClient;
// lingara:begin listCanvases
import com.getlingara.client.canvas.CanvasFilters;
import com.getlingara.client.canvas.ListCanvasesParams;
import com.getlingara.client.model.CanvasPage;
import com.getlingara.client.model.CanvasSummary;

// lingara:end

/** The documentation site's listCanvases example. */
public final class ListCanvases {
  private ListCanvases() {}

  static String run(LingaraClient client) {
    // lingara:begin listCanvases
    // One page of your live canvases that use a webhook; pageCanvases walks every page.
    CanvasFilters filters = CanvasFilters.of().status("live").kinds("webhook").limit(20);
    CanvasPage page = client.canvases().listCanvases(ListCanvasesParams.of(filters)).body();
    for (CanvasSummary canvas : page.getItems()) {
      System.out.println(canvas.getName() + " (" + canvas.getRecipe() + ")");
    }
    // Pass it back as the cursor for the next page, while has_more is true.
    return page.getNextCursor();
    // lingara:end
  }
}
