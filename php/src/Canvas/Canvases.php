<?php

declare(strict_types=1);

namespace Lingara\Canvas;

use Lingara\ApiResponse;
use Lingara\Exception\TransportException;
use Lingara\Exception\TransportKind;
use Lingara\Model\ActivateCanvasRequest;
use Lingara\Model\Canvas;
use Lingara\Model\CanvasDryRun;
use Lingara\Model\CanvasPage;
use Lingara\Model\CanvasPlan;
use Lingara\Model\CanvasSummary;
use Lingara\Model\CreateCanvasRequest;
use Lingara\Model\RenameCanvasRequest;
use Lingara\Model\SaveCanvasDraftRequest;
use Lingara\ObjectSerializer;

/**
 * The canvas operations (ADR 16.10.26ak; scopes canvas:read and
 * canvas:write), reached as `$client->canvases()`. The group holds no state
 * of its own: every call goes through the client's request path, so the
 * token, retries, version pin and errors are the client's.
 *
 * A stale `draft_version` is 409 canvas_conflict, thrown as an ApiException
 * and never retried: re-read with getCanvas(), compare its draft with yours
 * (after a retried save it may be your own), and decide. A replayed
 * activateCanvas() answers 409 canvas_conflict too, and writes nothing.
 * Branch on an error's code(), never its localised message. A dry-run
 * answers 200 whether or not `ok` is true; a refused verdict's `code` and
 * English `error` are data. There is no delete: a canvas is deleted through
 * the platform's lifecycle routes, not this library.
 */
final class Canvases
{
    /** The filter keys, in the order they are sent; the last three are lists. */
    private const FILTERS = ['limit', 'name', 'status', 'recipes', 'kinds', 'scopes'];

    /**
     * Built by Client::canvases() over the client's own JSON request path:
     * (operationId, args, model, query, headers, payload) → ApiResponse.
     */
    public function __construct(private readonly \Closure $request) {}

    /**
     * One page of canvases; pageCanvases() walks them. $params holds
     * `cursor` (a previous page's next_cursor) and the filters: `limit`
     * (1–50), `name`, `status` (all, live, changed or unapplied), and the
     * lists `recipes`, `kinds` and `scopes`, each sent comma-separated.
     *
     * @param array<string, mixed> $params
     *
     * @return ApiResponse<CanvasPage>
     */
    public function listCanvases(array $params = []): ApiResponse
    {
        return $this->call('listCanvases', [], CanvasPage::class, null, self::query($params));
    }

    /**
     * Creates a canvas (201). The answer does not echo `recipe`.
     *
     * @return ApiResponse<Canvas>
     */
    public function createCanvas(CreateCanvasRequest $body): ApiResponse
    {
        return $this->call('createCanvas', [], Canvas::class, $body);
    }

    /**
     * One canvas, its draft and active revision inline.
     *
     * @return ApiResponse<Canvas>
     */
    public function getCanvas(string $canvasId): ApiResponse
    {
        return $this->call('getCanvas', [$canvasId], Canvas::class);
    }

    /**
     * Renames a canvas (PATCH).
     *
     * @return ApiResponse<Canvas>
     */
    public function renameCanvas(string $canvasId, RenameCanvasRequest $body): ApiResponse
    {
        return $this->call('renameCanvas', [$canvasId], Canvas::class, $body);
    }

    /**
     * Saves the draft (PUT). A stale `draft_version` is 409 canvas_conflict,
     * which may follow your own retried save: re-read with getCanvas() and
     * compare before you decide.
     *
     * @return ApiResponse<Canvas>
     */
    public function saveCanvasDraft(string $canvasId, SaveCanvasDraftRequest $body): ApiResponse
    {
        return $this->call('saveCanvasDraft', [$canvasId], Canvas::class, $body);
    }

    /**
     * Promotes a draft with the objects an apply made. A replay at a
     * `draft_version` already promoted is 409 canvas_conflict.
     *
     * @return ApiResponse<Canvas>
     */
    public function activateCanvas(string $canvasId, ActivateCanvasRequest $body): ApiResponse
    {
        return $this->call('activateCanvas', [$canvasId], Canvas::class, $body);
    }

    /**
     * Judges a plan without applying it. A plan with a refused op still
     * answers 200, `ok` false.
     *
     * @return ApiResponse<CanvasDryRun>
     */
    public function dryRunCanvasPlan(CanvasPlan $body): ApiResponse
    {
        return $this->call('dryRunCanvasPlan', [], CanvasDryRun::class, $body);
    }

    /**
     * Every canvas the filters match (listCanvases()' keys, less `cursor`),
     * following next_cursor until a page says has_more false, the filters
     * resent on every page. It fetches lazily, never sleeps or polls, and
     * stops on the first error. A page with has_more and no next_cursor is a
     * TransportException (malformed_response), its items unyielded.
     *
     * @param array<string, mixed> $filters
     *
     * @return \Generator<int, CanvasSummary>
     */
    public function pageCanvases(array $filters = []): \Generator
    {
        unset($filters['cursor']);
        $cursor = null;
        do {
            $page = $this->listCanvases($filters + ['cursor' => $cursor])->value;
            $more = (bool) $page->getHasMore();
            if ($more && $page->getNextCursor() === null) {
                throw new TransportException(TransportKind::MalformedResponse, 'a canvas page has more but no next_cursor');
            }
            yield from $page->getItems();
            $cursor = $page->getNextCursor();
        } while ($more);
    }

    /**
     * @template T of object
     *
     * @param list<string>          $args
     * @param class-string<T>       $model
     * @param array<string, string> $query
     *
     * @return ApiResponse<T>
     */
    private function call(string $operationId, array $args, string $model, ?object $body = null, array $query = []): ApiResponse
    {
        $payload = $body === null ? null : json_encode(ObjectSerializer::sanitizeForSerialization($body), JSON_THROW_ON_ERROR);
        /** @var ApiResponse<T> */
        return ($this->request)($operationId, $args, $model, $query, [], $payload);
    }

    /**
     * listCanvases()' query: `cursor`, then the filters; an absent or empty
     * value is left out and a list is joined with commas.
     *
     * @param array<string, mixed> $params
     *
     * @return array<string, string>
     */
    private static function query(array $params): array
    {
        $query = [];
        foreach (['cursor', ...self::FILTERS] as $key) {
            $value = $params[$key] ?? null;
            if (is_array($value)) {
                $value = $value === [] ? null : implode(',', array_map(static fn(mixed $v): string => is_scalar($v) ? (string) $v : '', $value));
            }
            if ($value !== null) {
                $query[$key] = is_scalar($value) ? (string) $value : '';
            }
        }
        return $query;
    }
}
