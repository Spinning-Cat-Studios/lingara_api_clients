<?php

declare(strict_types=1);

namespace Lingara\Internal;

use Lingara\ApiResponse;
use Lingara\Exception\TransportException;
use Lingara\Exception\TransportKind;
use Lingara\MintedToken;
use Lingara\Model\DialoguePlanRequest;
use Lingara\Model\DialoguePlanResponseOneOf;
use Lingara\Model\DialoguePlanResponseOneOf1;
use Lingara\Model\EmbedPlayerLink;
use Lingara\Model\EmbedToken;
use Lingara\Model\EmbedTokenRequest;
use Lingara\ObjectSerializer;

/**
 * The embedding operations that are not streams (ADR 1.10.26w, 22.10.26t),
 * kept out of Client.php so the client stays under its line budget (ADR
 * 22.10.26t D5). A trait rather than a helper class: the methods stay on
 * Client, which ClientTest reflects, and keep Client's private json() and
 * noContent(). sendDialogueTurn is a stream and stays on Client.
 *
 * @internal
 */
trait EmbedCalls
{
    /**
     * Mints a player's embed token (scope embed:mint; a metered client only,
     * else 403 embed_needs_metered). Call it on your server, never on a
     * player's device. Every call mints: nothing is cached, and K4 retries a
     * 429 or 503 with no Idempotency-Key, since two tokens are harmless.
     *
     * The answer is decoded raw and its notice removed before the typed
     * decode, so a malformed notice is dropped rather than failing the mint
     * (ADR 31.10.26g D3).
     *
     * @return ApiResponse<MintedToken>
     */
    public function createEmbedToken(EmbedTokenRequest $request): ApiResponse
    {
        $payload = json_encode(ObjectSerializer::sanitizeForSerialization($request), JSON_THROW_ON_ERROR);
        $answer = $this->json('createEmbedToken', [], \stdClass::class, [], [], $payload);
        $raw = $answer->value;
        $notice = MintedToken::updateFrom($raw->update_available ?? null);
        unset($raw->update_available);
        return new ApiResponse(MintedToken::fromAnswer(self::typed($raw, EmbedToken::class), $notice), $answer->servedVersion);
    }

    /**
     * Plans the next beats of a Procedural dialogue (scope embed:play, a
     * metered payer only; ADR 3.11.26n). The value is a
     * DialoguePlanResponseOneOf (status `planned`, a typed fragment) or a
     * DialoguePlanResponseOneOf1 (status `kept`, a reason): a keep is a
     * result, never an error. The document is sent as given and nothing is
     * validated here. Never retried, as the turn is not: each call spends
     * one embed:play unit, a keep and every refusal after admission
     * included, so a 429 or 503 is thrown at once with its retryAfter.
     *
     * The generated DialoguePlanResponse merges the two arms and rewrites
     * `planned` to an unknown status, so the answer is decoded raw and its
     * arm picked by `status`.
     *
     * @return ApiResponse<DialoguePlanResponseOneOf|DialoguePlanResponseOneOf1>
     */
    public function planDialogue(DialoguePlanRequest $request): ApiResponse
    {
        $payload = json_encode(ObjectSerializer::sanitizeForSerialization($request), JSON_THROW_ON_ERROR);
        $answer = $this->json('planDialogue', [], \stdClass::class, [], [], $payload, false);
        $arm = ($answer->value->status ?? null) === 'kept' ? DialoguePlanResponseOneOf1::class : DialoguePlanResponseOneOf::class;
        return new ApiResponse(self::typed($answer->value, $arm), $answer->servedVersion);
    }

    /**
     * A raw answer as the generated $class, or MalformedResponse carrying
     * none of it.
     *
     * @template T of object
     *
     * @param class-string<T> $class
     *
     * @return T
     */
    private static function typed(\stdClass $raw, string $class): object
    {
        try {
            $model = ObjectSerializer::deserialize($raw, $class);
        } catch (\Throwable) {
            $model = null;
        }
        if (!$model instanceof $class) {
            throw new TransportException(TransportKind::MalformedResponse, 'the response body does not decode');
        }
        return $model;
    }

    /**
     * Deletes a player and revokes its tokens (scope embed:mint). An unknown
     * player is a success too, so a retry is safe, and it works while embed
     * is dark. The value is an empty \stdClass.
     *
     * @return ApiResponse<\stdClass>
     */
    public function deleteEmbedPlayer(string $playerRef): ApiResponse
    {
        return $this->noContent('deleteEmbedPlayer', [$playerRef]);
    }

    /**
     * Links a player to the Lingara account that granted your app access
     * (scope embed:play). Call it on a client whose tokenSource returns the
     * player's delegated token, from that account's own consent: a
     * client-credentials client is refused 403 embed_link_needs_grant. The
     * library does not obtain that token yet. A repeat answers `existing`
     * and moves what could not move before, so a retry is safe: call again
     * while `kept` has `lesson_plans`. It sends no body.
     *
     * @return ApiResponse<EmbedPlayerLink>
     */
    public function linkEmbedPlayer(string $playerRef): ApiResponse
    {
        return $this->json('linkEmbedPlayer', [$playerRef], EmbedPlayerLink::class);
    }

    /**
     * Ends a player's link (scope embed:mint, on your ordinary client). No
     * live link is 404 embed_link_not_found, which after a retried success
     * means it was already unlinked. The value is an empty \stdClass.
     *
     * @return ApiResponse<\stdClass>
     */
    public function unlinkEmbedPlayer(string $playerRef): ApiResponse
    {
        return $this->noContent('unlinkEmbedPlayer', [$playerRef]);
    }
}
