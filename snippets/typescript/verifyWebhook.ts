import { createServer } from "node:http";

// lingara:begin verifyWebhook
import { UnknownEvent, Webhook, WebhookVerificationError } from "@lingara/api";

// The secret from your webhook endpoint's settings; pass two during a rotation.
const webhook = new Webhook(process.env.LINGARA_WEBHOOK_SECRET!);

createServer(async (req, res) => {
  // Verify the raw body, exactly as received, before anything parses it.
  const chunks: Buffer[] = [];
  for await (const chunk of req) chunks.push(chunk as Buffer);
  try {
    const event = await webhook.verify(Buffer.concat(chunks), req.headers);
    res.writeHead(204).end(); // Answer fast; deduplicate by event.id.
    if (event instanceof UnknownEvent) console.log("newer event type:", event.type, event.id);
    else if (event.type === "lesson_plan.ready") console.log("plan ready:", event.data.plan_id);
  } catch (e) {
    if (!(e instanceof WebhookVerificationError)) throw e;
    res.writeHead(400).end(e.reason);
  }
}).listen(8787);
// lingara:end
