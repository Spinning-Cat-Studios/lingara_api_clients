package snippets

// lingara:begin listEvents
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.events.events
// lingara:end

/** The documentation site's listEvents example. */
suspend fun listEventsSnippet(
    client: LingaraClient,
    savedCursor: String?,
): String? {
    // lingara:begin listEvents
    // Everything since the saved cursor, page by page; it completes when it has caught up.
    val feed = client.events(cursor = savedCursor)
    feed.collect { event -> println("${event.type} ${event.id}") }
    // Save it, and collect again later: the feed never polls on its own.
    return feed.cursor
    // lingara:end
}
