package snippets;

import com.getlingara.client.LingaraClient;
// lingara:begin getCanvas
import com.getlingara.client.model.Canvas;

// lingara:end

/** The documentation site's getCanvas example. */
public final class GetCanvas {
  private GetCanvas() {}

  static void run(LingaraClient client, String canvasId) {
    // lingara:begin getCanvas
    Canvas canvas = client.canvases().getCanvas(canvasId).body();
    // Either revision may be null: no draft since the last activate, or never activated.
    if (canvas.getDraft() != null) {
      System.out.println("draft at version " + canvas.getDraft().getDraftVersion());
    }
    if (canvas.getActive() != null) {
      System.out.println("live revision " + canvas.getActive().getRevision());
    }
    // lingara:end
  }
}
