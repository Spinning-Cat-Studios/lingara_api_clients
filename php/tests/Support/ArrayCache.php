<?php

declare(strict_types=1);

namespace Lingara\Tests\Support;

use Psr\SimpleCache\CacheInterface;

/** An in-memory PSR-16 cache that records each TTL, and can be told to throw. */
final class ArrayCache implements CacheInterface
{
    /** @var array<string, mixed> */
    public array $values = [];

    /** @var array<string, int|null> */
    public array $ttls = [];

    public bool $broken = false;

    public function get(string $key, mixed $default = null): mixed
    {
        $this->check();
        return $this->values[$key] ?? $default;
    }

    public function set(string $key, mixed $value, null|int|\DateInterval $ttl = null): bool
    {
        $this->check();
        $this->values[$key] = $value;
        $this->ttls[$key] = $ttl instanceof \DateInterval ? null : $ttl;
        return true;
    }

    public function delete(string $key): bool
    {
        $this->check();
        unset($this->values[$key]);
        return true;
    }

    public function clear(): bool
    {
        $this->values = [];
        return true;
    }

    /**
     * @param iterable<string> $keys
     *
     * @return iterable<string, mixed>
     */
    public function getMultiple(iterable $keys, mixed $default = null): iterable
    {
        $out = [];
        foreach ($keys as $key) {
            $out[$key] = $this->get($key, $default);
        }
        return $out;
    }

    /** @param iterable<string, mixed> $values */
    public function setMultiple(iterable $values, null|int|\DateInterval $ttl = null): bool
    {
        foreach ($values as $key => $value) {
            $this->set($key, $value, $ttl);
        }
        return true;
    }

    /** @param iterable<string> $keys */
    public function deleteMultiple(iterable $keys): bool
    {
        foreach ($keys as $key) {
            $this->delete($key);
        }
        return true;
    }

    public function has(string $key): bool
    {
        return isset($this->values[$key]);
    }

    private function check(): void
    {
        if ($this->broken) {
            throw new \RuntimeException('the cache backend is down');
        }
    }
}
