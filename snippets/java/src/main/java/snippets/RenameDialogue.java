package snippets;

import com.getlingara.client.LingaraClient;
// lingara:begin renameDialogue
import com.getlingara.client.model.Dialogue;
import com.getlingara.client.model.RenameDialogueRequest;

// lingara:end

/** The documentation site's renameDialogue example. */
public final class RenameDialogue {
  private RenameDialogue() {}

  static void run(LingaraClient client, String dialogueId) {
    // lingara:begin renameDialogue
    Dialogue renamed =
        client
            .dialogues()
            .renameDialogue(dialogueId, new RenameDialogueRequest().name("Night market keepers"))
            .body();
    System.out.println(renamed.getName());
    // lingara:end
  }
}
