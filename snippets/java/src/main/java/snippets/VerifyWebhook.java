package snippets;

// lingara:begin verifyWebhook
import com.getlingara.client.events.Event;
import com.getlingara.client.events.LessonPlanReady;
import com.getlingara.client.events.UnknownEvent;
import com.getlingara.client.events.Webhook;
import com.getlingara.client.events.WebhookVerificationException;
import java.util.List;
import java.util.Map;

// lingara:end

/** The documentation site's verifyWebhook example. */
public final class VerifyWebhook {
  private VerifyWebhook() {}

  /** Answers one delivery: the HTTP status to reply with. */
  static int handle(byte[] body, Map<String, List<String>> headers) {
    // lingara:begin verifyWebhook
    // The secret from your webhook endpoint's settings; pass two during a rotation.
    Webhook webhook = Webhook.of(System.getenv("LINGARA_WEBHOOK_SECRET"));
    // Verify the raw body, exactly as received, before anything parses it: a servlet's
    // request.getInputStream().readAllBytes(), or Spring's @RequestBody byte[] body.
    Event event;
    try {
      event = webhook.verify(body, headers);
    } catch (WebhookVerificationException e) {
      return 400;
    }
    // Answer fast, and deduplicate by event.id(): delivery is at least once.
    if (event instanceof LessonPlanReady ready) {
      System.out.println("plan ready: " + ready.data().getPlanId());
    } else if (event instanceof UnknownEvent unknown) {
      System.out.println("newer event type: " + unknown.type() + " " + unknown.id());
    }
    return 204;
    // lingara:end
  }
}
