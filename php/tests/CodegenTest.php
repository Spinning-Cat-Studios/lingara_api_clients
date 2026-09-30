<?php

declare(strict_types=1);

namespace Lingara\Tests;

use PHPUnit\Framework\TestCase;

final class CodegenTest extends TestCase
{
    private const SCRIPT = __DIR__ . '/../codegen/generate.php';
    private const VIEW = __DIR__ . '/fixtures/view.json';

    /**
     * 29.9.26u AC4: over a fixture view, generate.php writes one union
     * interface per stream with one class per event other than `done` and
     * `error`, the routes with the streams' event names, and Version.php; two
     * runs are byte-identical; and it refuses an output directory holding a
     * file named after a union or a branch.
     */
    public function testFixtureViewYieldsUnionsOperationsAndVersion(): void
    {
        $dir = sys_get_temp_dir() . '/lgr-codegen-' . bin2hex(random_bytes(4));
        mkdir($dir);
        file_put_contents("{$dir}/VERSION", "0.1.0-alpha.1\n");
        $out = "{$dir}/out";
        [$status, $err] = self::generate($out, "{$dir}/VERSION");
        self::assertSame(0, $status, $err);

        self::assertSame([
            'Internal/Operations.php', 'Stream/FollowPlanEvent.php', 'Stream/FollowPlanEvent/Pending.php', 'Stream/FollowPlanEvent/Phase.php',
            'Stream/FollowPlanEvent/Result.php', 'Stream/StreamWordsEvent.php', 'Stream/StreamWordsEvent/Started.php',
            'Stream/StreamWordsEvent/Word.php', 'Version.php',
        ], self::tree($out));

        $operations = self::load("{$out}/Internal/Operations.php");
        self::assertSame(['followPlan', 'getA', 'getB', 'getC', 'putD', 'streamWords'], array_keys($operations));
        $words = self::events($operations, 'streamWords');
        self::assertSame(['started', 'word', 'done', 'error'], array_keys($words));
        self::assertSame([null, null, 'quiet', 'raise'], array_column($words, 'end'));
        self::assertSame(['yield', 'yield', 'raise'], array_slice(array_column(self::events($operations, 'followPlan'), 'end'), 1));
        self::assertSame(['id'], $operations['followPlan']['pathParams']);
        self::assertSame([true, false], [$operations['getA']['needsToken'], $operations['getC']['needsToken']]);
        $version = (string) file_get_contents("{$out}/Version.php");
        self::assertStringContainsString("VERSION = '0.1.0-alpha.1';", $version);
        self::assertStringContainsString("GENERATED_FOR_VERSION = '2026-09-fixture-view';", $version);

        $first = array_map(static fn(string $f): string => (string) file_get_contents("{$out}/{$f}"), self::tree($out));
        self::generate($out, "{$dir}/VERSION");
        self::assertSame($first, array_map(static fn(string $f): string => (string) file_get_contents("{$out}/{$f}"), self::tree($out)));

        mkdir("{$out}/Model");
        foreach (['StreamWordsEvent.php', 'StreamWordsEventWord.php'] as $file) {
            touch("{$out}/Model/{$file}");
            [$status, $err] = self::generate($out, "{$dir}/VERSION");
            self::assertSame(1, $status);
            self::assertStringContainsString($file, $err);
            unlink("{$out}/Model/{$file}");
        }

        // A `list` event would be the class `List`, a reserved word.
        $view = str_replace(
            ['"events": ["started", "word", "done", "error"]', '"error": "#/components/schemas/StreamWordsEventError"'],
            ['"events": ["started", "word", "list", "done", "error"]', '"error": "#/components/schemas/StreamWordsEventError", "list": "#/components/schemas/StreamWordsEventWord"'],
            (string) file_get_contents(self::VIEW),
            $replaced,
        );
        self::assertSame(2, $replaced);
        file_put_contents("{$dir}/reserved.json", $view);
        [$status, $err] = self::generate($out, "{$dir}/VERSION", "{$dir}/reserved.json");
        self::assertSame(1, $status);
        self::assertStringContainsString('reserved word', $err);
    }

    /** @return array{int, string} */
    private static function generate(string $out, string $version, string $view = self::VIEW): array
    {
        $command = [PHP_BINARY, self::SCRIPT, "--view={$view}", "--version={$version}", "--out={$out}"];
        $process = proc_open($command, [1 => ['pipe', 'w'], 2 => ['pipe', 'w']], $pipes);
        self::assertIsResource($process);
        stream_get_contents($pipes[1]);
        $err = (string) stream_get_contents($pipes[2]);
        return [proc_close($process), $err];
    }

    /** @return list<string> */
    private static function tree(string $root): array
    {
        $files = [];
        $it = new \RecursiveIteratorIterator(new \RecursiveDirectoryIterator($root, \FilesystemIterator::SKIP_DOTS));
        foreach ($it as $file) {
            if ($file instanceof \SplFileInfo && $file->getExtension() === 'php') {
                $files[] = substr($file->getPathname(), strlen($root) + 1);
            }
        }
        sort($files);
        return $files;
    }

    /**
     * The fixture's OPERATIONS table, read in its own namespace so it never
     * meets the real Lingara\Internal\Operations.
     *
     * @return array<array<mixed>>
     */
    private static function load(string $file): array
    {
        $namespace = 'Fixture' . bin2hex(random_bytes(4));
        $code = str_replace(['<?php', 'namespace Lingara\Internal;'], ['', "namespace {$namespace};"], (string) file_get_contents($file));
        eval($code);
        $operations = constant("{$namespace}\\Operations::OPERATIONS");
        self::assertIsArray($operations);
        return array_map(static fn(mixed $op): array => is_array($op) ? $op : [], $operations);
    }

    /**
     * @param array<array<mixed>> $operations
     *
     * @return array<array<mixed>>
     */
    private static function events(array $operations, string $id): array
    {
        $stream = $operations[$id]['stream'] ?? null;
        self::assertIsArray($stream);
        self::assertIsArray($stream['events']);
        return array_map(static fn(mixed $e): array => is_array($e) ? $e : [], $stream['events']);
    }
}
