package snippets;

import com.getlingara.client.LingaraClient;
// lingara:begin listDialogues
import com.getlingara.client.dialogue.DialogueFilters;
import com.getlingara.client.dialogue.ListDialoguesParams;
import com.getlingara.client.model.DialoguePage;
import com.getlingara.client.model.DialogueSummary;

// lingara:end

/** The documentation site's listDialogues example. */
public final class ListDialogues {
  private ListDialogues() {}

  static String run(LingaraClient client) {
    // lingara:begin listDialogues
    // One page of the dialogues whose name contains "market"; pageDialogues walks every page.
    DialogueFilters filters = DialogueFilters.of().name("market").limit(20);
    DialoguePage page = client.dialogues().listDialogues(ListDialoguesParams.of(filters)).body();
    for (DialogueSummary d : page.getItems()) {
      System.out.println(
          d.getId()
              + " "
              + d.getName()
              + " "
              + d.getIrId()
              + ": "
              + d.getNpcCount()
              + " NPCs, "
              + d.getCheckpointCount()
              + " checkpoints");
    }
    // Pass it back as the cursor for the next page, while has_more is true.
    return page.getNextCursor();
    // lingara:end
  }
}
