package snippets;

import com.getlingara.client.LingaraClient;
// lingara:begin createCanvas
import com.getlingara.client.model.Canvas;
import com.getlingara.client.model.CanvasDefinition;
import com.getlingara.client.model.CanvasEdge;
import com.getlingara.client.model.CanvasNode;
import com.getlingara.client.model.CanvasNodeKind;
import com.getlingara.client.model.CanvasRecipe;
import com.getlingara.client.model.CreateCanvasRequest;
import java.util.List;

// lingara:end

/** The documentation site's createCanvas example. */
public final class CreateCanvas {
  private CreateCanvas() {}

  static String run(LingaraClient client) {
    // lingara:begin createCanvas
    // A client node wired to a webhook node: tell my server when a lesson plan is ready.
    CanvasDefinition definition =
        new CanvasDefinition()
            .schema(2)
            .nodes(
                List.of(
                    new CanvasNode()
                        .id("n1")
                        .kind(CanvasNodeKind.CLIENT)
                        .position(List.of(0.0, 0.0)),
                    new CanvasNode()
                        .id("n2")
                        .kind(CanvasNodeKind.WEBHOOK)
                        .position(List.of(1.0, 0.0))))
            .edges(List.of(new CanvasEdge().from("n1").to("n2")));
    CreateCanvasRequest request =
        new CreateCanvasRequest()
            .name("Tell my server when a lesson plan is ready")
            .recipe(CanvasRecipe.LESSON_PLAN_READY)
            .definition(definition);
    Canvas canvas = client.canvases().createCanvas(request).body();
    // Keep the draft's version: the next saveCanvasDraft sends it back.
    System.out.println(canvas.getId() + " at draft " + canvas.getDraft().getDraftVersion());
    return canvas.getId();
    // lingara:end
  }
}
