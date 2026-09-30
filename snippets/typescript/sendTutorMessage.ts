import { Lingara } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
});

// lingara:begin sendTutorMessage
const reply = client.sendTutorMessage({
  message: "你好！我想点一杯茶。",
  history: [],
  source_lang: "en",
  target_lang: "zh",
});
for await (const ev of reply) {
  if (ev.event === "delta") process.stdout.write(ev.data.text);
}
// lingara:end
