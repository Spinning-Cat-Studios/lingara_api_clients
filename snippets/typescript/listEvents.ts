import { Lingara } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
});
const savedCursor: string | undefined = process.argv[2];

// lingara:begin listEvents
import { ApiError, UnknownEvent } from "@lingara/api";

// Every event since the saved cursor, then stop: the feed never polls.
const feed = client.events(savedCursor ? { cursor: savedCursor } : { start: "oldest" });
try {
  for await (const event of feed) {
    if (event instanceof UnknownEvent) console.log("newer event type:", event.type);
    else console.log(event.type, event.id);
  }
} catch (e) {
  // Older than 30 days: start again from now, or from what is still kept.
  if (!(e instanceof ApiError && e.code === "cursor_expired")) throw e;
}
console.log("resume from", feed.cursor);
// lingara:end
