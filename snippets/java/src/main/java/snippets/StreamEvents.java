package snippets;

import com.getlingara.client.EventStream;
import com.getlingara.client.LingaraClient;
// lingara:begin streamEvents
import com.getlingara.client.events.Event;
import com.getlingara.client.events.EventsRequest;
import com.getlingara.client.events.LessonPlanFailed;
import com.getlingara.client.events.LessonPlanReady;

// lingara:end

/** The documentation site's streamEvents example. */
public final class StreamEvents {
  private StreamEvents() {}

  static void run(LingaraClient client, String savedCursor) {
    // lingara:begin streamEvents
    // Live events: the tail reconnects from its cursor after every ending, and raises only after
    // 8 failed reopens in a row.
    EventsRequest request =
        EventsRequest.of().cursor(savedCursor).types("lesson_plan.ready", "lesson_plan.failed");
    try (EventStream<Event> tail = client.tailEvents(request)) {
      for (Event event : tail) {
        if (event instanceof LessonPlanReady ready) {
          System.out.println("ready: " + ready.data().getPlanId());
        } else if (event instanceof LessonPlanFailed failed) {
          System.out.println("failed: " + failed.data().getPlanId());
        }
        // Save tail.cursor() to resume after a restart.
      }
    }
    // lingara:end
  }
}
