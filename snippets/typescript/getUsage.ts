import { Lingara } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
});

// lingara:begin getUsage
const usage = await client.getUsage();
for (const row of usage.allowance) {
  console.log(row.feature, row.window, `${row.remaining} of ${row.limit} left`);
}
// lingara:end
