package com.getlingara.kotlin

import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** How a blocked read ends once the body is closed or its thread interrupted. */
enum class Ending { THROW, EOF }

/**
 * A body that hands out one scripted chunk per read. A `Long` step sleeps that many milliseconds;
 * [BLOCK] blocks until the body is closed or the reading thread interrupted, then throws or
 * returns EOF; [FAIL] fails the test if it is ever read.
 */
class ScriptedBody(
    private val ending: Ending,
    vararg steps: Any,
) : InputStream() {
    constructor(vararg steps: Any) : this(Ending.EOF, *steps)

    private val script = ArrayDeque(steps.toList())
    private val released = CountDownLatch(1)
    val blocked = CountDownLatch(1)
    val reads = AtomicInteger()

    @Volatile var closed = false

    @Volatile var readAfterFail = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        reads.incrementAndGet()
        var step = script.poll()
        while (step is Long) {
            Fakes.sleep(step)
            step = script.poll()
        }
        if (step == null || closed) return -1
        if (step == FAIL) {
            readAfterFail = true
            throw AssertionError("a byte after the terminal event was read")
        }
        if (step == BLOCK) return block()
        val bytes = (step as String).toByteArray()
        check(bytes.size <= length) { "a scripted chunk is larger than the read buffer" }
        bytes.copyInto(buffer, offset)
        return bytes.size
    }

    private fun block(): Int {
        blocked.countDown()
        try {
            released.await()
        } catch (e: InterruptedException) {
            // As the JDK's body stream does: the flag set, and an IOException (or EOF).
            Thread.currentThread().interrupt()
            return end(InterruptedIOException("interrupted"))
        }
        return end(IOException("closed"))
    }

    private fun end(failure: IOException): Int = if (ending == Ending.THROW) throw failure else -1

    override fun close() {
        closed = true
        released.countDown()
    }

    companion object {
        const val BLOCK = "\u0000block"
        const val FAIL = "\u0000fail"
    }
}

/** A TCP listener on 127.0.0.1:0 that hands each accepted socket to a script. */
class Listener(
    script: (Socket) -> Unit,
) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

    init {
        val accept =
            Thread {
                while (!server.isClosed) {
                    try {
                        server.accept().use(script)
                    } catch (e: IOException) {
                        // The listener was closed, or the client went away.
                    }
                }
            }
        accept.isDaemon = true
        accept.start()
    }

    fun uri(scheme: String): URI = URI.create("$scheme://127.0.0.1:${server.localPort}")

    override fun close() = server.close()

    companion object {
        /** Reads a request's head and, by its Content-Length, its body. */
        fun readRequest(socket: Socket) {
            val input = socket.getInputStream()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) return
                head.append(b.toChar())
            }
            Regex("(?i)content-length:\\s*(\\d+)").find(head)?.let { input.readNBytes(it.groupValues[1].toInt()) }
        }

        fun write(
            socket: Socket,
            text: String,
        ) {
            socket.getOutputStream().write(text.toByteArray())
            socket.getOutputStream().flush()
        }

        /** Blocks until the client closes the connection; a reset is a disconnect too. */
        fun awaitHangUp(socket: Socket) {
            try {
                while (socket.getInputStream().read() >= 0) {
                    // Hold until the client closes the connection.
                }
            } catch (e: IOException) {
                // A reset is a disconnect too.
            }
        }
    }
}
