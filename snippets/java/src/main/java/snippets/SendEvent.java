package snippets;

import com.getlingara.client.LingaraClient;
// lingara:begin sendEvent
import com.getlingara.client.events.InboundEvent;
import com.getlingara.client.events.SendEventOptions;
import com.getlingara.client.model.InboundEventAccepted;
import com.getlingara.client.model.PlanStatus;
import com.getlingara.client.model.WorldContextChanged;

// lingara:end

/** The documentation site's sendEvent example. */
public final class SendEvent {
  private SendEvent() {}

  static void run(LingaraClient client, String saveSlot) {
    // lingara:begin sendEvent
    WorldContextChanged scene =
        new WorldContextChanged()
            .scene("A night market after rain")
            .sourceLang("en")
            .targetLang("zh")
            .level(2)
            .generate(true);
    // Your own key, so a resend after a crash gets the first answer instead of a second event.
    InboundEventAccepted accepted =
        client
            .sendEvent(
                InboundEvent.worldContextChanged(scene),
                new SendEventOptions(saveSlot + "/night-market"))
            .body();
    // Only a plan still generating promises a lesson_plan.ready or .failed event.
    if (accepted.getReaction() != null
        && accepted.getReaction().getPlanStatus() == PlanStatus.GENERATING) {
      System.out.println("watch the feed for plan " + accepted.getReaction().getPlanId());
    }
    // lingara:end
  }
}
