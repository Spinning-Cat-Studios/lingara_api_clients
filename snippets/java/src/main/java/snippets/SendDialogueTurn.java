package snippets;

import com.getlingara.client.EventStream;
import com.getlingara.client.LingaraClient;
// lingara:begin sendDialogueTurn
import com.getlingara.client.model.DialogueEntry;
import com.getlingara.client.model.DialogueTurnRequest;
import com.getlingara.client.model.Npc;
import com.getlingara.client.model.SendDialogueTurnEvent;
import com.getlingara.client.model.Speaker;
import java.util.List;

// lingara:end

/** The documentation site's sendDialogueTurn example. */
public final class SendDialogueTurn {
  private SendDialogueTurn() {}

  static void run(LingaraClient client, List<DialogueEntry> history) {
    // lingara:begin sendDialogueTurn
    String line = "饺子多少钱？";
    DialogueTurnRequest turn =
        new DialogueTurnRequest()
            .npc(new Npc().name("Auntie Lin").persona("a street-food vendor who likes to haggle"))
            .sourceLang("en")
            .targetLang("zh")
            .level(3)
            .line(line)
            .history(history); // at most 12 entries, each at most 500 characters
    StringBuilder reply = new StringBuilder();
    // Sent once, never retried: each turn is billed, so a 429 is yours to answer.
    try (EventStream<SendDialogueTurnEvent> stream = client.sendDialogueTurn(turn)) {
      for (SendDialogueTurnEvent event : stream) {
        if (event instanceof SendDialogueTurnEvent.Delta delta) {
          reply.append(delta.data().getText());
        }
      }
    }
    // The window is yours to keep: the reply goes back cut to its first 500 characters.
    int cut = reply.offsetByCodePoints(0, Math.min(500, reply.codePointCount(0, reply.length())));
    String npc = reply.substring(0, cut);
    history.add(new DialogueEntry().speaker(Speaker.PLAYER).text(line));
    history.add(new DialogueEntry().speaker(Speaker.NPC).text(npc));
    // lingara:end
  }
}
