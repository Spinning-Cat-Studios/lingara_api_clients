package snippets;

import com.getlingara.client.LingaraClient;
// lingara:begin getLessonPlan
import com.getlingara.client.model.LessonPlan;

// lingara:end

/** The documentation site's getLessonPlan example. */
public final class GetLessonPlan {
  private GetLessonPlan() {}

  static void run(LingaraClient client, String planId) {
    // lingara:begin getLessonPlan
    LessonPlan plan = client.getLessonPlan(planId).body();
    System.out.println(plan.getTitle() + " (" + plan.getStatus() + ")");
    // lingara:end
  }
}
