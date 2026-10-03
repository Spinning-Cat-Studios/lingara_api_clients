package snippets

// lingara:begin streamEvents
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.events.LessonPlanFailed
import com.getlingara.kotlin.events.LessonPlanReady
import com.getlingara.kotlin.events.tailEvents
// lingara:end

/** The documentation site's streamEvents example. */
suspend fun streamEventsSnippet(
    client: LingaraClient,
    savedCursor: String?,
) {
    // lingara:begin streamEvents
    // Live events: the tail reconnects from its cursor after every ending, and throws only after
    // 8 failed reopens in a row. Cancel the collecting coroutine to stop it.
    val tail = client.tailEvents(cursor = savedCursor, types = listOf("lesson_plan.ready", "lesson_plan.failed"))
    tail.collect { event ->
        when (event) {
            is LessonPlanReady -> println("ready: ${event.data.planId}")
            is LessonPlanFailed -> println("failed: ${event.data.planId}")
            else -> Unit
        }
        // Save tail.cursor to resume after a restart.
    }
    // lingara:end
}
