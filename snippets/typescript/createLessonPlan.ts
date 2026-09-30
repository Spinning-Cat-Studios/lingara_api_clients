import { Lingara } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
});

// lingara:begin createLessonPlan
const stream = client.createLessonPlan({
  context: "ordering at a night market",
  source_lang: "en",
  target_lang: "zh",
  level: 2,
});
for await (const ev of stream) {
  if (ev.event === "started") console.log("plan", ev.data.plan_id);
  if (ev.event === "phase") console.log("working:", ev.data.phase);
  if (ev.event === "result") console.log(ev.data.plan.title);
}
// lingara:end
