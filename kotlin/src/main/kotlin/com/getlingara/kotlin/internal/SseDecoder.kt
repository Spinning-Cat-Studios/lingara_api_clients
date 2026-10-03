package com.getlingara.kotlin.internal

import java.io.ByteArrayOutputStream

/**
 * The server-sent-events decoder: bytes in, frames out, no I/O (CONTRACT.md K5, Parsing; ADR
 * 29.9.26s D9).
 *
 * It splits lines on bytes, at `\r\n`, `\n` or `\r`. Every line ending is ASCII, so a UTF-8
 * character split across reads is carried whole in the line buffer and decoded only once its line
 * ends. A `\r` that ends one read is remembered, so a `\n` opening the next is the same line
 * ending rather than a blank line that would dispatch early. The frames are therefore the same
 * however the bytes were chunked, down to one byte at a time.
 *
 * `id` sets the last-event-id buffer, which persists across frames until the next `id` field, as
 * WHATWG defines it; an `id` containing U+0000 is ignored. Every frame carries the buffer as it
 * stood when the frame was dispatched (ADR 30.9.26aa D7).
 */
internal class SseDecoder {
    /**
     * One dispatched frame: its event name (`message` when it named none), its data lines, and the
     * last-event-id buffer (empty until an `id` field sets it).
     */
    data class Frame(
        val event: String,
        val data: String,
        val id: String = "",
    )

    private val line = ByteArrayOutputStream()
    private val data = mutableListOf<String>()
    private var event = ""
    private var lastEventId = ""
    private var skipLineFeed = false

    /** Consumes the first [length] bytes of [bytes] and returns every frame they completed. */
    fun feed(
        bytes: ByteArray,
        length: Int,
    ): List<Frame> {
        val frames = mutableListOf<Frame>()
        for (i in 0 until length) {
            val b = bytes[i]
            val lineFeedAfterReturn = skipLineFeed && b == LF
            skipLineFeed = false
            if (lineFeedAfterReturn) continue
            if (b == LF || b == CR) {
                endLine(frames)
                skipLineFeed = b == CR
            } else {
                line.write(b.toInt())
            }
        }
        return frames
    }

    /** The end of the body: an unterminated last frame is never dispatched (C2 D6). */
    fun end(): List<Frame> = emptyList()

    private fun endLine(frames: MutableList<Frame>) {
        val text = line.toString(Charsets.UTF_8)
        line.reset()
        when {
            text.isEmpty() -> dispatch(frames)
            text.startsWith(":") -> Unit
            else -> field(text)
        }
    }

    private fun field(text: String) {
        val colon = text.indexOf(':')
        val name = if (colon < 0) text else text.substring(0, colon)
        val value = if (colon < 0) "" else text.substring(colon + 1).removePrefix(" ")
        // retry and unknown fields are ignored.
        when (name) {
            "event" -> event = value
            "data" -> data.add(value)
            "id" -> if (NUL !in value) lastEventId = value
        }
    }

    /** Ends a frame on a blank line: one with no data is dropped, one with no event is "message". */
    private fun dispatch(frames: MutableList<Frame>) {
        if (data.isNotEmpty()) {
            frames.add(Frame(event.ifEmpty { "message" }, data.joinToString("\n"), lastEventId))
        }
        event = ""
        data.clear()
    }

    private companion object {
        const val LF: Byte = '\n'.code.toByte()
        const val CR: Byte = '\r'.code.toByte()
        const val NUL: Char = Char.MIN_VALUE
    }
}
