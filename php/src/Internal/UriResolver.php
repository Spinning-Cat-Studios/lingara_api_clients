<?php

declare(strict_types=1);

namespace Lingara\Internal;

/**
 * RFC 3986 §5.2 reference resolution, for a Link target (RFC 8288 §3.2):
 * the deprecation Link is a relative reference such as `</v1/versions/<id>>`.
 *
 * @internal
 */
final class UriResolver
{
    /** $reference resolved against the absolute URL $base, or null when $base is not absolute. */
    public static function resolve(string $base, string $reference): ?string
    {
        $b = parse_url($base);
        if ($b === false || !isset($b['scheme'], $b['host'])) {
            return null;
        }
        if (preg_match('/\A[A-Za-z][A-Za-z0-9+.-]*:/', $reference) === 1) {
            return $reference;
        }
        $authority = $b['host'] . (isset($b['port']) ? ":{$b['port']}" : '');
        if (str_starts_with($reference, '//')) {
            return "{$b['scheme']}:{$reference}";
        }
        [$path, $suffix] = self::split($reference);
        $basePath = $b['path'] ?? '';
        if ($path === '') {
            $path = $basePath;
            if ($suffix === '' || $suffix[0] === '#') {
                $suffix = (isset($b['query']) ? "?{$b['query']}" : '') . $suffix;
            }
        }
        return "{$b['scheme']}://{$authority}" . self::removeDotSegments(self::merge($basePath, $path)) . $suffix;
    }

    /** A relative path joined to the base path's directory (RFC 3986 §5.2.3). */
    private static function merge(string $basePath, string $path): string
    {
        if ($path === '' || $path[0] === '/') {
            return $path;
        }
        $dir = substr($basePath, 0, (int) strrpos($basePath, '/') + 1);
        return ($dir === '' ? '/' : $dir) . $path;
    }

    /** @return array{string, string} the path, and the query and fragment after it */
    private static function split(string $reference): array
    {
        $at = strcspn($reference, '?#');
        return [substr($reference, 0, $at), substr($reference, $at)];
    }

    private static function removeDotSegments(string $path): string
    {
        $out = [];
        foreach (explode('/', $path) as $segment) {
            if ($segment === '..') {
                array_pop($out);
            } elseif ($segment !== '.') {
                $out[] = $segment;
            }
        }
        $result = implode('/', $out);
        if (str_ends_with($path, '/.') || str_ends_with($path, '/..')) {
            $result .= '/';
        }
        return str_starts_with($result, '/') ? $result : "/{$result}";
    }
}
