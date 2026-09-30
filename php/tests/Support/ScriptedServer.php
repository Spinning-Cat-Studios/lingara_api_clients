<?php

declare(strict_types=1);

namespace Lingara\Tests\Support;

/**
 * Starts scripted_server.php on a free loopback port and reads what it
 * reports. One instance serves one script, one connection per entry.
 */
final class ScriptedServer
{
    /** @var resource */
    private $process;

    /** @var resource */
    private $stdout;

    private string $scriptFile;

    public readonly string $url;

    /** @param list<array<string, mixed>> $script */
    public function __construct(array $script)
    {
        $this->scriptFile = (string) tempnam(sys_get_temp_dir(), 'lgr-script');
        file_put_contents($this->scriptFile, json_encode($script, JSON_THROW_ON_ERROR));
        $command = [PHP_BINARY, __DIR__ . '/scripted_server.php', $this->scriptFile];
        $process = proc_open($command, [1 => ['pipe', 'w'], 2 => STDERR], $pipes);
        if ($process === false) {
            throw new \RuntimeException('could not start scripted_server.php');
        }
        $this->process = $process;
        $this->stdout = $pipes[1];
        $line = (string) fgets($this->stdout);
        if (!str_starts_with($line, 'port ')) {
            throw new \RuntimeException("scripted_server.php said: {$line}");
        }
        $this->url = 'http://127.0.0.1:' . trim(substr($line, 5));
    }

    /** A 200 SSE response head, chunked as the API's are. */
    public static function sseHead(string $extraHeaders = ''): string
    {
        return "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n{$extraHeaders}\r\n";
    }

    /** One HTTP chunk. */
    public static function chunk(string $bytes): string
    {
        return dechex(strlen($bytes)) . "\r\n{$bytes}\r\n";
    }

    /** The last chunk. */
    public static function end(): string
    {
        return "0\r\n\r\n";
    }

    /** A complete JSON response. */
    public static function json(int $status, string $body, string $extraHeaders = ''): string
    {
        return "HTTP/1.1 {$status} X\r\nContent-Type: application/json\r\nContent-Length: " . strlen($body)
            . "\r\nConnection: close\r\n{$extraHeaders}\r\n{$body}";
    }

    /** The next line the server printed that starts with $prefix, waiting up to $seconds. */
    public function next(string $prefix, float $seconds = 5.0): ?string
    {
        $until = microtime(true) + $seconds;
        stream_set_blocking($this->stdout, false);
        while (microtime(true) < $until) {
            $line = fgets($this->stdout);
            if ($line === false) {
                usleep(10_000);
                continue;
            }
            if (str_starts_with($line, $prefix)) {
                return trim(substr($line, strlen($prefix)));
            }
        }
        return null;
    }

    public function stop(): void
    {
        proc_terminate($this->process);
        proc_close($this->process);
        @unlink($this->scriptFile);
    }

    public function __destruct()
    {
        if (is_resource($this->process)) {
            $this->stop();
        }
    }
}
