<?php

declare(strict_types=1);

namespace Lingara\Tests\Support;

use Lingara\HttpStack;
use Nyholm\Psr7\Factory\Psr17Factory;
use Nyholm\Psr7\Response;
use Psr\Http\Client\ClientInterface;
use Psr\Http\Message\RequestInterface;
use Psr\Http\Message\ResponseInterface;

/**
 * The in-memory PSR-18 fake most tests run against: it answers each request
 * from a queue of responses (or callables building one) and records every
 * request it was sent.
 */
final class FakeHttpClient implements ClientInterface
{
    /** @var list<RequestInterface> */
    public array $requests = [];

    /** @var list<ResponseInterface|\Throwable|\Closure(RequestInterface): ResponseInterface> */
    private array $queue = [];

    /** @param ResponseInterface|\Throwable|\Closure(RequestInterface): ResponseInterface ...$answers */
    public function __construct(ResponseInterface|\Throwable|\Closure ...$answers)
    {
        $this->queue = array_values($answers);
    }

    public function stack(): HttpStack
    {
        $factory = new Psr17Factory();
        return HttpStack::custom($this, $factory, $factory);
    }

    public function push(ResponseInterface|\Throwable|\Closure ...$answers): void
    {
        array_push($this->queue, ...$answers);
    }

    public function sendRequest(RequestInterface $request): ResponseInterface
    {
        $this->requests[] = $request;
        $answer = array_shift($this->queue) ?? throw new \LogicException('FakeHttpClient: no answer queued for ' . $request->getUri());
        if ($answer instanceof \Throwable) {
            throw $answer;
        }
        return $answer instanceof \Closure ? $answer($request) : $answer;
    }

    /** @param array<string, string> $headers */
    public static function json(int $status, mixed $body, array $headers = []): ResponseInterface
    {
        $text = is_string($body) ? $body : json_encode($body, JSON_THROW_ON_ERROR);
        return new Response($status, ['Content-Type' => 'application/json'] + $headers, $text);
    }

    /** @param array<string, string> $headers */
    public static function sse(string $body, array $headers = []): ResponseInterface
    {
        return new Response(200, ['Content-Type' => 'text/event-stream'] + $headers, $body);
    }

    public static function token(string $token = 'lgr_at_fake', int $expiresIn = 3600): ResponseInterface
    {
        return self::json(200, ['access_token' => $token, 'token_type' => 'Bearer', 'expires_in' => $expiresIn]);
    }
}
