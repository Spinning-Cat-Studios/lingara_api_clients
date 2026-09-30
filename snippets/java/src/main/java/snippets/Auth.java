package snippets;

// lingara:begin auth
import com.getlingara.client.LingaraClient;

// lingara:end

/** The documentation site's authentication example. */
public final class Auth {
  private Auth() {}

  static LingaraClient newClient() {
    // lingara:begin auth
    LingaraClient client =
        LingaraClient.builder()
            .clientCredentials(
                System.getenv("LINGARA_CLIENT_ID"), System.getenv("LINGARA_CLIENT_SECRET"))
            .build();
    // lingara:end
    return client;
  }
}
