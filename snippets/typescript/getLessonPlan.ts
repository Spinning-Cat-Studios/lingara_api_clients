import { Lingara } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
});
const planId = process.argv[2]!;

// lingara:begin getLessonPlan
const plan = await client.getLessonPlan({ id: planId });
console.log(plan.status, plan.title);
// lingara:end
