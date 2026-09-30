package snippets

// lingara:begin streamLessonPlan
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.model.StreamLessonPlanEvent
// lingara:end

/** The documentation site's streamLessonPlan example. */
suspend fun streamLessonPlanSnippet(
    client: LingaraClient,
    planId: String,
) {
    // lingara:begin streamLessonPlan
    client.streamLessonPlan(planId).use { stream ->
        stream.collect { event ->
            when (event) {
                is StreamLessonPlanEvent.Result -> println(event.data.plan.title)
                // Still generating elsewhere: ask again later.
                is StreamLessonPlanEvent.Pending -> println("pending: ${event.data.status}")
                else -> Unit
            }
        }
    }
    // lingara:end
}
