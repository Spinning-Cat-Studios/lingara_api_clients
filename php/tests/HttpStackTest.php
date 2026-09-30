<?php

declare(strict_types=1);

namespace Lingara\Tests;

use GuzzleHttp\Client as GuzzleClient;
use Lingara\HttpStack;
use PHPUnit\Framework\TestCase;
use Symfony\Component\HttpClient\Psr18Client;

final class HttpStackTest extends TestCase
{
    /**
     * 29.9.26u AC25: detect() picks Symfony when both are installed and
     * Guzzle when only it is; with allow_url_fopen off it skips Guzzle and
     * guzzle() throws \LogicException; with neither usable detect() throws a
     * \LogicException naming both packages, the ini setting and custom();
     * symfony() and guzzle() each build a /v1 client with the idle bound and
     * no redirects (Guzzle's with stream on) and a token client with the
     * total bound.
     */
    public function testDetectionOrderAndBuiltClientOptions(): void
    {
        self::assertSame('symfony', HttpStack::detect()->name(), 'both are installed as require-dev');
        self::assertSame('symfony', HttpStack::detectFrom(true, true, true)->name());
        self::assertSame('guzzle', HttpStack::detectFrom(false, true, true)->name());
        try {
            HttpStack::detectFrom(false, true, false);
            self::fail('Guzzle without allow_url_fopen was detected');
        } catch (\LogicException $e) {
            foreach (['symfony/http-client', 'guzzlehttp/guzzle', 'allow_url_fopen', 'HttpStack::custom()'] as $name) {
                self::assertStringContainsString($name, $e->getMessage());
            }
        }

        // allow_url_fopen is INI_SYSTEM: only a new process can turn it off.
        $script = 'require "' . __DIR__ . '/../vendor/autoload.php";'
            . 'try { Lingara\HttpStack::guzzle(); echo "built"; } catch (LogicException $e) { echo "refused"; }';
        $out = shell_exec(escapeshellarg(PHP_BINARY) . ' -d allow_url_fopen=0 -r ' . escapeshellarg($script));
        self::assertSame('refused', $out);

        $symfony = HttpStack::symfony()->transport(0.5, 7.0);
        self::assertTrue($symfony->built);
        self::assertInstanceOf(Psr18Client::class, $symfony->v1Client());
        self::assertSame(['max_redirects' => 0, 'timeout' => 0.5], $symfony->v1Options);
        self::assertSame(['max_redirects' => 0, 'max_duration' => 7.0, 'timeout' => 7.0], $symfony->tokenOptions);

        $guzzle = HttpStack::guzzle()->transport(0.5, 7.0);
        self::assertInstanceOf(GuzzleClient::class, $guzzle->v1Client());
        self::assertSame(
            ['allow_redirects' => false, 'http_errors' => false, 'stream' => true, 'read_timeout' => 0.1, 'timeout' => 0.5],
            $guzzle->v1Options,
        );
        self::assertSame(['allow_redirects' => false, 'http_errors' => false, 'timeout' => 7.0], $guzzle->tokenOptions);
        self::assertNotSame($guzzle->v1Client(), $guzzle->tokenClient());
    }
}
