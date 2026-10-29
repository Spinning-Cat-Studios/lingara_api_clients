package snippets;

import com.getlingara.client.LingaraClient;
// lingara:begin exportDialogue
import com.getlingara.client.dialogue.ExportDialogueOptions;
import com.getlingara.client.dialogue.ExportedDialogue;

// lingara:end

/** The documentation site's exportDialogue example. */
public final class ExportDialogue {
  private ExportDialogue() {}

  /** The copy you hold: its ETag and its bytes, exactly as received. */
  record Cached(String etag, String irJson) {}

  static Cached run(LingaraClient client, String dialogueId, Cached cached) {
    // lingara:begin exportDialogue
    // Cache by ETag: the body is canonical bytes, so store it exactly as received. A 304 still
    // spends one unit of the rate budget.
    ExportDialogueOptions options =
        ExportDialogueOptions.of().ifNoneMatch(cached == null ? null : cached.etag());
    ExportedDialogue exported = client.dialogues().exportDialogue(dialogueId, options).body();
    if (exported instanceof ExportedDialogue.Body body) {
      cached = new Cached(body.etag(), body.irJson());
      System.out.println("revision " + body.revision() + " is new");
    } else {
      System.out.println("revision " + exported.revision() + " is unchanged");
    }
    // lingara:end
    return cached;
  }
}
