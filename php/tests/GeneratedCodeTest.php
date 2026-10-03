<?php

declare(strict_types=1);

namespace Lingara\Tests;

use Lingara\Client;
use Lingara\Exception\TransportException;
use Lingara\Exception\TransportKind;
use Lingara\Model\LessonPlan;
use Lingara\Model\VersionState;
use Lingara\Model\VersionSummary;
use Lingara\Tests\Support\FakeHttpClient;
use PHPUnit\Framework\TestCase;

final class GeneratedCodeTest extends TestCase
{
    private const SRC = __DIR__ . '/../src';

    /**
     * 29.9.26u AC2: no line of src/Model/, src/ObjectSerializer.php,
     * src/Stream/, src/Events/Generated/ or Operations.php names GuzzleHttp\ or the generator's
     * Configuration, ApiException or HeaderSelector, and every `use` or
     * fully qualified name is under Lingara\, Psr\ or PHP's own classes.
     */
    public function testGeneratedSourcesReferenceOnlyAllowedNames(): void
    {
        $files = self::generatedFiles();
        self::assertGreaterThan(40, count($files));
        foreach ($files as $file) {
            foreach (file($file) ?: [] as $n => $line) {
                $where = basename($file) . ':' . ($n + 1);
                self::assertDoesNotMatchRegularExpression('/GuzzleHttp\\\\|\b(Configuration|ApiException|HeaderSelector)\b/', $line, $where);
                // Every whole name holding a backslash, and every `use`d name.
                preg_match_all('/(?<![\w\\\\])(\\\\?[A-Za-z_]\w*(?:\\\\[A-Za-z_]\w*)+|\\\\[A-Za-z_]\w*)/', $line, $qualified);
                preg_match_all('/^\s*use\s+([A-Za-z_][\w\\\\]*)/', $line, $used);
                foreach ([...$qualified[1], ...$used[1]] as $name) {
                    self::assertTrue(self::allowed($name), "{$where} names {$name}");
                }
            }
        }
    }

    /** 29.9.26u AC3: a named date-time field and a named uuid field of the generated models are declared string. */
    public function testMappedFormatsAreStrings(): void
    {
        self::assertSame('string', VersionSummary::openAPITypes()['minted_at']);
        self::assertSame('date-time', VersionSummary::openAPIFormats()['minted_at']);
        self::assertSame('string', LessonPlan::openAPITypes()['id']);
        self::assertSame('uuid', LessonPlan::openAPIFormats()['id']);
        $setter = new \ReflectionMethod(LessonPlan::class, 'setId');
        self::assertSame('string', (string) $setter->getParameters()[0]->getType());
    }

    /**
     * 29.9.26u AC33: every generated file includes under E_ALL with an error
     * handler that throws, with no error, and a response holding an enum
     * value the pin does not list decodes to the unknown case while a
     * deserialization throw of any other kind is MalformedResponse.
     */
    public function testGeneratedCodeIncludesQuietlyAndDecodesForwardCompatibly(): void
    {
        $script = 'error_reporting(E_ALL);'
            . 'set_error_handler(static function (int $level, string $message, string $file, int $line): never {'
            . ' throw new ErrorException($message, 0, $level, $file, $line); });'
            . 'require ' . var_export(__DIR__ . '/../vendor/autoload.php', true) . ';'
            . 'foreach (' . var_export(self::generatedFiles(), true) . ' as $file) { require_once $file; }'
            . 'echo "quiet";';
        $out = shell_exec(escapeshellarg(PHP_BINARY) . ' -r ' . escapeshellarg($script) . ' 2>&1');
        self::assertSame('quiet', $out);

        $detail = ['id' => '2026-09-knowing-tenpounder', 'state' => 'hibernating', 'lts' => false, 'minted_at' => '2026-09-20T09:00:00Z',
            'summary' => 'x', 'history' => [], 'spec' => ['url' => '/v1/openapi.json', 'sha256' => str_repeat('0', 64)]];
        $client = new Client(http: (new FakeHttpClient(FakeHttpClient::json(200, $detail), FakeHttpClient::json(200, ['history' => 'not a list'] + $detail)))->stack());
        self::assertSame(VersionState::UNKNOWN_DEFAULT_OPEN_API, $client->getApiVersion('x')->value->getState());
        try {
            $client->getApiVersion('x');
            self::fail('a history that is not a list decoded');
        } catch (TransportException $e) {
            self::assertSame(TransportKind::MalformedResponse, $e->kind());
        }
    }

    /** @return list<string> */
    private static function generatedFiles(): array
    {
        $files = [self::SRC . '/ObjectSerializer.php', self::SRC . '/Internal/Operations.php', self::SRC . '/Version.php'];
        foreach (['Model', 'Stream', 'Events/Generated'] as $dir) {
            $it = new \RecursiveIteratorIterator(new \RecursiveDirectoryIterator(self::SRC . "/{$dir}", \FilesystemIterator::SKIP_DOTS));
            foreach ($it as $file) {
                if ($file instanceof \SplFileInfo && $file->getExtension() === 'php') {
                    $files[] = $file->getPathname();
                }
            }
        }
        sort($files);
        return $files;
    }

    /** Under Lingara\ or Psr\, or a single-segment name PHP itself defines. */
    private static function allowed(string $name): bool
    {
        $name = ltrim($name, '\\');
        if (str_starts_with($name, 'Lingara\\') || str_starts_with($name, 'Psr\\') || $name === 'Lingara') {
            return true;
        }
        if (str_contains($name, '\\')) {
            return false;
        }
        if (class_exists($name, false) || interface_exists($name, false)) {
            return (new \ReflectionClass($name))->isInternal();
        }
        // Not a class: a word in prose after a backslash, such as `\n`.
        return !class_exists($name);
    }
}
