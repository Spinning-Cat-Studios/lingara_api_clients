<?php

declare(strict_types=1);

// A loopback server for the tests that only a real socket can show: both
// built stacks' streaming, the idle and token timeouts, a closed or reset
// connection, and the transport kinds. PHP cannot serve and block on a client
// in one process, so ScriptedServer starts this with proc_open.
//
//   php scripted_server.php <script.json>
//
// It prints `port <n>` once listening, then serves one connection per script
// entry, in order. An entry is {"write": [<bytes> | {"sleep": <s>}, …],
// "then": "close" | "reset" | "hold" | "garbage"}. `hold` keeps the
// connection open after the writes until the client closes it, then prints
// `closed <ms>`: the milliseconds since the last write. `garbage` answers the
// first bytes (a TLS ClientHello) with bytes that are not TLS. Each request's
// head is printed as `request <first line>`.

// A client that has timed out or cancelled closes its end; the next write
// must fail, not kill the server.
if (function_exists('pcntl_signal')) {
    pcntl_signal(SIGPIPE, SIG_IGN);
}

$script = json_decode((string) file_get_contents($argv[1]), true, 512, JSON_THROW_ON_ERROR);
$server = stream_socket_server('tcp://127.0.0.1:0', $errno, $errstr);
if ($server === false) {
    fwrite(STDERR, "listen: {$errstr}\n");
    exit(1);
}
$name = (string) stream_socket_get_name($server, false);
echo 'port ', substr($name, strrpos($name, ':') + 1), "\n";

foreach ($script as $entry) {
    $conn = stream_socket_accept($server, 30);
    if ($conn === false) {
        exit(0);
    }
    serve($conn, $entry);
}

/** @param resource $conn */
function serve($conn, array $entry): void
{
    if (($entry['then'] ?? '') === 'garbage') {
        fread($conn, 4096);
        fwrite($conn, "this is not TLS\r\n\r\n");
        fclose($conn);
        return;
    }
    $head = readRequest($conn);
    echo 'request ', strtok($head, "\r\n"), "\n";
    $last = hrtime(true);
    foreach ($entry['write'] ?? [] as $step) {
        if (is_array($step)) {
            usleep((int) ($step['sleep'] * 1e6));
            continue;
        }
        if (@fwrite($conn, $step) === false) {
            break;
        }
        fflush($conn);
        $last = hrtime(true);
    }
    finish($conn, $entry['then'] ?? 'close', $last);
}

/** @param resource $conn */
function finish($conn, string $then, int|float $last): void
{
    if ($then === 'reset') {
        usleep(100_000);
        $socket = socket_import_stream($conn);
        socket_set_option($socket, SOL_SOCKET, SO_LINGER, ['l_onoff' => 1, 'l_linger' => 0]);
        socket_close($socket);
        return;
    }
    if ($then === 'hold') {
        stream_set_blocking($conn, false);
        while (!feof($conn)) {
            $read = [$conn];
            $none = null;
            if (stream_select($read, $none, $none, 0, 20_000) === 1 && fread($conn, 4096) === '' && feof($conn)) {
                break;
            }
        }
        echo 'closed ', (int) ((hrtime(true) - $last) / 1e6), "\n";
    }
    fclose($conn);
}

/**
 * The request head, and a Content-Length body after it.
 *
 * @param resource $conn
 */
function readRequest($conn): string
{
    $data = '';
    while (!str_contains($data, "\r\n\r\n")) {
        $bytes = fread($conn, 4096);
        if ($bytes === '' || $bytes === false) {
            return $data;
        }
        $data .= $bytes;
    }
    [$head, $body] = explode("\r\n\r\n", $data, 2);
    if (preg_match('/^content-length:\s*(\d+)/mi', $head, $m) === 1) {
        while (strlen($body) < (int) $m[1] && ($bytes = fread($conn, 4096)) !== '' && $bytes !== false) {
            $body .= $bytes;
        }
    }
    return $head;
}
