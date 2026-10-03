<?php

declare(strict_types=1);

namespace Lingara\Tests;

use GuzzleHttp\Exception\ConnectException;
use Lingara\Client;
use Lingara\Exception\TransportException;
use Lingara\Exception\TransportKind;
use Lingara\HttpStack;
use Lingara\Internal\ErrorMapper;
use Lingara\Tests\Support\ScriptedServer;
use Nyholm\Psr7\Request;
use PHPUnit\Framework\Attributes\DataProvider;
use Psr\Http\Client\NetworkExceptionInterface;
use Psr\Http\Message\RequestInterface;
use Psr\Log\NullLogger;

final class TransportKindsTest extends StacksTestCase
{
    /**
     * 29.9.26u AC14: on each transport path a refused port and a reported
     * connect timeout map to Connect; on both built stacks a listener that
     * never sends headers maps to Timeout, a listener answering the
     * ClientHello with garbage to Tls, a body cut mid-read to Reset, and a
     * token endpoint stalling past tokenRequestTimeout, after its headers or
     * mid-body, to Timeout; and a PSR-18 exception carrying the request is
     * never reachable through getPrevious().
     */
    #[DataProvider('stacks')]
    public function testFailuresMapToTheirKindsOnBothStacks(HttpStack $http): void
    {
        $refused = self::closedPort();
        $failure = self::kindOf(static fn() => (new Client(baseUrl: $refused, http: $http))->getOpenApiDocument());
        self::assertSame(TransportKind::Connect, $failure->kind(), 'a refused /v1 connect');
        self::assertNotInstanceOf(NetworkExceptionInterface::class, $failure->getPrevious());
        self::assertNull($failure->getPrevious()?->getPrevious());
        $token = self::kindOf(static fn() => self::withToken($http, $refused)->getUsage());
        self::assertSame(TransportKind::Connect, $token->kind(), 'a refused token connect');

        $cut = ScriptedServer::json(200, str_repeat('x', 100));
        $server = new ScriptedServer([
            ['write' => [['sleep' => 1.0]], 'then' => 'close'],
            ['then' => 'garbage'],
            ['write' => [substr($cut, 0, -90)], 'then' => 'reset'],
            ['write' => [ScriptedServer::sseHead(), self::started()], 'then' => 'reset'],
            // The two token stalls outlast the 0.5 s token timeout by 2.5 s: at
            // 1.0 s a slow runner saw the close before the timeout, as Reset.
            ['write' => ["HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 90\r\n\r\n", ['sleep' => 3.0]], 'then' => 'close'],
            ['write' => ["HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 90\r\n\r\n{\"access_", ['sleep' => 3.0]], 'then' => 'close'],
        ]);
        $client = self::client($http, $server);
        self::assertSame(TransportKind::Timeout, self::kindOf(static fn() => $client->getOpenApiDocument())->kind(), 'no headers');
        // Guzzle 7.15's StreamHandler replaces every failed fopen() with its
        // own "Connection refused for URI" and drops PHP's TLS warning, so on
        // that one path a TLS failure is Connect (As built). The token client
        // is on curl, whose TLS errno survives.
        $tls = new Client(baseUrl: str_replace('http://', 'https://', $server->url), http: $http);
        $expected = $http->name() === 'guzzle' ? TransportKind::Connect : TransportKind::Tls;
        self::assertSame($expected, self::kindOf(static fn() => $tls->getOpenApiDocument())->kind(), 'garbage for a ClientHello');
        self::assertSame(TransportKind::Reset, self::kindOf(static fn() => $client->getOpenApiDocument())->kind(), 'a JSON body cut');
        self::assertSame(TransportKind::Reset, self::kindOf(static fn() => iterator_to_array($client->generateVocabulary(self::vocab())))->kind(), 'a stream cut');
        $stalled = self::withToken($http, $server->url, 0.5);
        self::assertSame(TransportKind::Timeout, self::kindOf(static fn() => $stalled->getUsage())->kind(), 'a token body that never starts');
        $stalled = self::withToken($http, $server->url, 0.5);
        self::assertSame(TransportKind::Timeout, self::kindOf(static fn() => $stalled->getUsage())->kind(), 'a token body stalled mid-way');
        $server->stop();

        $garbage = new ScriptedServer([['then' => 'garbage']]);
        $tlsToken = self::withToken($http, str_replace('http://', 'https://', $garbage->url));
        self::assertSame(TransportKind::Tls, self::kindOf(static fn() => $tlsToken->getUsage())->kind(), 'garbage for the token ClientHello');
        $garbage->stop();
    }

    /**
     * 29.9.26u AC14: a connect timeout, which loopback cannot provoke
     * reliably, as each path reports one: curl's errno through Guzzle's
     * handler context, curl's text through Symfony, and the OS text through
     * StreamHandler, each well past the bound.
     */
    public function testReportedConnectTimeoutsAreConnectOnEveryPath(): void
    {
        $request = new Request('GET', 'http://192.0.2.1/v1/usage');
        $reports = [
            'guzzle curl' => new ConnectException('cURL error 28: Connection timed out after 500 milliseconds', $request, null, ['errno' => 28, 'connect_time' => 0.0]),
            'symfony' => self::network('Connection timed out after 500 milliseconds for "http://192.0.2.1/v1/usage".', $request),
            'streamhandler macOS' => self::network('Operation timed out', $request),
            'streamhandler Linux' => self::network('Connection timed out', $request),
        ];
        foreach ($reports as $path => $report) {
            self::assertSame(TransportKind::Connect, ErrorMapper::sendKind($report, 2.0, 0.5), $path);
        }
        $transfer = new ConnectException('cURL error 28: Operation timed out after 500 milliseconds', $request, null, ['errno' => 28, 'connect_time' => 0.01]);
        self::assertSame(TransportKind::Timeout, ErrorMapper::sendKind($transfer, 0.5, 0.5), 'a transfer timeout once connected');
        self::assertSame(TransportKind::Timeout, ErrorMapper::sendKind(self::network('Connection refused', $request), 0.6, 0.5), 'an expired header wait');
    }

    private static function kindOf(\Closure $call): TransportException
    {
        try {
            $call();
        } catch (TransportException $e) {
            return $e;
        }
        self::fail('no TransportException');
    }

    private static function withToken(HttpStack $http, string $base, float $tokenTimeout = 30.0): Client
    {
        return new Client(
            clientId: 'lgr_cid_x',
            clientSecret: 'lgr_cs_x',
            baseUrl: $base,
            tokenUrl: $base . '/oauth/token',
            logger: new NullLogger(),
            tokenRequestTimeout: $tokenTimeout,
            http: $http,
        );
    }

    private static function closedPort(): string
    {
        $server = stream_socket_server('tcp://127.0.0.1:0');
        self::assertIsResource($server);
        $name = (string) stream_socket_get_name($server, false);
        fclose($server);
        return "http://{$name}";
    }

    private static function network(string $message, RequestInterface $request): NetworkExceptionInterface
    {
        return new class ($message, $request) extends \RuntimeException implements NetworkExceptionInterface {
            public function __construct(string $message, private readonly RequestInterface $request)
            {
                parent::__construct($message);
            }

            public function getRequest(): RequestInterface
            {
                return $this->request;
            }
        };
    }
}
