package com.getlingara.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Byte-level fakes: a response body read by read, and a raw TCP listener. */
final class Scripted {
  private Scripted() {}

  /** How a blocked read ends once the body is closed or its thread interrupted. */
  enum Ending {
    THROW,
    EOF
  }

  /**
   * A body that hands out one scripted chunk per read. {@code BLOCK} blocks until the body is
   * closed or the reading thread interrupted, then throws or returns EOF; {@code FAIL} fails the
   * test if it is ever read.
   */
  static final class Body extends InputStream {
    static final String BLOCK = "\u0000block";
    static final String FAIL = "\u0000fail";

    private final Deque<Object> script = new ArrayDeque<>();
    private final Ending ending;
    private final CountDownLatch released = new CountDownLatch(1);
    final CountDownLatch blocked = new CountDownLatch(1);
    final AtomicInteger reads = new AtomicInteger();
    volatile boolean closed;
    volatile boolean readAfterFail;

    Body(Ending ending, Object... steps) {
      this.ending = ending;
      script.addAll(List.of(steps));
    }

    Body(Object... steps) {
      this(Ending.EOF, steps);
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      reads.incrementAndGet();
      Object step = script.poll();
      while (step instanceof Long millis) {
        Fakes.sleep(millis);
        step = script.poll();
      }
      if (step == null || closed) {
        return -1;
      }
      if (FAIL.equals(step)) {
        readAfterFail = true;
        throw new AssertionError("a byte after the terminal event was read");
      }
      if (BLOCK.equals(step)) {
        return block();
      }
      byte[] bytes = ((String) step).getBytes(StandardCharsets.UTF_8);
      if (bytes.length > length) {
        throw new IllegalStateException("a scripted chunk is larger than the read buffer");
      }
      System.arraycopy(bytes, 0, buffer, offset, bytes.length);
      return bytes.length;
    }

    private int block() throws IOException {
      blocked.countDown();
      try {
        released.await();
      } catch (InterruptedException e) {
        // As the JDK's body stream does: the flag set, and an IOException (or EOF).
        Thread.currentThread().interrupt();
        return end(new InterruptedIOException("interrupted"));
      }
      return end(new IOException("closed"));
    }

    private int end(IOException failure) throws IOException {
      if (ending == Ending.THROW) {
        throw failure;
      }
      return -1;
    }

    @Override
    public void close() {
      closed = true;
      released.countDown();
    }
  }

  /** A TCP listener on 127.0.0.1:0 that hands each accepted socket to a script. */
  static final class Listener implements AutoCloseable {
    /** What the listener does with one connection. */
    @FunctionalInterface
    interface Script {
      void run(Socket socket) throws IOException;
    }

    private final ServerSocket server;

    Listener(Script script) throws IOException {
      server = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
      Thread accept =
          new Thread(
              () -> {
                while (!server.isClosed()) {
                  try (Socket socket = server.accept()) {
                    script.run(socket);
                  } catch (IOException e) {
                    // The listener was closed, or the client went away.
                  }
                }
              });
      accept.setDaemon(true);
      accept.start();
    }

    URI uri(String scheme) {
      return URI.create(scheme + "://127.0.0.1:" + server.getLocalPort());
    }

    @Override
    public void close() throws IOException {
      server.close();
    }

    /** Reads a request's head and, by its Content-Length, its body. */
    static void readRequest(Socket socket) throws IOException {
      InputStream in = socket.getInputStream();
      StringBuilder head = new StringBuilder();
      while (!head.toString().endsWith("\r\n\r\n")) {
        int b = in.read();
        if (b < 0) {
          return;
        }
        head.append((char) b);
      }
      java.util.regex.Matcher length =
          java.util.regex.Pattern.compile("(?i)content-length:\\s*(\\d+)").matcher(head);
      if (length.find()) {
        in.readNBytes(Integer.parseInt(length.group(1)));
      }
    }

    static void write(Socket socket, String text) throws IOException {
      socket.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
      socket.getOutputStream().flush();
    }
  }
}
