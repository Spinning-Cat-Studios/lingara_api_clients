import { Lingara } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
});

// lingara:begin sendEvent
import { InboundEvent } from "@lingara/api";

const accepted = await client.sendEvent(
  InboundEvent.worldContextChanged({
    scene: "A night market after rain",
    npc: { name: "Auntie Lin", persona: "a street-food vendor who likes to haggle" },
    source_lang: "en",
    target_lang: "zh",
    level: 2,
    generate: true,
  }),
  // Your own key, so a resend after a crash gets the first answer back.
  { idempotencyKey: "save-17/night-market" },
);
const reaction = accepted.reaction;
if (reaction?.status === "started" && reaction.plan_status === "generating") {
  console.log("lesson_plan.ready or .failed will follow for", reaction.plan_id);
}
// lingara:end
