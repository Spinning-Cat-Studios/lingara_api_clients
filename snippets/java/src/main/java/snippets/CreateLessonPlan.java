package snippets;

import com.getlingara.client.EventStream;
import com.getlingara.client.LingaraClient;
// lingara:begin createLessonPlan
import com.getlingara.client.model.CreateLessonPlanEvent;
import com.getlingara.client.model.LessonPlanCreateRequest;

// lingara:end

/** The documentation site's createLessonPlan example. */
public final class CreateLessonPlan {
  private CreateLessonPlan() {}

  static void run(LingaraClient client) {
    // lingara:begin createLessonPlan
    LessonPlanCreateRequest request =
        new LessonPlanCreateRequest()
            .context("Ordering at a night market")
            .level(2)
            .sourceLang("en")
            .targetLang("zh");
    try (EventStream<CreateLessonPlanEvent> stream = client.createLessonPlan(request)) {
      for (CreateLessonPlanEvent event : stream) {
        if (event instanceof CreateLessonPlanEvent.Started started) {
          // Keep the id: streamLessonPlan rejoins a generation that was cut off.
          System.out.println("plan " + started.data().getPlanId());
        } else if (event instanceof CreateLessonPlanEvent.Phase phase) {
          System.out.println("phase " + phase.data().getPhase());
        } else if (event instanceof CreateLessonPlanEvent.Result result) {
          System.out.println(result.data().getPlan().getTitle());
        }
      }
    }
    // lingara:end
  }
}
