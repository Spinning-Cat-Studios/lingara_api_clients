<?php

declare(strict_types=1);

namespace Lingara;

use Lingara\Internal\Secrets;

/**
 * An opaque access token, as a token source returns it (K1). The raw value
 * lives in Lingara\Internal\Secrets, never in a property, so no dump form
 * reaches it, and exposeSecret() is the one way to read it. A caller's own
 * token source builds one with `new AccessToken($raw)`.
 */
final class AccessToken
{
    private readonly object $handle;

    public function __construct(#[\SensitiveParameter] string $raw)
    {
        if ($raw === '') {
            throw new \InvalidArgumentException('an access token is a non-empty string');
        }
        $this->handle = Secrets::handle();
        Secrets::put($this->handle, 'token', $raw);
    }

    /** The raw token: the one accessor that does not redact. */
    public function exposeSecret(): string
    {
        return Secrets::string($this->handle, 'token');
    }

    public function equals(self $other): bool
    {
        return hash_equals($this->exposeSecret(), $other->exposeSecret());
    }

    /** @return array<string, string> */
    public function __debugInfo(): array
    {
        return ['token' => '[REDACTED]'];
    }

    /** @return array<string, mixed> */
    public function __serialize(): array
    {
        throw new \LogicException('an access token cannot be serialized');
    }

    /** @param array<mixed> $data */
    public function __unserialize(array $data): void
    {
        throw new \LogicException('an access token cannot be unserialized');
    }
}
