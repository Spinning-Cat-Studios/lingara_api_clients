<?php

declare(strict_types=1);

namespace Lingara;

use GuzzleHttp\Client as GuzzleClient;
use GuzzleHttp\Psr7\HttpFactory;
use Lingara\Internal\Transport;
use Psr\Http\Client\ClientInterface;
use Psr\Http\Message\RequestFactoryInterface;
use Psr\Http\Message\StreamFactoryInterface;
use Symfony\Component\HttpClient\HttpClient;
use Symfony\Component\HttpClient\Psr18Client;

/**
 * Which HTTP client the library sends through, passed to Client as `http:`.
 * Its four named constructors are the only place the library names an HTTP
 * client, and neither Symfony nor Guzzle is a dependency: each is detected
 * with class_exists.
 *
 * PSR-18 does not say whether a response body streams, and has no
 * per-request options. So symfony() and guzzle() each build two clients
 * the library has proven to stream: a /v1 client whose inactivity bound is
 * `streamIdleTimeout`, and a token client whose total bound is
 * `tokenRequestTimeout`. custom() uses a caller's client for both endpoints
 * exactly as given, and the caller then owns streaming and both timeouts.
 */
final class HttpStack
{
    /** The longest an event waits in Guzzle's StreamHandler buffer (see buildGuzzle). */
    private const GUZZLE_READ_TIMEOUT = 0.1;

    private function __construct(
        private readonly string $name,
        private readonly ?ClientInterface $client = null,
        private readonly ?RequestFactoryInterface $requestFactory = null,
        private readonly ?StreamFactoryInterface $streamFactory = null,
    ) {}

    /**
     * symfony() when Symfony HttpClient and a PSR-17 implementation it
     * accepts are installed; else guzzle() when Guzzle 7 is installed and
     * allow_url_fopen is on.
     *
     * @throws \LogicException when neither is usable
     */
    public static function detect(): self
    {
        return self::detectFrom(self::symfonyInstalled(), class_exists(GuzzleClient::class), self::urlFopen());
    }

    /**
     * D3's detection order, from what is installed.
     *
     * @internal
     */
    public static function detectFrom(bool $symfony, bool $guzzle, bool $urlFopen): self
    {
        if ($symfony) {
            return new self('symfony');
        }
        if ($guzzle && $urlFopen) {
            return new self('guzzle');
        }
        throw new \LogicException(
            'Lingara needs an HTTP client that streams: install symfony/http-client with nyholm/psr7, '
            . 'or guzzlehttp/guzzle with the allow_url_fopen ini setting on, or pass HttpStack::custom().',
        );
    }

    /** @throws \LogicException when symfony/http-client or a PSR-17 implementation is missing */
    public static function symfony(): self
    {
        if (!self::symfonyInstalled()) {
            throw new \LogicException('HttpStack::symfony() needs symfony/http-client and a PSR-17 implementation such as nyholm/psr7');
        }
        return new self('symfony');
    }

    /** @throws \LogicException when Guzzle 7 is missing, or allow_url_fopen is off (Guzzle would buffer every stream) */
    public static function guzzle(): self
    {
        if (!class_exists(GuzzleClient::class)) {
            throw new \LogicException('HttpStack::guzzle() needs guzzlehttp/guzzle 7');
        }
        if (!self::urlFopen()) {
            throw new \LogicException('HttpStack::guzzle() needs the allow_url_fopen ini setting on: without it Guzzle buffers every stream');
        }
        return new self('guzzle');
    }

    /**
     * A caller's PSR-18 client and PSR-17 factories, for both endpoints. The
     * library cannot pre-empt a blocking sendRequest() or read(), so
     * streaming in real time, the idle timeout and the token request timeout
     * are then the client's to provide.
     */
    public static function custom(
        ClientInterface $client,
        RequestFactoryInterface $requestFactory,
        StreamFactoryInterface $streamFactory,
    ): self {
        return new self('custom', $client, $requestFactory, $streamFactory);
    }

    /** `symfony`, `guzzle` or `custom`. */
    public function name(): string
    {
        return $this->name;
    }

    /**
     * The clients this stack sends through, built for the two bounds.
     *
     * @internal
     */
    public function transport(float $streamIdleTimeout, float $tokenRequestTimeout): Transport
    {
        return match ($this->name) {
            'symfony' => self::buildSymfony($streamIdleTimeout, $tokenRequestTimeout),
            'guzzle' => self::buildGuzzle($streamIdleTimeout, $tokenRequestTimeout),
            default => new Transport($this->client(), $this->client(), $this->requestFactory(), $this->streamFactory(), false),
        };
    }

    private static function buildSymfony(float $idle, float $total): Transport
    {
        // `timeout` is Symfony's inactivity bound, which also bounds a JSON
        // call's wait for headers; `max_duration` bounds a whole transfer.
        $v1Options = ['max_redirects' => 0, 'timeout' => $idle];
        $tokenOptions = ['max_redirects' => 0, 'max_duration' => $total, 'timeout' => $total];
        $v1 = new Psr18Client(HttpClient::create($v1Options));
        $token = new Psr18Client(HttpClient::create($tokenOptions));
        return new Transport($v1, $token, $v1, $v1, true, $v1Options, $tokenOptions);
    }

    private static function buildGuzzle(float $idle, float $total): Transport
    {
        // `stream` routes every /v1 request through StreamHandler, where
        // `timeout` bounds the connect and the wait for headers. There a
        // read through PHP's dechunk filter is greedy: it returns only when
        // its buffer fills, the body ends, or `read_timeout` expires, so
        // `read_timeout` is how long an event can wait in the buffer, not the
        // idle bound. It is kept short, a timed-out read is "no data yet",
        // and the library's own deadline decides the idle timeout (D3,
        // measured against Guzzle 7.15). The token client stays on curl,
        // where `timeout` is the whole transfer.
        $v1Options = [
            'allow_redirects' => false, 'http_errors' => false, 'stream' => true,
            'read_timeout' => min(self::GUZZLE_READ_TIMEOUT, $idle), 'timeout' => $idle,
        ];
        $tokenOptions = ['allow_redirects' => false, 'http_errors' => false, 'timeout' => $total];
        $factory = new HttpFactory();
        return new Transport(new GuzzleClient($v1Options), new GuzzleClient($tokenOptions), $factory, $factory, true, $v1Options, $tokenOptions);
    }

    private static function symfonyInstalled(): bool
    {
        return class_exists(Psr18Client::class)
            && (class_exists(\Nyholm\Psr7\Factory\Psr17Factory::class) || class_exists(HttpFactory::class));
    }

    private static function urlFopen(): bool
    {
        return filter_var(ini_get('allow_url_fopen'), FILTER_VALIDATE_BOOL);
    }

    private function client(): ClientInterface
    {
        return $this->client ?? throw new \LogicException('HttpStack::custom() has no client');
    }

    private function requestFactory(): RequestFactoryInterface
    {
        return $this->requestFactory ?? throw new \LogicException('HttpStack::custom() has no request factory');
    }

    private function streamFactory(): StreamFactoryInterface
    {
        return $this->streamFactory ?? throw new \LogicException('HttpStack::custom() has no stream factory');
    }
}
