import { Lingara } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
});
const savedCursor: string | undefined = process.argv[2];

// lingara:begin streamEvents
import { UnknownEvent } from "@lingara/api";

// Live events: the tail reconnects from its cursor after every ending, and
// raises only after 8 failed reopens in a row.
const tail = client.tailEvents({ cursor: savedCursor, types: ["lesson_plan.ready", "lesson_plan.failed"] });
for await (const event of tail) {
  if (event instanceof UnknownEvent) continue; // A newer type: log it if you like.
  if (event.type === "lesson_plan.ready") console.log("ready:", event.data.plan_id);
  if (event.type === "lesson_plan.failed") console.log("failed:", event.data.plan_id);
  // Save tail.cursor to resume after a restart.
}
// lingara:end
