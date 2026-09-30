package snippets;

import com.getlingara.client.EventStream;
import com.getlingara.client.LingaraClient;
// lingara:begin sendTutorMessage
import com.getlingara.client.model.SendTutorMessageEvent;
import com.getlingara.client.model.TutorTurnRequest;

// lingara:end

/** The documentation site's sendTutorMessage example. */
public final class SendTutorMessage {
  private SendTutorMessage() {}

  static void run(LingaraClient client) {
    // lingara:begin sendTutorMessage
    TutorTurnRequest turn =
        new TutorTurnRequest().message("荔枝多少钱？").sourceLang("en").targetLang("zh").level(2);
    try (EventStream<SendTutorMessageEvent> stream = client.sendTutorMessage(turn)) {
      for (SendTutorMessageEvent event : stream) {
        if (event instanceof SendTutorMessageEvent.Delta delta) {
          System.out.print(delta.data().getText());
        } else if (event instanceof SendTutorMessageEvent.Notice notice) {
          System.out.println("\n(" + notice.data().getMessage() + ")");
        }
      }
    }
    // lingara:end
  }
}
