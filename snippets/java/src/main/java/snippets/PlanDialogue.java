package snippets;

import com.getlingara.client.LingaraClient;
// lingara:begin planDialogue
import com.getlingara.client.DialoguePlanResponse;
import com.getlingara.client.model.Beat;
import com.getlingara.client.model.DialoguePlanRequest;
import com.getlingara.client.model.PlanReason;
import java.util.Map;

// lingara:end

/** The documentation site's planDialogue example. */
public final class PlanDialogue {
  private PlanDialogue() {}

  static void run(LingaraClient client, Map<String, Object> document) {
    // lingara:begin planDialogue
    // The first plan of a Procedural dialogue. Inside the embed kits the kit calls this for you.
    DialoguePlanRequest request =
        new DialoguePlanRequest()
            .document(document) // your dialogue.v2 Procedural document, sent as given
            .npc("ma_ren")
            .reason(PlanReason.START)
            .idPrefix("p1")
            .turns(0)
            .beats(0);
    // Sent once, never retried: each call spends one embed:play unit, a keep too.
    DialoguePlanResponse answer = client.planDialogue(request).body();
    if ("planned".equals(answer.status())) {
      for (Beat beat : answer.fragment().getBeats()) {
        System.out.println(beat.getId() + " " + beat.getAtom());
      }
    } else {
      // A keep is a result: your frontier stands (on a first plan, walk the seed).
      System.out.println("kept: " + answer.reason());
    }
    // lingara:end
  }
}
