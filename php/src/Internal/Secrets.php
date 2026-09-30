<?php

declare(strict_types=1);

namespace Lingara\Internal;

/**
 * Where every client secret and access token lives (K1, redaction): a
 * private static WeakMap, never an instance property. var_dump, print_r,
 * var_export, (array), json_encode and serialize read an object's
 * properties, and a static property belongs to no object, so none of them
 * can reach a secret.
 *
 * An owner keeps only a handle, an empty object that keys the map. A clone
 * copies the handle with its other properties, so it keeps its secret with
 * no __clone of its own (PHP's __clone cannot see the original), and the
 * entry goes when the last owner holding the handle does.
 *
 * A value need not be a string: ClientCredentials keeps a caller's PSR-16
 * cache here too, because a dump of an in-memory cache would show the token
 * it holds.
 *
 * @internal
 */
final class Secrets
{
    /** @var \WeakMap<object, array<string, mixed>>|null */
    private static ?\WeakMap $store = null;

    /** A new, empty handle. */
    public static function handle(): object
    {
        return new \stdClass();
    }

    public static function put(object $handle, string $name, #[\SensitiveParameter] mixed $value): void
    {
        $store = self::store();
        $entry = $store[$handle] ?? [];
        $entry[$name] = $value;
        $store[$handle] = $entry;
    }

    public static function get(object $handle, string $name): mixed
    {
        return self::store()[$handle][$name] ?? null;
    }

    public static function string(object $handle, string $name): string
    {
        $value = self::get($handle, $name);
        return is_string($value) ? $value : '';
    }

    /** @return \WeakMap<object, array<string, mixed>> */
    private static function store(): \WeakMap
    {
        return self::$store ??= new \WeakMap();
    }
}
