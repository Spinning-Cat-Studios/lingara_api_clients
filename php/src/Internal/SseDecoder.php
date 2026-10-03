<?php

declare(strict_types=1);

namespace Lingara\Internal;

/**
 * K5's frame parser (CONTRACT.md K5, Parsing): pure, with no I/O. It buffers
 * bytes, splits lines at \r\n, \n or \r (holding a trailing \r until the next
 * byte says whether a \n follows), and hands on only complete lines. A PHP
 * string is bytes, so a UTF-8 character split across two reads is simply
 * joined before its line completes; json_decode validates it per frame. The
 * frames are the same however the bytes were chunked.
 *
 * An `id` field sets the last-event-id buffer, which persists across frames
 * until the next `id` field, as WHATWG defines it; an `id` whose value holds
 * U+0000 is ignored. Every frame carries the buffer, and only K5a's tail
 * reads it (ADR 30.9.26aa D7).
 *
 * @internal
 */
final class SseDecoder
{
    private string $buffer = '';
    private ?string $event = null;
    private ?string $data = null;
    private ?string $lastEventId = null;

    /**
     * Feeds bytes and returns every frame they complete.
     *
     * @return list<Frame>
     */
    public function feed(string $bytes): array
    {
        $this->buffer .= $bytes;
        $frames = [];
        while (($line = $this->nextLine()) !== null) {
            $frame = $this->takeLine($line);
            if ($frame !== null) {
                $frames[] = $frame;
            }
        }
        return $frames;
    }

    /**
     * The end of the stream: a partial line, and any frame with no blank line
     * after it, is dropped.
     *
     * @return list<Frame>
     */
    public function finish(): array
    {
        $this->buffer = '';
        $this->event = null;
        $this->data = null;
        return [];
    }

    /** The next complete line without its terminator, or null. */
    private function nextLine(): ?string
    {
        $index = strcspn($this->buffer, "\r\n");
        $length = strlen($this->buffer);
        if ($index === $length) {
            return null;
        }
        $width = 1;
        if ($this->buffer[$index] === "\r") {
            if ($index === $length - 1) {
                return null;
            }
            $width = $this->buffer[$index + 1] === "\n" ? 2 : 1;
        }
        $line = substr($this->buffer, 0, $index);
        $this->buffer = substr($this->buffer, $index + $width);
        return $line;
    }

    private function takeLine(string $line): ?Frame
    {
        if ($line === '') {
            return $this->dispatch();
        }
        if ($line[0] === ':') {
            return null;
        }
        $parts = explode(':', $line, 2);
        $value = $parts[1] ?? '';
        if (str_starts_with($value, ' ')) {
            $value = substr($value, 1);
        }
        if ($parts[0] === 'event') {
            $this->event = $value;
        } elseif ($parts[0] === 'data') {
            $this->data = $this->data === null ? $value : "{$this->data}\n{$value}";
        } elseif ($parts[0] === 'id' && !str_contains($value, "\0")) {
            $this->lastEventId = $value;
        }
        return null;
    }

    private function dispatch(): ?Frame
    {
        $frame = $this->data === null ? null : new Frame($this->event ?? 'message', $this->data, $this->lastEventId);
        $this->event = null;
        $this->data = null;
        return $frame;
    }
}
