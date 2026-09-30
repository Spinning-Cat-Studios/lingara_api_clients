// lingara:begin auth
import { Lingara } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
});
// lingara:end

// lingara:begin generateVocabulary
const stream = client.generateVocabulary({ level: 2, source_lang: "en", target_lang: "zh", count: 8 });
for await (const ev of stream) {
  if (ev.event === "item") console.log(ev.data.word, ev.data.translation);
}
// lingara:end
