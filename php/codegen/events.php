<?php

declare(strict_types=1);

// php/codegen/events.php: generate.php's events output (ADR 30.9.26aa D3),
// required by it and written from the view's x-lingara-events. Into
// Events/Generated/ it writes:
//
//   <Arm>.php            one final readonly class per outbound type,
//                        implementing Lingara\Events\Event, holding the
//                        envelope fields and the type's generated `data` model
//   InboundEvent.php     one named constructor per inbound type, named after
//                        and taking its `data` component
//   EventParser.php      the type table and parseEvent()
//
// It refuses a Model/ file named after an arm, or after one of the four
// names it generates beside them: the openapi-generator ignore file is
// static, and a new arm would otherwise ship two types for one name.

const GENERATED_EVENT_NAMES = ['Event', 'UnknownEvent', 'InboundEvent', 'EventParser'];

/**
 * The view's outbound and inbound entries, each with its data component's
 * name and, for an outbound one, the component's `required` list.
 *
 * @return array{out: list<array<string, mixed>>, in: list<array<string, mixed>>}
 */
function eventEntries(array $view): array
{
    $entries = ['out' => [], 'in' => []];
    foreach ($view['x-lingara-events'] ?? [] as $i => $entry) {
        $data = refName($entry['data'] ?? null) ?? fail("x-lingara-events/{$i}: no data \$ref");
        $schema = $view['components']['schemas'][$data] ?? fail("x-lingara-events/{$i}: no component {$data}");
        $direction = $entry['direction'] ?? '';
        if ($direction === 'out') {
            $arm = $entry['arm'] ?? fail("x-lingara-events/{$i}: an outbound entry has no arm");
            in_array($arm, GENERATED_EVENT_NAMES, true) && fail("x-lingara-events/{$i}: the arm {$arm} is a generated name");
            in_array(strtolower($arm), RESERVED, true) && fail("x-lingara-events/{$i}: the arm {$arm} is a PHP reserved word");
            $entries['out'][] = ['type' => $entry['type'], 'arm' => $arm, 'data' => $data, 'model' => !freeForm($schema),
                'required' => $schema['required'] ?? [], 'transports' => $entry['transports'] ?? []];
        } elseif ($direction === 'in') {
            $entries['in'][] = ['type' => $entry['type'], 'data' => $data];
        } else {
            fail("x-lingara-events/{$i}: direction must be out or in");
        }
    }
    return $entries;
}

/**
 * A bare `type: object` (WebhookTestData, like a stream's Done) is a schema
 * openapi-generator writes no model for: its arm holds the \stdClass.
 */
function freeForm(array $schema): bool
{
    return ($schema['type'] ?? null) === 'object' && ($schema['properties'] ?? []) === []
        && !isset($schema['allOf']) && !isset($schema['oneOf']) && !isset($schema['anyOf']);
}

/** The PHP type of an outbound arm's `data`. */
function armData(array $entry): string
{
    return $entry['model'] ? "\\Lingara\\Model\\{$entry['data']}" : '\\stdClass';
}

function refuseGeneratedArms(string $out, array $view): void
{
    foreach ([...array_column(eventEntries($view)['out'], 'arm'), ...GENERATED_EVENT_NAMES] as $name) {
        $file = "{$out}/Model/{$name}.php";
        if (file_exists($file)) {
            fail("{$file} is openapi-generator's model named {$name}, a class Lingara\\Events generates; "
                . 'name it in codegen/php.openapi-generator-ignore');
        }
    }
}

/** @return array<string, string> relative path => contents */
function eventFiles(array $view): array
{
    $entries = eventEntries($view);
    $files = [];
    foreach ($entries['out'] as $entry) {
        $files["Events/Generated/{$entry['arm']}.php"] = armPhp($entry);
    }
    $files['Events/Generated/InboundEvent.php'] = inboundPhp($entries['in']);
    $files['Events/Generated/EventParser.php'] = parserPhp($entries['out']);
    return $files;
}

function armPhp(array $entry): string
{
    $header = HEADER;
    $type = export($entry['type']);
    $transports = implode(', ', $entry['transports']);
    $data = armData($entry);
    return <<<PHP
        <?php

        {$header}

        declare(strict_types=1);

        namespace Lingara\\Events\\Generated;

        /** The `{$entry['type']}` event ({$transports}): its payload is `\$data`. */
        final readonly class {$entry['arm']} implements \\Lingara\\Events\\Event
        {
            public const TYPE = {$type};

            /** Always self::TYPE. */
            public string \$type;

            public function __construct(
                public string \$id,
                public string \$createdAt,
                public string \$apiVersion,
                public string \$subject,
                public {$data} \$data,
            ) {
                \$this->type = self::TYPE;
            }
        }

        PHP;
}

