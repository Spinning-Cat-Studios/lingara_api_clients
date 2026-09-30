package snippets

// lingara:begin createLessonPlan
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.model.CreateLessonPlanEvent
import com.getlingara.kotlin.model.LessonPlanCreateRequest
// lingara:end

/** The documentation site's createLessonPlan example. */
suspend fun createLessonPlanSnippet(client: LingaraClient) {
    // lingara:begin createLessonPlan
    val request =
        LessonPlanCreateRequest(context = "Ordering at a night market", sourceLang = "en", targetLang = "zh", level = 2)
    client.createLessonPlan(request).use { stream ->
        stream.collect { event ->
            when (event) {
                // Keep the id: streamLessonPlan rejoins a generation that was cut off.
                is CreateLessonPlanEvent.Started -> println("plan ${event.data.planId}")
                is CreateLessonPlanEvent.Phase -> println("phase ${event.data.phase}")
                is CreateLessonPlanEvent.Result -> println(event.data.plan.title)
            }
        }
    }
    // lingara:end
}
