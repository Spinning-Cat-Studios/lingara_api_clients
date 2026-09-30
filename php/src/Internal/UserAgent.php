<?php

declare(strict_types=1);

namespace Lingara\Internal;

use Lingara\Version;

/**
 * K6: `lingara-php/<VERSION> (php/<PHP_VERSION>; <PHP_OS_FAMILY>)`, the
 * library's token first and a caller's suffix after one space.
 *
 * @internal
 */
final class UserAgent
{
    /** Visible ASCII with no `)`: CONTRACT.md K6's <runtime>. */
    private const RUNTIME = '/\A[\x20-\x28\x2A-\x7E]+\z/';

    public static function build(
        ?string $suffix = null,
        string $version = Version::VERSION,
        string $runtime = 'php/' . PHP_VERSION . '; ' . PHP_OS_FAMILY,
    ): string {
        if (preg_match(self::RUNTIME, $runtime) !== 1) {
            $runtime = 'php/unknown';
        }
        $agent = "lingara-php/{$version} ({$runtime})";
        return $suffix === null || $suffix === '' ? $agent : "{$agent} {$suffix}";
    }
}
