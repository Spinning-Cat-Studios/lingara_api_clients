package snippets;

import com.getlingara.client.LingaraClient;
// lingara:begin getApiVersion
import com.getlingara.client.model.VersionDetail;

// lingara:end

/** The documentation site's getApiVersion example. */
public final class GetApiVersion {
  private GetApiVersion() {}

  static void run() {
    // This operation needs no token, so a client with no credentials is enough.
    LingaraClient client = LingaraClient.builder().build();
    // lingara:begin getApiVersion
    VersionDetail version = client.getApiVersion("2026-09-knowing-tenpounder").body();
    System.out.println(version.getId() + " " + version.getState());
    // lingara:end
  }
}
