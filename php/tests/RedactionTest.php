<?php

declare(strict_types=1);

namespace Lingara\Tests;

use Lingara\AccessToken;
use Lingara\Client;
use Lingara\Exception\LingaraException;
use Lingara\Exception\TransportException;
use Lingara\Tests\Support\ArrayCache;
use Lingara\Tests\Support\FakeHttpClient;
use Nyholm\Psr7\Response;
use PHPUnit\Framework\TestCase;
use Psr\Http\Client\NetworkExceptionInterface;
use Psr\Http\Message\RequestInterface;
use Psr\Log\NullLogger;

final class RedactionTest extends TestCase
{
    private const SECRET = 'lgr_cs_redaction000000000000000000000000000000000';
    private const TOKEN = 'lgr_at_redaction';

    /**
     * 29.9.26u AC12: var_dump, print_r, var_export, (array) and json_encode
     * of the client, the token source, an AccessToken and each exception,
     * each exception's message and getPrevious() chain, and a recursive walk
     * of each exception's getTrace() arguments with zend.exception_ignore_args
     * off, never contain the secret or the token; serialize throws; a clone
     * keeps its secret; clientId is rendered; exposeSecret() is the one
     * public accessor that returns a raw value.
     */
    public function testSecretsUnreachableFromEveryDumpForm(): void
    {
        self::assertSame('0', ini_get('zend.exception_ignore_args'));
        $fake = new FakeHttpClient(
            FakeHttpClient::token(self::TOKEN),
            new Response(403, ['Content-Type' => 'application/json'], '{"code":"insufficient_scope","error":"no"}'),
            self::echoingFailure(...),
        );
        $client = new Client(clientId: 'lgr_cid_visible', clientSecret: self::SECRET, tokenCache: new ArrayCache(), logger: new NullLogger(), http: $fake->stack());
        $errors = [self::thrown(static fn() => $client->getUsage()), self::thrown(static fn() => $client->getUsage())];
        self::assertInstanceOf(TransportException::class, $errors[1]);
        self::assertStringContainsString('[REDACTED]', (string) $errors[1]->getPrevious()?->getMessage());
        self::assertNull($errors[1]->getPrevious()?->getPrevious());

        $source = $client->tokenSource;
        self::assertNotNull($source);
        $token = $source->token();
        foreach ([$client, $source, $token, ...$errors] as $object) {
            self::assertClean($object);
        }
        foreach ($errors as $error) {
            for ($link = $error; $link !== null; $link = $link->getPrevious()) {
                self::assertClean($link->getMessage());
                self::assertClean(self::walk($link->getTrace()));
            }
        }
        self::assertStringContainsString('lgr_cid_visible', print_r($source, true));

        foreach ([$client, $source, $token] as $object) {
            try {
                serialize($object);
                self::fail($object::class . ' serialized');
            } catch (\LogicException) {
            }
        }
        $copy = clone $token;
        self::assertSame(self::TOKEN, $copy->exposeSecret());
        self::assertTrue($copy->equals(new AccessToken(self::TOKEN)));
        // The client holds no secret of its own, and its no-argument methods
        // are operations that would send requests.
        self::assertSame(['exposeSecret', 'exposeSecret'], [...self::rawAccessors($source), ...self::rawAccessors($token)]);
    }

    /** A network failure that echoes the request, Authorization included, as a PSR-18 exception may. */
    private static function echoingFailure(RequestInterface $request): never
    {
        throw new class ($request) extends \RuntimeException implements NetworkExceptionInterface {
            public function __construct(private readonly RequestInterface $request)
            {
                parent::__construct('failed: ' . $request->getHeaderLine('Authorization'));
            }

            public function getRequest(): RequestInterface
            {
                return $this->request;
            }
        };
    }

    /** @return list<string> the public no-argument methods that return a raw secret */
    private static function rawAccessors(object $object): array
    {
        $raw = [];
        foreach ((new \ReflectionClass($object))->getMethods(\ReflectionMethod::IS_PUBLIC) as $method) {
            $name = $method->getName();
            if ($method->getNumberOfRequiredParameters() > 0 || $method->isStatic() || str_starts_with($name, '__') || $name === 'token') {
                continue;
            }
            $value = $method->invoke($object);
            if ($value === self::SECRET || $value === self::TOKEN) {
                $raw[] = $name;
            }
        }
        return $raw;
    }

    private static function thrown(\Closure $call): LingaraException
    {
        try {
            $call();
        } catch (LingaraException $e) {
            return $e;
        }
        self::fail('nothing was thrown');
    }

    private static function assertClean(mixed $subject): void
    {
        $forms = is_string($subject) ? [$subject] : self::dumps($subject);
        foreach ($forms as $form) {
            foreach ([self::SECRET, self::TOKEN] as $secret) {
                $at = strpos($form, $secret);
                self::assertFalse($at, $at === false ? '' : 'found after: ' . substr($form, max(0, $at - 600), 600));
            }
        }
    }

    /** @return list<string> */
    private static function dumps(mixed $subject): array
    {
        ob_start();
        var_dump($subject);
        $forms = [(string) ob_get_clean(), print_r($subject, true), (string) json_encode($subject)];
        $forms[] = (string) @var_export($subject, true);
        $forms[] = (string) @var_export(is_object($subject) ? (array) $subject : $subject, true);
        if ($subject instanceof \Throwable) {
            $forms[] = (string) $subject;
        }
        return $forms;
    }

    /** Every trace argument, recursively, as text. */
    private static function walk(mixed $value): string
    {
        return match (true) {
            is_array($value) => implode("\n", array_map(self::walk(...), $value)),
            $value instanceof \SensitiveParameterValue => '[sensitive]',
            is_object($value) => implode("\n", self::dumps($value)),
            is_scalar($value) => (string) $value,
            default => '',
        };
    }
}
