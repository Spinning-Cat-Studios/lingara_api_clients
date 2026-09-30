package com.getlingara.client.internal;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The library's one daemon pool and one daemon scheduler, created on first use and shared by every
 * client in the JVM (ADR 29.9.26r D8). The pool runs token exchanges, so a waiter that leaves never
 * takes the exchange with it; the scheduler runs each stream's idle watchdog. Daemon threads never
 * keep a JVM alive, which is why a client needs no {@code close()}; they are named {@code
 * lingara-java-*}, so a thread dump says whose they are.
 */
public final class SharedExecutor {
  private SharedExecutor() {}

  /**
   * Returns the cached daemon pool.
   *
   * @return the pool
   */
  public static ExecutorService pool() {
    return Holder.POOL;
  }

  /**
   * Returns the daemon scheduler. A cancelled task leaves its queue at once.
   *
   * @return the scheduler
   */
  public static ScheduledExecutorService scheduler() {
    return Holder.SCHEDULER;
  }

  /** Initialised on first use: the JVM's class-holder idiom. */
  private static final class Holder {
    static final ExecutorService POOL = Executors.newCachedThreadPool(daemons("lingara-java-"));
    static final ScheduledThreadPoolExecutor SCHEDULER = scheduler();

    private static ScheduledThreadPoolExecutor scheduler() {
      ScheduledThreadPoolExecutor s =
          new ScheduledThreadPoolExecutor(1, daemons("lingara-java-watchdog-"));
      s.setRemoveOnCancelPolicy(true);
      return s;
    }

    private static ThreadFactory daemons(String prefix) {
      AtomicInteger next = new AtomicInteger();
      return task -> {
        Thread t = new Thread(task, prefix + next.incrementAndGet());
        t.setDaemon(true);
        return t;
      };
    }
  }
}
