package snippets;

// lingara:begin saveDialogueDraft
import com.getlingara.client.ApiException;
import com.getlingara.client.LingaraClient;
import com.getlingara.client.model.Dialogue;
import com.getlingara.client.model.SaveDialogueDraftRequest;

// lingara:end

/** The documentation site's saveDialogueDraft example. */
public final class SaveDialogueDraft {
  private SaveDialogueDraft() {}

  static void run(LingaraClient client, String dialogueId, Object definition, int readAt) {
    // lingara:begin saveDialogueDraft
    // Send back the draft_version you read the draft at.
    SaveDialogueDraftRequest save =
        new SaveDialogueDraftRequest().definition(definition).draftVersion(readAt);
    try {
      Dialogue saved = client.dialogues().saveDialogueDraft(dialogueId, save).body();
      System.out.println("saved; the next save sends " + saved.getDraft().getDraftVersion());
    } catch (ApiException e) {
      if (!"dialogue_conflict".equals(e.code())) {
        throw e;
      }
      // The draft moved since you read it, perhaps by your own retried save. It is never retried
      // for you: re-read, compare, and decide. With no draft at all, send draft_version 0 to open
      // one.
      Dialogue current = client.dialogues().getDialogue(dialogueId).body();
      int now = current.getDraft() == null ? 0 : current.getDraft().getDraftVersion();
      System.out.println("changed elsewhere; the draft is now at " + now);
    }
    // lingara:end
  }
}
