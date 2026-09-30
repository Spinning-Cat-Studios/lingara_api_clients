<?php

declare(strict_types=1);

namespace Lingara\Tests;

use PHPUnit\Framework\TestCase;

final class ComposerJsonTest extends TestCase
{
    /**
     * 29.9.26u AC1: composer.json's `require` names only `php` and the six
     * PSR interface packages, and it has no `version` field.
     */
    public function testRequireIsInterfacesOnlyAndThereIsNoVersion(): void
    {
        $manifest = json_decode((string) file_get_contents(__DIR__ . '/../composer.json'), true, 512, JSON_THROW_ON_ERROR);
        self::assertIsArray($manifest);
        self::assertIsArray($manifest['require']);
        $require = array_keys($manifest['require']);
        sort($require);
        self::assertSame([
            'php', 'psr/clock', 'psr/http-client', 'psr/http-factory', 'psr/http-message', 'psr/log', 'psr/simple-cache',
        ], $require);
        self::assertArrayNotHasKey('version', $manifest);
        self::assertSame('spinningcatstudios/lingara', $manifest['name']);
        self::assertSame('MIT', $manifest['license']);
    }
}
