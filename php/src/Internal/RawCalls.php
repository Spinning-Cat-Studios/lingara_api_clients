<?php

declare(strict_types=1);

namespace Lingara\Internal;

use Psr\Http\Message\ResponseInterface;

/**
 * The one call whose answer is its response rather than a model (ADR
 * 29.10.26c D6): exportDialogue() reads two headers and the bytes as sent,
 * and reads a 304 itself. Kept out of Client.php so the client stays under
 * its line budget (ADR 22.10.26t D5's EmbedCalls precedent); a trait, so it
 * keeps Client's private send(), url() and transport().
 *
 * @internal
 */
trait RawCalls
{
    /**
     * One GET as its response, its whole body (empty for a 304) and the
     * version echo. Sent like any call (token, K1, K4's retries, the
     * refusal mapping), but a status in $allow is returned, not thrown.
     *
     * @param list<string>          $args
     * @param array<string, string> $query
     * @param array<string, string> $headers
     * @param list<int>             $allow
     *
     * @return array{ResponseInterface, string, ?string}
     */
    private function exchange(string $operationId, array $args, array $query, array $headers, array $allow): array
    {
        $operation = Operations::OPERATIONS[$operationId];
        $url = $this->url($operation['path'], $operation['pathParams'], $args, $query);
        [$response, $token] = $this->send($operationId, $url, null, $headers, true, $allow);
        $served = $this->deprecations->observe($response, $url);
        $body = $this->transport()->readAll($response->getBody(), $this->streamIdleTimeout, self::secrets($token));
        return [$response, $body, $served];
    }
}
