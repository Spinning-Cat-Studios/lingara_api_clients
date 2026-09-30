package com.getlingara.client;

import java.time.Duration;

/**
 * Waits out a {@code Retry-After}: a testing seam (CONTRACT.md, Test seams).
 *
 * <p>The default ends early when the thread is interrupted, which is how a caller cancels a call
 * during a wait. A test's sleeper records each duration and returns at once.
 */
@FunctionalInterface
public interface Sleeper {
  /**
   * Waits for {@code duration}.
   *
   * @param duration how long to wait
   * @throws InterruptedException when the thread is interrupted during the wait
   */
  void sleep(Duration duration) throws InterruptedException;
}
