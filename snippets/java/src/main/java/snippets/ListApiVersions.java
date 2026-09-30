package snippets;

import com.getlingara.client.LingaraClient;
// lingara:begin listApiVersions
import com.getlingara.client.model.VersionSummary;

// lingara:end

/** The documentation site's listApiVersions example. */
public final class ListApiVersions {
  private ListApiVersions() {}

  static void run() {
    // This operation needs no token, so a client with no credentials is enough.
    LingaraClient client = LingaraClient.builder().build();
    // lingara:begin listApiVersions
    for (VersionSummary version : client.listApiVersions().body().getVersions()) {
      System.out.println(version.getId() + " " + version.getState() + " " + version.getLts());
    }
    // lingara:end
  }
}
