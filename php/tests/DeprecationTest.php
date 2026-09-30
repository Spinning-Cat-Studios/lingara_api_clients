<?php

declare(strict_types=1);

namespace Lingara\Tests;

use Lingara\Client;
use Lingara\DeprecationNotice;
use Lingara\Tests\Support\FakeHttpClient;
use Lingara\Tests\Support\RecordingLogger;
use PHPUnit\Framework\TestCase;

final class DeprecationTest extends TestCase
{
    private const VERSION = '2026-09-affable-cat';

    /**
     * 29.9.26u AC26: a Deprecation header calls the hook once with parsed
     * times and a resolved link; an unparseable header leaves its field null;
     * a throwing hook does not fail the call and reaches logger->debug();
     * with no hook one warning per version id reaches the logger, or
     * error_log() by default, and an error handler that throws on every
     * error does not fail the call.
     */
    public function testDeprecationHookParsingAndWarnOnce(): void
    {
        $notices = [];
        $client = self::client(static function (DeprecationNotice $notice) use (&$notices): void {
            $notices[] = $notice;
        }, self::recorder(), self::deprecated('@1790812800', 'Mon, 01 Mar 2027 00:00:00 GMT'), self::deprecated('last Tuesday', 'soon'));
        $client->getOpenApiDocument();
        $client->getOpenApiDocument();
        self::assertCount(2, $notices);
        [$parsed, $raw] = $notices;
        self::assertSame([self::VERSION, 1790812800, 1803859200], [$parsed->version, $parsed->deprecatedAt?->getTimestamp(), $parsed->sunsetAt?->getTimestamp()]);
        self::assertSame('https://api.getlingara.com/v1/versions/' . self::VERSION, $parsed->linkTarget);
        self::assertSame('</v1/versions/' . self::VERSION . '>; rel="deprecation"', $parsed->linkRaw);
        self::assertNull($raw->deprecatedAt);
        self::assertNull($raw->sunsetAt);
        self::assertSame('last Tuesday', $raw->headers['Deprecation']);

        $logger = self::recorder();
        $throwing = self::client(static function (): void {
            throw new \RuntimeException('the hook broke');
        }, $logger, self::deprecated('@1790812800', null));
        $throwing->getOpenApiDocument();
        self::assertStringContainsString('hook threw RuntimeException', implode("\n", $logger->lines['debug'] ?? []));

        $logger = self::recorder();
        $plain = self::client(null, $logger, self::deprecated('@1790812800', null), self::deprecated('@1790812800', null), self::deprecated('@1790812800', null, '2026-09-other-cat'));
        $plain->getOpenApiDocument();
        $plain->getOpenApiDocument();
        $plain->getOpenApiDocument();
        $deprecated = array_filter($logger->lines['warning'] ?? [], static fn(string $l): bool => str_contains($l, 'is deprecated'));
        self::assertCount(2, $deprecated, 'one warning per version id');

        $log = (string) tempnam(sys_get_temp_dir(), 'lgr-log');
        $previous = ini_set('error_log', $log);
        set_error_handler(static function (int $level, string $message): never {
            throw new \ErrorException($message, 0, $level);
        });
        try {
            self::client(null, null, self::deprecated('@1790812800', null))->getOpenApiDocument();
        } finally {
            restore_error_handler();
            ini_set('error_log', (string) $previous);
        }
        self::assertStringContainsString('Lingara API version ' . self::VERSION . ' is deprecated', (string) file_get_contents($log));
        unlink($log);
    }

    private static function deprecated(string $deprecation, ?string $sunset, string $version = self::VERSION): \Psr\Http\Message\ResponseInterface
    {
        $headers = ['Lingara-Version' => $version, 'Deprecation' => $deprecation, 'Link' => "</v1/versions/{$version}>; rel=\"deprecation\""];
        if ($sunset !== null) {
            $headers['Sunset'] = $sunset;
        }
        return FakeHttpClient::json(200, ['openapi' => '3.2.0'], $headers);
    }

    private static function client(?\Closure $hook, ?RecordingLogger $logger, \Psr\Http\Message\ResponseInterface ...$responses): Client
    {
        return new Client(onDeprecation: $hook, logger: $logger, http: (new FakeHttpClient(...$responses))->stack());
    }

    private static function recorder(): RecordingLogger
    {
        return new RecordingLogger();
    }
}
