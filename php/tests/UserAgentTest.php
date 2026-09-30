<?php

declare(strict_types=1);

namespace Lingara\Tests;

use Lingara\Client;
use Lingara\Internal\UserAgent;
use Lingara\Tests\Support\FakeHttpClient;
use PHPUnit\Framework\TestCase;

final class UserAgentTest extends TestCase
{
    /** CONTRACT.md K6's pattern, with `<lang>` narrowed to php. */
    private const PATTERN = '/^lingara-php\/(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z.-]+)? \([\x20-\x28\x2A-\x7E]+\)( .+)?$/';

    /**
     * 29.9.26u AC21: the User-Agent matches C2 D8's pattern on both the token
     * request and a /v1 request, including for a pre-release VERSION and a
     * runtime string holding `)`, and a suffix is appended after it.
     */
    public function testUserAgentShapeAndSuffix(): void
    {
        $fake = new FakeHttpClient(FakeHttpClient::token(), FakeHttpClient::json(200, ['allowance' => []]));
        $client = new Client(clientId: 'lgr_cid_x', clientSecret: 'lgr_cs_x', userAgentSuffix: 'kanji-quest/2.1', http: $fake->stack());
        $client->getUsage();
        self::assertCount(2, $fake->requests);
        foreach ($fake->requests as $request) {
            $agent = $request->getHeaderLine('User-Agent');
            self::assertMatchesRegularExpression(self::PATTERN, $agent);
            self::assertStringEndsWith(') kanji-quest/2.1', $agent);
        }

        self::assertMatchesRegularExpression(self::PATTERN, UserAgent::build(null, '0.1.0-alpha.1'));
        self::assertSame('lingara-php/0.1.0 (php/unknown)', UserAgent::build(null, '0.1.0', 'php/8.5 (odd)'));
        self::assertSame('lingara-php/0.1.0 (php/unknown)', UserAgent::build(null, '0.1.0', "php/8.5\r\n"));
        self::assertSame('lingara-php/0.1.0 (php/8.5.0; Linux)', UserAgent::build('', '0.1.0', 'php/8.5.0; Linux'));
    }
}
