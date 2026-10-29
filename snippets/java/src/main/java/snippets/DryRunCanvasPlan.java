package snippets;

import com.getlingara.client.LingaraClient;
// lingara:begin dryRunCanvasPlan
import com.getlingara.client.model.CanvasDryRun;
import com.getlingara.client.model.CanvasPlan;
import com.getlingara.client.model.CanvasVerdict;
import com.getlingara.client.model.CanvasVerdictKind;
import java.util.List;
import java.util.Map;

// lingara:end

/** The documentation site's dryRunCanvasPlan example. */
public final class DryRunCanvasPlan {
  private DryRunCanvasPlan() {}

  static void run(LingaraClient client) {
    // lingara:begin dryRunCanvasPlan
    Map<String, Object> createClient =
        Map.of(
            "handle", "c1",
            "kind", "client",
            "op", "create",
            "body", Map.of("name", "Lesson server", "scopes", List.of("events:read")));
    CanvasDryRun dryRun =
        client.canvases().dryRunCanvasPlan(new CanvasPlan().ops(List.of(createClient))).body();
    // A refused op is an answer, not an error: branch on its code; its English error is a fallback.
    for (CanvasVerdict verdict : dryRun.getVerdicts()) {
      if (verdict.getVerdict() == CanvasVerdictKind.REFUSED) {
        System.out.println(verdict.getHandle() + ": " + verdict.getCode());
      }
    }
    // lingara:end
  }
}
