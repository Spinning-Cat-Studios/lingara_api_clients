package snippets;

import com.getlingara.client.LingaraClient;
// lingara:begin listEvents
import com.getlingara.client.events.Event;
import com.getlingara.client.events.EventFeed;
import com.getlingara.client.events.EventsRequest;

// lingara:end

/** The documentation site's listEvents example. */
public final class ListEvents {
  private ListEvents() {}

  static String run(LingaraClient client, String savedCursor) {
    // lingara:begin listEvents
    // Everything since the saved cursor, page by page; it stops when it has caught up.
    EventFeed feed = client.events(EventsRequest.of().cursor(savedCursor));
    while (feed.hasNext()) {
      Event event = feed.next();
      System.out.println(event.type() + " " + event.id());
    }
    // Save it, and call events again later: the feed never polls on its own.
    return feed.cursor().orElse(savedCursor);
    // lingara:end
  }
}
