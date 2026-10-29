package snippets;

import com.getlingara.client.LingaraClient;
// lingara:begin pageDialogues
import com.getlingara.client.dialogue.DialogueFilters;
import com.getlingara.client.model.DialogueSummary;

// lingara:end

/** The documentation site's pageDialogues example. */
public final class PageDialogues {
  private PageDialogues() {}

  static void run(LingaraClient client) {
    // lingara:begin pageDialogues
    // Every dialogue the filters match: the helper follows next_cursor and resends the filters on
    // every page, one page fetched at a time. It never sleeps or polls.
    DialogueFilters filters = DialogueFilters.of().name("market");
    for (DialogueSummary d : client.dialogues().pageDialogues(filters)) {
      Object active = d.getActiveRevision() == null ? "never activated" : d.getActiveRevision();
      System.out.println(d.getId() + " " + d.getName() + " " + active);
    }
    // lingara:end
  }
}
