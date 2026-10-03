<?php

declare(strict_types=1);

namespace Lingara\Tests;

use Lingara\Internal\Frame;
use Lingara\Internal\Json;
use Lingara\Internal\SseDecoder;
use PHPUnit\Framework\TestCase;

final class SseDecoderTest extends TestCase
{
    /** C2 Example A: a vocabulary stream, keepalive included, with a multi-byte word. */
    private const EXAMPLE_A = ": keepalive\n\n"
        . "event: started\ndata: {\"meta\":{\"level\":2,\"source_lang\":\"en\",\"target_lang\":\"zh\",\"framework\":\"HSK\",\"count\":1,\"ai_generated\":true}}\n\n"
        . "event: item\r\ndata: {\"word\":\"你好\",\"translation\":\"hello\"}\r\n\r\n"
        . "event: done\ndata: {}\n\n";

    /**
     * 29.9.26u AC6: the decoder yields the same frames for every chunking of
     * C2 Example A's bytes, including a split inside a UTF-8 character and a
     * \r\n split across two reads.
     */
    public function testDecodesIdenticallyHoweverChunked(): void
    {
        $whole = self::decode([self::EXAMPLE_A]);
        self::assertSame(['started', 'item', 'done'], array_map(static fn(Frame $f): string => $f->event, $whole));
        self::assertSame('{"word":"你好","translation":"hello"}', $whole[1]->data);

        $length = strlen(self::EXAMPLE_A);
        for ($at = 1; $at < $length; $at++) {
            self::assertEquals($whole, self::decode([substr(self::EXAMPLE_A, 0, $at), substr(self::EXAMPLE_A, $at)]), "split at {$at}");
        }
        self::assertEquals($whole, self::decode(str_split(self::EXAMPLE_A)), 'a byte at a time');

        $insideCharacter = strpos(self::EXAMPLE_A, '你') + 1;
        $insideCrlf = strpos(self::EXAMPLE_A, "\r\n") + 1;
        foreach ([$insideCharacter, $insideCrlf] as $at) {
            self::assertEquals($whole, self::decode([substr(self::EXAMPLE_A, 0, $at), substr(self::EXAMPLE_A, $at)]));
        }
    }

    /**
     * 29.9.26u AC7: CRLF, CR and LF line endings, comments and multi-line
     * data parse per C2 D6, and a JSON number of magnitude 2^63 or more is
     * refused, never clamped.
     */
    public function testLineEndingsCommentsMultiLineDataAndWideNumbers(): void
    {
        $frames = self::decode(["event: a\rdata: 1\r\r: a comment\n", "data: x\ndata:y\ndata\n\nid: 7\nretry: 5\nevent: b\r\n\r\ndata:  z\n\n"]);
        self::assertEquals([new Frame('a', '1'), new Frame('message', "x\ny\n"), new Frame('message', ' z', '7')], $frames);

        $decoder = new SseDecoder();
        self::assertSame([], $decoder->feed("event: a\ndata: 1\n"));
        self::assertSame([], $decoder->finish(), 'a frame with no blank line after it is dropped');

        $widest = Json::decode('{"n":9223372036854775807}');
        self::assertInstanceOf(\stdClass::class, $widest);
        self::assertSame(PHP_INT_MAX, $widest->n);
        foreach (['9223372036854775808', '-9223372036854775808', '18446744073709551615', '1e19', '-9.3e18', '[1,99999999999999999999]'] as $wide) {
            try {
                Json::decode($wide);
                self::fail("{$wide} was not refused");
            } catch (\JsonException) {
            }
        }
        self::assertSame(['9223372036854775808'], Json::decode('["9223372036854775808"]'), 'a number inside a string is text');
    }

    /**
     * ADR 30.9.26aa D7 (K5's parsing rule): `id` sets the last-event-id
     * buffer, which persists across frames until the next `id`; an `id`
     * holding U+0000 is ignored, and an empty one empties the buffer.
     */
    public function testRecordsTheLastEventIdAcrossFrames(): void
    {
        $frames = self::decode(["data: 1\n\nid: c1\ndata: 2\n\ndata: 3\n\nid: c\0x\ndata: 4\n\nid\ndata: 5\n\n"]);
        self::assertSame([null, 'c1', 'c1', 'c1', ''], array_map(static fn(Frame $f): ?string => $f->id, $frames));
    }

    /**
     * @param list<string> $chunks
     *
     * @return list<Frame>
     */
    private static function decode(array $chunks): array
    {
        $decoder = new SseDecoder();
        $frames = [];
        foreach ($chunks as $chunk) {
            array_push($frames, ...$decoder->feed($chunk));
        }
        return $frames;
    }
}
