package snippets;

import com.getlingara.client.LingaraClient;
// lingara:begin pageCanvases
import com.getlingara.client.canvas.CanvasFilters;
import com.getlingara.client.model.CanvasSummary;

// lingara:end

/** The documentation site's pageCanvases example. */
public final class PageCanvases {
  private PageCanvases() {}

  static void run(LingaraClient client) {
    // lingara:begin pageCanvases
    // Every canvas made from the lesson-plan recipe, one page fetched at a time.
    for (CanvasSummary canvas :
        client.canvases().pageCanvases(CanvasFilters.of().recipes("lesson_plan_ready"))) {
      System.out.println(canvas.getId() + " " + canvas.getName());
    }
    // lingara:end
  }
}
