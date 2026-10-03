package snippets

// lingara:begin verifyWebhook
import com.getlingara.kotlin.events.LessonPlanReady
import com.getlingara.kotlin.events.UnknownEvent
import com.getlingara.kotlin.events.Webhook
import com.getlingara.kotlin.events.WebhookVerificationException
// lingara:end

/** The documentation site's verifyWebhook example: answers one delivery with an HTTP status. */
fun verifyWebhookSnippet(
    body: ByteArray,
    headers: Map<String, List<String>>,
): Int {
    // lingara:begin verifyWebhook
    // The secret from your webhook endpoint's settings; pass two during a rotation.
    val webhook = Webhook(System.getenv("LINGARA_WEBHOOK_SECRET"))
    // Verify the raw body, exactly as received, before anything parses it: Ktor's
    // call.receive<ByteArray>(), or Spring's @RequestBody body: ByteArray.
    val event =
        try {
            webhook.verify(body, headers)
        } catch (e: WebhookVerificationException) {
            return 400
        }
    // Answer fast, and deduplicate by event.id: delivery is at least once.
    when (event) {
        is LessonPlanReady -> println("plan ready: ${event.data.planId}")
        is UnknownEvent -> println("newer event type: ${event.type} ${event.id}")
        else -> Unit
    }
    return 204
    // lingara:end
}
