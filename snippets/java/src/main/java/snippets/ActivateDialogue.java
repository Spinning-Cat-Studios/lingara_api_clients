package snippets;

import com.getlingara.client.LingaraClient;
// lingara:begin activateDialogue
import com.getlingara.client.model.ActivateDialogueRequest;
import com.getlingara.client.model.Dialogue;

// lingara:end

/** The documentation site's activateDialogue example. */
public final class ActivateDialogue {
  private ActivateDialogue() {}

  static void run(LingaraClient client, String dialogueId) {
    // lingara:begin activateDialogue
    // Promote the draft you read: a stale or missing draft is 409 dialogue_conflict.
    Dialogue current = client.dialogues().getDialogue(dialogueId).body();
    if (current.getDraft() != null) {
      ActivateDialogueRequest request =
          new ActivateDialogueRequest().draftVersion(current.getDraft().getDraftVersion());
      Dialogue activated = client.dialogues().activateDialogue(dialogueId, request).body();
      System.out.println("active revision " + activated.getActive().getRevision());
    }
    // lingara:end
  }
}
