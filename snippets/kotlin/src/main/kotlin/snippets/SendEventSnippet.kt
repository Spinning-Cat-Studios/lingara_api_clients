package snippets

// lingara:begin sendEvent
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.events.InboundEvent
import com.getlingara.kotlin.model.PlanStatus
import com.getlingara.kotlin.model.WorldContextChanged
// lingara:end

/** The documentation site's sendEvent example. */
suspend fun sendEventSnippet(
    client: LingaraClient,
    saveSlot: String,
) {
    // lingara:begin sendEvent
    val scene =
        WorldContextChanged(
            scene = "A night market after rain",
            sourceLang = "en",
            targetLang = "zh",
            level = 2,
            generate = true,
        )
    // Your own key, so a resend after a crash gets the first answer instead of a second event.
    val accepted = client.sendEvent(InboundEvent.WorldContextChanged(scene), idempotencyKey = "$saveSlot/night-market").body
    // Only a plan still generating promises a lesson_plan.ready or .failed event.
    val reaction = accepted.reaction
    if (reaction?.planStatus == PlanStatus.GENERATING) println("watch the feed for plan ${reaction.planId}")
    // lingara:end
}
