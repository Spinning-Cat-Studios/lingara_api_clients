package snippets;

// lingara:begin saveCanvasDraft
import com.getlingara.client.ApiException;
import com.getlingara.client.LingaraClient;
import com.getlingara.client.model.Canvas;
import com.getlingara.client.model.CanvasDefinition;
import com.getlingara.client.model.SaveCanvasDraftRequest;

// lingara:end

/** The documentation site's saveCanvasDraft example. */
public final class SaveCanvasDraft {
  private SaveCanvasDraft() {}

  static void run(LingaraClient client, String canvasId, CanvasDefinition edited, int readAt) {
    // lingara:begin saveCanvasDraft
    // Send back the draft_version you read the draft at.
    SaveCanvasDraftRequest save =
        new SaveCanvasDraftRequest().definition(edited).draftVersion(readAt);
    try {
      client.canvases().saveCanvasDraft(canvasId, save);
    } catch (ApiException e) {
      if (!"canvas_conflict".equals(e.code())) {
        throw e;
      }
      // Someone saved since you read it, or your own save landed before a retry. The library never
      // merges or re-sends: re-read, compare, and decide.
      Canvas latest = client.canvases().getCanvas(canvasId).body();
      if (!edited.equals(latest.getDraft().getDefinition())) {
        System.out.println("The draft changed; ask which one to keep.");
      }
    }
    // lingara:end
  }
}
