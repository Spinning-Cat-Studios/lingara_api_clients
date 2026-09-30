import { Lingara } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
});

// lingara:begin errors
import { ApiError, MaintenanceError, OAuthError, TransportError } from "@lingara/api";

try {
  await client.getUsage();
} catch (e) {
  if (e instanceof ApiError) {
    // A refusal from the API: e.status, e.code (stable) and e.message (localised).
    console.error(e.status, e.code, e.message, e.retryAfter ?? "");
  } else if (e instanceof OAuthError) {
    // The token endpoint refused the credentials or the scopes.
    console.error(e.status, e.error, e.description ?? "");
  } else if (e instanceof MaintenanceError) {
    console.error("under maintenance; retry after", e.retryAfter ?? "a while");
  } else if (e instanceof TransportError) {
    // No usable answer: connect, tls, reset, timeout, and so on.
    console.error("transport:", e.kind);
  } else {
    throw e;
  }
}
// lingara:end
