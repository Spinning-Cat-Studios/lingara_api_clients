package snippets;

// lingara:begin getOpenApiDocument
import com.fasterxml.jackson.databind.JsonNode;
import com.getlingara.client.LingaraClient;

// lingara:end

/** The documentation site's getOpenApiDocument example. */
public final class GetOpenApiDocument {
  private GetOpenApiDocument() {}

  static void run() {
    // This operation needs no token, so a client with no credentials is enough.
    LingaraClient client = LingaraClient.builder().build();
    // lingara:begin getOpenApiDocument
    JsonNode document = client.getOpenApiDocument().body();
    System.out.println(document.path("info").path("version").asText());
    // lingara:end
  }
}
