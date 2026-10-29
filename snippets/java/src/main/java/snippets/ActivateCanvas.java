package snippets;

import com.getlingara.client.LingaraClient;
// lingara:begin activateCanvas
import com.getlingara.client.model.ActivateCanvasRequest;
import com.getlingara.client.model.CanvasActivationOutcome;
import com.getlingara.client.model.CanvasObjectRef;
import java.util.Map;

// lingara:end

/** The documentation site's activateCanvas example. */
public final class ActivateCanvas {
  private ActivateCanvas() {}

  static void run(LingaraClient client, String canvasId, String madeClientId) {
    // lingara:begin activateCanvas
    // After your apply made the objects, promote the draft with what each node became. A repeat at
    // the same draft_version answers 409 canvas_conflict and writes nothing.
    ActivateCanvasRequest request =
        new ActivateCanvasRequest()
            .draftVersion(2)
            .outcome(CanvasActivationOutcome.COMPLETE)
            .objects(Map.of("n1", new CanvasObjectRef().id(madeClientId)));
    client.canvases().activateCanvas(canvasId, request);
    // lingara:end
  }
}
