<?php

declare(strict_types=1);

namespace Lingara\Tests;

use PHPUnit\Framework\TestCase;

/**
 * The one budget PHP_CodeSniffer has no sniff for: at most five required
 * parameters per hand-written function. A parameter with a default is
 * exempt: a named argument with a default is PHP's spelling of an option, so
 * Client's constructor options are not a parameter list.
 */
final class BudgetsTest extends TestCase
{
    private const MAX_REQUIRED = 5;
    private const GENERATED = ['Model/', 'Stream/', 'Events/Generated/', 'ObjectSerializer.php', 'Internal/Operations.php', 'Version.php'];

    public function testNoHandWrittenFunctionTakesMoreThanFiveRequiredParameters(): void
    {
        $checked = 0;
        foreach (self::classes() as $class) {
            foreach ((new \ReflectionClass($class))->getMethods() as $method) {
                if ($method->getDeclaringClass()->getName() !== $class) {
                    continue;
                }
                $checked++;
                self::assertLessThanOrEqual(
                    self::MAX_REQUIRED,
                    $method->getNumberOfRequiredParameters(),
                    "{$class}::{$method->getName()}",
                );
            }
        }
        self::assertGreaterThan(100, $checked);
    }

    /** @return list<class-string> every hand-written class under src/ and tests/ */
    private static function classes(): array
    {
        $classes = [];
        foreach (['Lingara\\' => __DIR__ . '/../src', 'Lingara\\Tests\\' => __DIR__] as $prefix => $root) {
            $root = (string) realpath($root);
            $it = new \RecursiveIteratorIterator(new \RecursiveDirectoryIterator($root, \FilesystemIterator::SKIP_DOTS));
            foreach ($it as $file) {
                if (!$file instanceof \SplFileInfo || $file->getExtension() !== 'php' || !ctype_upper($file->getFilename()[0])) {
                    continue;
                }
                $relative = substr($file->getPathname(), strlen($root) + 1);
                if (self::generated($relative) || str_starts_with($relative, 'fixtures/')) {
                    continue;
                }
                $class = $prefix . str_replace(['/', '.php'], ['\\', ''], $relative);
                if (class_exists($class) || interface_exists($class) || enum_exists($class)) {
                    $classes[] = $class;
                }
            }
        }
        return $classes;
    }

    private static function generated(string $relative): bool
    {
        foreach (self::GENERATED as $path) {
            if (str_starts_with($relative, $path)) {
                return true;
            }
        }
        return false;
    }
}
