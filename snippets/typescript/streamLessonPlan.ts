import { Lingara } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
});
const planId = process.argv[2]!;

// lingara:begin streamLessonPlan
for await (const ev of client.streamLessonPlan({ id: planId })) {
  if (ev.event === "result") console.log("ready:", ev.data.plan.title);
  if (ev.event === "pending") console.log("still", ev.data.status, "- try again later");
}
// lingara:end
