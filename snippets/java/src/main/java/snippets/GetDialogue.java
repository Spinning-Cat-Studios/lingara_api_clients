package snippets;

import com.getlingara.client.LingaraClient;
// lingara:begin getDialogue
import com.getlingara.client.model.Dialogue;

// lingara:end

/** The documentation site's getDialogue example. */
public final class GetDialogue {
  private GetDialogue() {}

  static void run(LingaraClient client, String dialogueId) {
    // lingara:begin getDialogue
    Dialogue dialogue = client.dialogues().getDialogue(dialogueId).body();
    // The draft and the active revision, inline; either may be null.
    if (dialogue.getDraft() != null) {
      System.out.println("draft " + dialogue.getDraft().getDraftVersion());
    }
    if (dialogue.getActive() != null) {
      System.out.println(
          "active revision "
              + dialogue.getActive().getRevision()
              + " since "
              + dialogue.getActive().getActivatedAt());
    }
    // lingara:end
  }
}
