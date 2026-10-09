<?php

declare(strict_types=1);

namespace Lingara;

use Lingara\Exception\TransportException;
use Lingara\Exception\TransportKind;
use Lingara\Model\EmbedToken;

/**
 * A player's embed token, as createEmbedToken() mints it (ADR 1.10.26w D3).
 * The `lgr_et_` value is an access token under K1, so it is held as an
 * AccessToken and read only through `$minted->token->exposeSecret()`:
 * var_dump, print_r and serialize all render it `[REDACTED]`, and
 * json_encode and var_export reach only AccessToken's empty handle.
 *
 * The token lives `expiresIn` seconds (900) and Lingara never refreshes it:
 * mint again. `expiresAt` is the server's RFC 3339 string; a device whose
 * clock cannot be trusted counts down from `expiresIn` instead. `subject` is
 * the player's `lgr_sub_`, the same on every mint: store it beside the
 * player, since every event and webhook names the player by it.
 *
 * Lives at Lingara\MintedToken, never under Lingara\Embed\, which is the
 * spinningcatstudios/lingara-embed package's namespace.
 */
final readonly class MintedToken
{
    private const PREFIX = 'lgr_et_';

    /**
     * fromAnswer() always passes all six. accountLinked's default, the value
     * every mint answers until account linking ships, keeps the constructor
     * within BudgetsTest's five required parameters.
     *
     * @param list<string> $scopes
     */
    private function __construct(
        public AccessToken $token,
        public string $expiresAt,
        public int $expiresIn,
        public string $subject,
        public array $scopes,
        public bool $accountLinked = false,
    ) {}

    /**
     * Builds the token from the decoded answer, checking all six fields.
     * PHP's ObjectSerializer accepts a missing one, and its setters have
     * already coerced each present one to its declared type, so a missing
     * field is the generated getter's TypeError (its return type is not
     * nullable). A failure, or a token without the `lgr_et_` prefix, is
     * MalformedResponse and carries none of the body: no cause is chained,
     * and the answer is a sensitive parameter, so no trace carries it either.
     *
     * @throws TransportException
     *
     * @internal
     */
    public static function fromAnswer(#[\SensitiveParameter] EmbedToken $answer): self
    {
        try {
            $minted = new self(
                new AccessToken($answer->getToken()),
                $answer->getExpiresAt(),
                $answer->getExpiresIn(),
                $answer->getSubject(),
                array_values($answer->getScopes()),
                $answer->getAccountLinked(),
            );
        } catch (\TypeError|\InvalidArgumentException) {
            $minted = null;
        }
        if ($minted === null || !str_starts_with($minted->token->exposeSecret(), self::PREFIX)) {
            throw new TransportException(TransportKind::MalformedResponse, 'the embed token answer lacks a field or its lgr_et_ token');
        }
        return $minted;
    }

    /** @return array<string, mixed> */
    public function __debugInfo(): array
    {
        return $this->redacted();
    }

    /** @return array<string, mixed> */
    public function __serialize(): array
    {
        return $this->redacted();
    }

    /** @param array<mixed> $data */
    public function __unserialize(array $data): void
    {
        throw new \LogicException('a minted token cannot be unserialized');
    }

    /** @return array<string, mixed> */
    private function redacted(): array
    {
        return [
            'token' => '[REDACTED]',
            'expiresAt' => $this->expiresAt,
            'expiresIn' => $this->expiresIn,
            'subject' => $this->subject,
            'scopes' => $this->scopes,
            'accountLinked' => $this->accountLinked,
        ];
    }
}
