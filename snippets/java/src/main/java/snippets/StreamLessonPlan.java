package snippets;

import com.getlingara.client.EventStream;
import com.getlingara.client.LingaraClient;
// lingara:begin streamLessonPlan
import com.getlingara.client.model.StreamLessonPlanEvent;

// lingara:end

/** The documentation site's streamLessonPlan example. */
public final class StreamLessonPlan {
  private StreamLessonPlan() {}

  static void run(LingaraClient client, String planId) {
    // lingara:begin streamLessonPlan
    try (EventStream<StreamLessonPlanEvent> stream = client.streamLessonPlan(planId)) {
      for (StreamLessonPlanEvent event : stream) {
        if (event instanceof StreamLessonPlanEvent.Result result) {
          System.out.println(result.data().getPlan().getTitle());
        } else if (event instanceof StreamLessonPlanEvent.Pending pending) {
          // Still generating elsewhere: ask again later.
          System.out.println("pending: " + pending.data().getStatus());
        }
      }
    }
    // lingara:end
  }
}