function inboundPhp(array $entries): string
{
    $header = HEADER;
    $constructors = [];
    foreach ($entries as $entry) {
        $method = lcfirst($entry['data']);
        $type = export($entry['type']);
        $constructors[] = <<<PHP
                /** The `{$entry['type']}` event. */
                public static function {$method}(\\Lingara\\Model\\{$entry['data']} \$data): self
                {
                    return new self({$type}, \$data);
                }
            PHP;
    }
    $body = implode("\n\n", $constructors);
    return <<<PHP
        <?php

        {$header}

        declare(strict_types=1);

        namespace Lingara\\Events\\Generated;

        /**
         * An event a game sends with Client::sendEvent(): one named constructor
         * per inbound type, each taking that type's `data` model. It serialises
         * as `{type, data}`; the server assigns the id, the time, the version and
         * the subject.
         */
        final readonly class InboundEvent implements \\JsonSerializable
        {
            private function __construct(
                public string \$type,
                public \\Lingara\\Model\\ModelInterface \$data,
            ) {
            }

        {$body}

            public function jsonSerialize(): \\stdClass
            {
                return (object) [
                    'type' => \$this->type,
                    'data' => \\Lingara\\ObjectSerializer::sanitizeForSerialization(\$this->data),
                ];
            }
        }

        PHP;
}

function parserPhp(array $entries): string
{
    $header = HEADER;
    $rows = [];
    foreach ($entries as $entry) {
        $required = implode(', ', array_map('export', $entry['required']));
        $model = $entry['model'] ? "\\Lingara\\Model\\{$entry['data']}::class" : 'null';
        $rows[] = '        ' . export($entry['type']) . " => [\n"
            . "            'class' => {$entry['arm']}::class,\n"
            . "            'data' => {$model},\n"
            . "            'required' => [{$required}],\n"
            . '        ],';
    }
    $table = implode("\n", $rows);
    return <<<PHP
        <?php

        {$header}

        declare(strict_types=1);

        namespace Lingara\\Events\\Generated;

        use Lingara\\Events\\Event;
        use Lingara\\Events\\UnknownEvent;

        /**
         * D3's parser (ADR 30.9.26aa): the envelope's `type` picks the arm. A
         * known type whose `data` lacks a required key or does not decode is
         * refused; an unknown type is an UnknownEvent, never an error, so a
         * library older than the catalogue still acknowledges a newer event.
         */
        final class EventParser
        {
            /**
             * Every outbound type: its arm, its `data` model (null for a free-form
             * object, kept as the \\stdClass) and that model's required keys.
             */
            public const ARMS = [
        {$table}
            ];

            private const ENVELOPE = ['id', 'type', 'created_at', 'api_version', 'subject'];

            /**
             * One event from its JSON text, exactly as received.
             *
             * @throws \\UnexpectedValueException for text that is not JSON, not an
             *                                   envelope, or a known type whose
             *                                   `data` does not decode
             */
            public static function parseEvent(string \$json): Event
            {
                try {
                    \$value = \\Lingara\\Internal\\Json::decode(\$json);
                } catch (\\JsonException) {
                    throw new \\UnexpectedValueException('the event is not JSON');
                }
                return self::fromValue(\$value);
            }

            /**
             * One event from its JSON, already decoded as objects.
             *
             * @throws \\UnexpectedValueException as parseEvent()
             */
            public static function fromValue(mixed \$value): Event
            {
                if (!\$value instanceof \\stdClass || !property_exists(\$value, 'data')) {
                    throw new \\UnexpectedValueException('the event is not an envelope');
                }
                foreach (self::ENVELOPE as \$key) {
                    if (!is_string(\$value->{\$key} ?? null)) {
                        throw new \\UnexpectedValueException("the event's {\$key} is not a string");
                    }
                }
                \$arm = self::ARMS[\$value->type] ?? null;
                if (\$arm === null) {
                    return new UnknownEvent(\$value->id, \$value->type, \$value->created_at, \$value->api_version, \$value->subject, \$value->data);
                }
                return new \$arm['class'](\$value->id, \$value->created_at, \$value->api_version, \$value->subject, self::data(\$value->type, \$value->data, \$arm));
            }

            /** @param array{class: class-string, data: class-string|null, required: list<string>} \$arm */
            private static function data(string \$type, mixed \$data, array \$arm): object
            {
                if (!\$data instanceof \\stdClass) {
                    throw new \\UnexpectedValueException("{\$type}: data is not a JSON object");
                }
                foreach (\$arm['required'] as \$key) {
                    if (!property_exists(\$data, \$key)) {
                        throw new \\UnexpectedValueException("{\$type}: data has no {\$key}");
                    }
                }
                if (\$arm['data'] === null) {
                    return \$data;
                }
                try {
                    \$model = \\Lingara\\ObjectSerializer::deserialize(\$data, \$arm['data']);
                } catch (\\Throwable) {
                    \$model = null;
                }
                return \$model instanceof \$arm['data'] ? \$model : throw new \\UnexpectedValueException("{\$type}: data does not decode");
            }
        }

        PHP;
}
