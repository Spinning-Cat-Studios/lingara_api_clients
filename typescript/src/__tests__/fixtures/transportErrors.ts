// The shapes a failed `fetch` takes on each runtime (the transport table in
// the library's design), and the kind each must map to.
//
// Node's were observed on Node 22 (undici) through the conformance run.
// Deno's and Bun's follow each runtime's documented error forms: Deno puts
// the OS or TLS error in `message` with no code, and Bun puts its own code
// spelling on the error itself.

export interface TransportShape {
  runtime: "node" | "deno" | "bun";
  what: string;
  phase: "fetch" | "body";
  error: unknown;
  kind: "connect" | "tls" | "reset";
}

function withCause(message: string, cause: unknown): Error {
  return new TypeError(message, { cause });
}

function coded(code: string, message: string, cause?: unknown): Error {
  return Object.assign(new Error(message, cause === undefined ? undefined : { cause }), { code });
}

export const TRANSPORT_SHAPES: TransportShape[] = [
  // Node (undici): TypeError("fetch failed"), the code on `cause`.
  { runtime: "node", what: "refused", phase: "fetch", kind: "connect", error: withCause("fetch failed", coded("ECONNREFUSED", "connect ECONNREFUSED 127.0.0.1:9")) },
  { runtime: "node", what: "dns", phase: "fetch", kind: "connect", error: withCause("fetch failed", coded("ENOTFOUND", "getaddrinfo ENOTFOUND api.invalid")) },
  { runtime: "node", what: "reset before the response", phase: "fetch", kind: "reset", error: withCause("fetch failed", coded("ECONNRESET", "read ECONNRESET")) },
  { runtime: "node", what: "reset mid-body", phase: "body", kind: "reset", error: withCause("terminated", coded("UND_ERR_SOCKET", "other side closed")) },
  // A TLS code with no CERT in it.
  { runtime: "node", what: "untrusted chain", phase: "fetch", kind: "tls", error: withCause("fetch failed", coded("UNABLE_TO_VERIFY_LEAF_SIGNATURE", "unable to verify the first certificate")) },
  // A message naming both a socket and TLS: tls is checked first.
  {
    runtime: "node",
    what: "socket closed during the handshake",
    phase: "fetch",
    kind: "tls",
    error: withCause("fetch failed", coded("ECONNRESET", "Client network socket disconnected before secure TLS connection was established")),
  },
  // The code one level deeper, on cause.cause.
  { runtime: "node", what: "nested cause", phase: "fetch", kind: "connect", error: withCause("fetch failed", new Error("wrapped", { cause: coded("EAI_AGAIN", "getaddrinfo EAI_AGAIN") })) },
  // Deno: a TypeError with no code; the detail is in the message.
  {
    runtime: "deno",
    what: "refused",
    phase: "fetch",
    kind: "connect",
    error: new TypeError("error sending request for url (http://127.0.0.1:9/v1/versions): client error (Connect): tcp connect error: Connection refused (os error 61)"),
  },
  {
    runtime: "deno",
    what: "invalid certificate",
    phase: "fetch",
    kind: "tls",
    error: new TypeError("error sending request for url (https://127.0.0.1:9/v1/versions): client error (Connect): invalid peer certificate: UnknownIssuer"),
  },
  { runtime: "deno", what: "reset mid-body", phase: "body", kind: "reset", error: new TypeError("error reading a body from connection: connection reset") },
  { runtime: "deno", what: "unrecognised, before the response", phase: "fetch", kind: "connect", error: new TypeError("error sending request for url (http://127.0.0.1:9/)") },
  // Bun: its own code spelling on the error itself.
  { runtime: "bun", what: "refused", phase: "fetch", kind: "connect", error: coded("ConnectionRefused", "Unable to connect. Is the computer able to access the url?") },
  { runtime: "bun", what: "closed", phase: "body", kind: "reset", error: coded("ConnectionClosed", "The socket connection was closed unexpectedly.") },
  { runtime: "bun", what: "untrusted chain", phase: "fetch", kind: "tls", error: coded("UNABLE_TO_VERIFY_LEAF_SIGNATURE", "unable to verify the first certificate") },
  { runtime: "bun", what: "unrecognised, after the response", phase: "body", kind: "reset", error: coded("Unknown", "something went wrong") },
];
