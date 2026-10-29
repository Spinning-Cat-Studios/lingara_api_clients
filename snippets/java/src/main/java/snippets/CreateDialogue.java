package snippets;

// lingara:begin createDialogue
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getlingara.client.LingaraClient;
import com.getlingara.client.model.CreateDialogueRequest;
import com.getlingara.client.model.Dialogue;
import com.getlingara.client.model.DialogueWarning;
import java.io.IOException;

// lingara:end

/** The documentation site's createDialogue example. */
public final class CreateDialogue {
  private CreateDialogue() {}

  static String run(LingaraClient client) throws IOException {
    // lingara:begin createDialogue
    // The definition is a dialogue.v1 document, any JSON value; the server validates it on every
    // save. Warnings are advice, never a refusal.
    JsonNode definition =
        new ObjectMapper()
            .readTree(
                """
                {"schema": "dialogue.v1", "id": "night_market_keepers", "start": "greet",
                 "npcs": [{"id": "old_wei", "name": "Old Wei"}],
                 "checkpoints": [{"id": "greet", "goal": "Greet the stranger", "may_disclose": [],
                   "edges": [{"id": "thanked", "to": "success",
                              "when": "the player says thank you"}]}],
                 "facts": []}
                """);
    CreateDialogueRequest request =
        new CreateDialogueRequest().name("Night market").definition(definition);
    Dialogue created = client.dialogues().createDialogue(request).body();
    System.out.println(created.getId() + " at draft " + created.getDraft().getDraftVersion());
    for (DialogueWarning w : created.getWarnings()) {
      System.out.println("warning " + w.getCode() + " at " + w.getPath());
    }
    return created.getId();
    // lingara:end
  }
}
