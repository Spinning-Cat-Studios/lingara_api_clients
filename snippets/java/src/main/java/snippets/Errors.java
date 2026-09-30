package snippets;

import com.getlingara.client.ApiException;
import com.getlingara.client.LingaraClient;
import com.getlingara.client.LingaraException;
import com.getlingara.client.MaintenanceException;
import com.getlingara.client.OAuthException;
import com.getlingara.client.TransportException;

/** The documentation site's error-handling example. */
public final class Errors {
  private Errors() {}

  static void run(LingaraClient client) {
    // lingara:begin errors
    try {
      client.getUsage();
    } catch (LingaraException e) {
      if (e instanceof ApiException api) {
        // A refusal from the API: status(), code() (stable) and getMessage() (localised).
        System.out.println(api.status() + " " + api.code() + " " + api.getMessage());
        api.retryAfter().ifPresent(wait -> System.out.println("retry after " + wait));
      } else if (e instanceof OAuthException oauth) {
        // The token endpoint refused the credentials or the scopes.
        System.out.println(oauth.status() + " " + oauth.error() + " " + oauth.description());
      } else if (e instanceof MaintenanceException maintenance) {
        System.out.println("under maintenance");
        maintenance.retryAfter().ifPresent(wait -> System.out.println("retry after " + wait));
      } else if (e instanceof TransportException transport) {
        // No usable answer: CONNECT, TLS, RESET, TIMEOUT, and so on.
        System.out.println("transport: " + transport.kind());
      }
    }
    // An interrupted call throws java.util.concurrent.CancellationException instead.
    // lingara:end
  }
}
