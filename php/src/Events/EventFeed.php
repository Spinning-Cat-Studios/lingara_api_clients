<?php

declare(strict_types=1);

namespace Lingara\Events;

use Lingara\Events\Generated\EventParser;
use Lingara\Exception\TransportException;
use Lingara\Exception\TransportKind;

/**
 * The events feed, page after page (CONTRACT.md, The event helpers; ADR
 * 30.9.26aa D6). Built by Client::events().
 *
 *     $feed = $client->events(cursor: $saved);
 *     foreach ($feed as $event) {
 *         handle($event);
 *     }
 *     $saved = $feed->cursor();
 *
 * It ends on a page with `has_more: false`, and never sleeps or polls: call
 * events() again later with the saved cursor, or move to tailEvents(). The
 * cursor advances after a page's last event is yielded, so a loop that
 * stops mid-page sees the rest of that page again; deduplicate by `id`. A
 * cursor older than the retention window is ApiException `cursor_expired`:
 * start again with no cursor, or with `start: 'oldest'`.
 *
 * It may be iterated once.
 *
 * @implements \IteratorAggregate<int, Event>
 */
final class EventFeed implements \IteratorAggregate
{
    private bool $iterated = false;

    /**
     * @param \Closure(array<string, string>): mixed $page   one listEvents body, decoded
     * @param list<string>|null                      $types
     *
     * @internal built by Client
     */
    public function __construct(
        private readonly \Closure $page,
        private ?string $cursor,
        private readonly ?string $start,
        private readonly ?array $types,
    ) {}

    /**
     * The query a feed or tail request sends: `types` comma-separated as one
     * value, and every absent option left out.
     *
     * @param list<string>|null $types
     *
     * @return array<string, string>
     *
     * @internal
     */
    public static function query(?string $cursor, ?string $start, ?array $types, ?int $limit = null): array
    {
        $query = ['cursor' => $cursor, 'start' => $start, 'types' => $types === null ? null : implode(',', $types)];
        $query['limit'] = $limit === null ? null : (string) $limit;
        return array_filter($query, static fn(?string $v): bool => $v !== null);
    }

    /** The next page's cursor once a page has been read to its end, else the cursor the feed was opened with. */
    public function cursor(): ?string
    {
        return $this->cursor;
    }

    /** @throws \LogicException on a second call */
    public function getIterator(): \Generator
    {
        if ($this->iterated) {
            throw new \LogicException('an EventFeed can be iterated once');
        }
        $this->iterated = true;
        return $this->pages();
    }

    /** @return \Generator<int, Event> */
    private function pages(): \Generator
    {
        do {
            // `start` only with no cursor: the server refuses the pair.
            $page = ($this->page)(self::query($this->cursor, $this->cursor === null ? $this->start : null, $this->types));
            [$items, $next, $more] = self::fields($page);
            foreach ($items as $item) {
                yield self::event($item);
            }
            $this->cursor = $next;
        } while ($more);
    }

    /** @return array{list<mixed>, string, bool} */
    private static function fields(mixed $page): array
    {
        $items = $page instanceof \stdClass ? ($page->items ?? null) : null;
        $next = $page instanceof \stdClass ? ($page->next_cursor ?? null) : null;
        $more = $page instanceof \stdClass ? ($page->has_more ?? null) : null;
        if (!is_array($items) || !array_is_list($items) || !is_string($next) || !is_bool($more)) {
            throw new TransportException(TransportKind::MalformedResponse, 'an event page is not {items, next_cursor, has_more}');
        }
        return [$items, $next, $more];
    }

    private static function event(mixed $item): Event
    {
        try {
            return EventParser::fromValue($item);
        } catch (\UnexpectedValueException $e) {
            throw new TransportException(TransportKind::MalformedEvent, "event: {$e->getMessage()}");
        }
    }
}
