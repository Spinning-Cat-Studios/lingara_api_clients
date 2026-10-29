<?php

declare(strict_types=1);

namespace Lingara\Dialogue;

use Lingara\ApiResponse;
use Lingara\Dialogue\ExportedDialogue\Body;
use Lingara\Dialogue\ExportedDialogue\NotModified;
use Lingara\Exception\TransportException;
use Lingara\Exception\TransportKind;
use Lingara\Model\ActivateDialogueRequest;
use Lingara\Model\CreateDialogueRequest;
use Lingara\Model\Dialogue;
use Lingara\Model\DialoguePage;
use Lingara\Model\DialogueSummary;
use Lingara\Model\RenameDialogueRequest;
use Lingara\Model\SaveDialogueDraftRequest;
use Lingara\ObjectSerializer;
use Psr\Http\Message\ResponseInterface;

/**
 * The dialogue operations (ADR 29.10.26c; scopes dialogues:read and
 * dialogues:write), reached as `$client->dialogues()`. The group holds no
 * state of its own: every call goes through the client's request path, so
 * the token, retries, version pin and errors are the client's.
 *
 * A stale `draft_version` is 409 dialogue_conflict, thrown as an
 * ApiException and never retried: re-read with getDialogue(), take the
 * draft's draft_version (or send 0 when there is no draft), and decide.
 * Branch on an error's errorCode(), never its localised message. The export
 * is read by hand: its body is canonical bytes never parsed here, its two
 * headers are part of the answer, and a 304 is an answer. There is no
 * delete: a dialogue is deleted through the platform's lifecycle routes, not
 * this library.
 */
final class Dialogues
{
    /**
     * Built by Client::dialogues() over the client's own request path: its
     * JSON call (operationId, args, model, query, headers, payload) →
     * ApiResponse, and its raw one (operationId, args, query, headers,
     * allowed statuses) → [response, body, served version].
     */
    public function __construct(
        private readonly \Closure $request,
        private readonly \Closure $exchange,
    ) {}

    /**
     * One page of dialogues; pageDialogues() walks them. $params holds
     * `cursor` (a previous page's next_cursor), `name` (a substring of the
     * dialogue name) and `limit` (1–50).
     *
     * @param array<string, mixed> $params
     *
     * @return ApiResponse<DialoguePage>
     */
    public function listDialogues(array $params = []): ApiResponse
    {
        return $this->call('listDialogues', [], DialoguePage::class, null, self::query($params, ['cursor', 'name', 'limit']));
    }

    /**
     * Creates a dialogue (201). Its `warnings` are advice, never a refusal.
     *
     * @return ApiResponse<Dialogue>
     */
    public function createDialogue(CreateDialogueRequest $body): ApiResponse
    {
        return $this->call('createDialogue', [], Dialogue::class, $body);
    }

    /**
     * One dialogue, its draft and active revision inline; either may be null.
     *
     * @return ApiResponse<Dialogue>
     */
    public function getDialogue(string $dialogueId): ApiResponse
    {
        return $this->call('getDialogue', [$dialogueId], Dialogue::class);
    }

    /**
     * Renames a dialogue (PATCH).
     *
     * @return ApiResponse<Dialogue>
     */
    public function renameDialogue(string $dialogueId, RenameDialogueRequest $body): ApiResponse
    {
        return $this->call('renameDialogue', [$dialogueId], Dialogue::class, $body);
    }

    /**
     * Saves the draft (PUT); `draft_version` 0 with no draft opens one. A
     * stale `draft_version` is 409 dialogue_conflict, which may follow your
     * own retried save: re-read with getDialogue() and compare before you
     * decide.
     *
     * @return ApiResponse<Dialogue>
     */
    public function saveDialogueDraft(string $dialogueId, SaveDialogueDraftRequest $body): ApiResponse
    {
        return $this->call('saveDialogueDraft', [$dialogueId], Dialogue::class, $body);
    }

    /**
     * Promotes the draft. A stale or missing draft is 409 dialogue_conflict
     * and writes nothing.
     *
     * @return ApiResponse<Dialogue>
     */
    public function activateDialogue(string $dialogueId, ActivateDialogueRequest $body): ApiResponse
    {
        return $this->call('activateDialogue', [$dialogueId], Dialogue::class, $body);
    }

    /**
     * Exports a dialogue (dialogues:read, which an embed token carries for
     * the active revision). `revision` is `active` (the server's default) or
     * `draft`; `layout` is `keep` (the default) or `omit`, which drops every
     * `x-` member and has its own ETag; each is sent only when given. Pass
     * the `etag` you hold as $ifNoneMatch, exactly as received, and an
     * unchanged revision answers NotModified with no body; a 304 still
     * spends one unit of the rate budget. A missing ETag, or a missing or
     * non-integer Lingara-Dialogue-Revision, is a TransportException
     * (malformed_response). K4's retries apply.
     */
    public function exportDialogue(string $dialogueId, ?string $revision = null, ?string $layout = null, ?string $ifNoneMatch = null): ExportedDialogue
    {
        $query = self::query(['revision' => $revision, 'layout' => $layout], ['revision', 'layout']);
        $headers = $ifNoneMatch === null ? [] : ['If-None-Match' => $ifNoneMatch];
        /** @var array{ResponseInterface, string, ?string} $answer */
        $answer = ($this->exchange)('exportDialogue', [$dialogueId], $query, $headers, [304]);
        [$response, $body, $served] = $answer;
        $number = $response->getHeaderLine('Lingara-Dialogue-Revision');
        $etag = $response->getHeaderLine('ETag');
        $parsed = ctype_digit($number) ? filter_var($number, FILTER_VALIDATE_INT) : false;
        if ($parsed === false || $etag === '') {
            throw new TransportException(TransportKind::MalformedResponse, 'an export lacks its ETag or Lingara-Dialogue-Revision');
        }
        return $response->getStatusCode() === 304
            ? new NotModified($parsed, $etag, $served)
            : new Body($parsed, $etag, $body, $served);
    }

    /**
     * Every dialogue the filters match (`name`, `limit`), following
     * next_cursor until a page says has_more false, the filters resent on
     * every page. It fetches lazily, never sleeps or polls, and stops on the
     * first error. A page with has_more and no next_cursor is a
     * TransportException (malformed_response), its items unyielded.
     *
     * @param array<string, mixed> $filters
     *
     * @return \Generator<int, DialogueSummary>
     */
    public function pageDialogues(array $filters = []): \Generator
    {
        unset($filters['cursor']);
        $cursor = null;
        do {
            $page = $this->listDialogues($filters + ['cursor' => $cursor])->value;
            $more = (bool) $page->getHasMore();
            if ($more && $page->getNextCursor() === null) {
                throw new TransportException(TransportKind::MalformedResponse, 'a dialogue page has more but no next_cursor');
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
     * A query of the $keys given in $params, in that order; an absent value
     * is left out.
     *
     * @param array<string, mixed> $params
     * @param list<string>         $keys
     *
     * @return array<string, string>
     */
    private static function query(array $params, array $keys): array
    {
        $query = [];
        foreach ($keys as $key) {
            $value = $params[$key] ?? null;
            if (is_scalar($value)) {
                $query[$key] = (string) $value;
            }
        }
        return $query;
    }
}
