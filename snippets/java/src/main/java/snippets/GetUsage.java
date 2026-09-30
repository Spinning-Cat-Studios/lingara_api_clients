package snippets;

import com.getlingara.client.LingaraClient;
// lingara:begin getUsage
import com.getlingara.client.model.AllowanceRow;
import com.getlingara.client.model.Usage;

// lingara:end

/** The documentation site's getUsage example. */
public final class GetUsage {
  private GetUsage() {}

  static void run(LingaraClient client) {
    // lingara:begin getUsage
    Usage usage = client.getUsage().body();
    for (AllowanceRow row : usage.getAllowance()) {
      System.out.println(
          row.getFeature()
              + " ("
              + row.getWindow()
              + "): "
              + row.getRemaining()
              + " of "
              + row.getLimit()
              + " left");
    }
    // lingara:end
  }
}
