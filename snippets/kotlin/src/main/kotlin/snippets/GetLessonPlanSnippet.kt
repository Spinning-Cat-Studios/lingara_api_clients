package snippets

import com.getlingara.kotlin.LingaraClient

/** The documentation site's getLessonPlan example. */
suspend fun getLessonPlanSnippet(
    client: LingaraClient,
    planId: String,
) {
    // lingara:begin getLessonPlan
    val plan = client.getLessonPlan(planId).body
    println("${plan.title} (${plan.status})")
    // lingara:end
}
