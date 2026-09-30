package com.getlingara.kotlin.internal

import com.getlingara.kotlin.internal.SseDecoder.Frame
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

internal class SseDecoderTest {
    private fun decode(vararg chunks: ByteArray): List<Frame> {
        val decoder = SseDecoder()
        return chunks.flatMap { decoder.feed(it, it.size) } + decoder.end()
    }

    private fun decode(vararg chunks: String): List<Frame> = decode(*chunks.map { it.toByteArray() }.toTypedArray())

    /**
     * 29.9.26s AC1: every two-piece split of Example A, every three-piece split whose cuts are at
     * most eight bytes apart (enough to cut each multi-byte character twice), and one byte at a
     * time yield the same frames, including a `\r\n` split across two reads.
     */
    @Test
    fun framesDoNotDependOnChunkBoundaries() {
        val b = EXAMPLE_A.toByteArray()
        assertEquals(EXAMPLE_FRAMES, decode(b))
        for (i in 0..b.size) {
            val head = b.copyOfRange(0, i)
            assertEquals(EXAMPLE_FRAMES, decode(head, b.copyOfRange(i, b.size)), "at $i")
            for (j in i..minOf(i + 8, b.size)) {
                assertEquals(EXAMPLE_FRAMES, decode(head, b.copyOfRange(i, j), b.copyOfRange(j, b.size)), "at $i, $j")
            }
        }
        assertEquals(EXAMPLE_FRAMES, decode(*b.map { byteArrayOf(it) }.toTypedArray()))
        assertEquals(listOf(Frame("a", "1")), decode("event: a\r", "\ndata: 1\r", "\n\r", "\n"))
    }

    /** 29.9.26s AC2: CRLF, CR and LF line endings, comments and multi-line data parse per C2 D6. */
    @Test
    fun lineEndingsCommentsAndMultiLineData() {
        val one = listOf(Frame("a", "1"))
        assertEquals(one, decode("event: a\ndata: 1\n\n"))
        assertEquals(one, decode("event: a\r\ndata: 1\r\n\r\n"))
        assertEquals(one, decode("event: a\rdata: 1\r\r"))
        assertEquals(one, decode(": comment\nevent: a\n: another\ndata: 1\n\n"))
        assertEquals(listOf(Frame("a", "line one\nline two")), decode("event: a\ndata: line one\ndata: line two\n\n"))
        assertEquals(listOf(Frame("message", "x")), decode("data: x\n\n"))
        assertEquals(listOf(), decode("event: a\n\n"), "a frame with no data is dropped")
        assertEquals(
            listOf(Frame("a", " two spaces")),
            decode("event:a\nid: 7\nretry: 10\nunknown: y\ndata:  two spaces\n\n"),
        )
        assertEquals(listOf(), decode("event: a\ndata: 1\n"), "no blank line, no dispatch")
    }

    companion object {
        /**
         * The body of CONTRACT.md's Example A (conformance case k5.vocab-split-frames), plus a CRLF
         * frame and a trailing keepalive.
         */
        const val EXAMPLE_A =
            ": keepalive\n\n" +
                "event: started\ndata: {\"meta\":{\"level\":2,\"source_lang\":\"en\",\"target_lang\":\"zh\"," +
                "\"framework\":\"HSK\",\"count\":2,\"ai_generated\":true}}\n" +
                "\nevent: item\ndata: {\"word\":\"你好\",\"pronunciation\":\"nǐ hǎo\",\"translation\":\"hello\"}\n\n" +
                "event: item\r\ndata: {\"word\":\"谢谢\",\"pronunciation\":\"xiè xie\"," +
                "\"translation\":\"thank you\"}\r\n\r\n" +
                ": keepalive\n\nevent: done\ndata: {}\n\n"

        val EXAMPLE_FRAMES =
            listOf(
                Frame(
                    "started",
                    "{\"meta\":{\"level\":2,\"source_lang\":\"en\",\"target_lang\":\"zh\"," +
                        "\"framework\":\"HSK\",\"count\":2,\"ai_generated\":true}}",
                ),
                Frame("item", "{\"word\":\"你好\",\"pronunciation\":\"nǐ hǎo\",\"translation\":\"hello\"}"),
                Frame("item", "{\"word\":\"谢谢\",\"pronunciation\":\"xiè xie\",\"translation\":\"thank you\"}"),
                Frame("done", "{}"),
            )
    }
}
