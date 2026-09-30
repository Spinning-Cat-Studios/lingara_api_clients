// The two testing seams (CONTRACT.md, Test seams). Both default to the real
// ones; the conformance harness injects a virtual clock and a sleeper that
// records each duration and returns at once.

/** Wall-clock time, in epoch milliseconds. A testing seam. */
export interface Clock {
  now(): number;
}

/**
 * Waits `ms` milliseconds, or rejects with `signal.reason` as soon as the
 * signal aborts. A testing seam.
 */
export type Sleeper = (ms: number, signal?: AbortSignal) => Promise<void>;

export const systemClock: Clock = { now: () => Date.now() };

export const realSleeper: Sleeper = (ms, signal) =>
  raceAbort(
    new Promise<void>((resolve) => {
      const timer = setTimeout(resolve, ms);
      signal?.addEventListener("abort", () => clearTimeout(timer), { once: true });
    }),
    signal,
  );

/**
 * Settles with `promise`, unless `signal` aborts first, in which case it
 * rejects with `signal.reason`. The promise itself is left running: this
 * abandons a wait, it does not cancel the work.
 */
export function raceAbort<T>(promise: Promise<T>, signal?: AbortSignal): Promise<T> {
  if (!signal) return promise;
  if (signal.aborted) {
    promise.catch(() => undefined);
    return Promise.reject(signal.reason);
  }
  return new Promise<T>((resolve, reject) => {
    const onAbort = () => reject(signal.reason);
    signal.addEventListener("abort", onAbort, { once: true });
    promise.then(
      (value) => {
        signal.removeEventListener("abort", onAbort);
        resolve(value);
      },
      (error: unknown) => {
        signal.removeEventListener("abort", onAbort);
        reject(error);
      },
    );
  });
}
