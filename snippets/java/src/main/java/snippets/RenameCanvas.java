package snippets;

import com.getlingara.client.LingaraClient;
// lingara:begin renameCanvas
import com.getlingara.client.model.RenameCanvasRequest;

// lingara:end

/** The documentation site's renameCanvas example. */
public final class RenameCanvas {
  private RenameCanvas() {}

  static void run(LingaraClient client, String canvasId) {
    // lingara:begin renameCanvas
    client.canvases().renameCanvas(canvasId, new RenameCanvasRequest().name("Lesson plan webhook"));
    // lingara:end
  }
}
