<?php

declare(strict_types=1);

// PER-CS 2.0 over the hand-written code: src/ without the generated paths,
// and the tests (ADR 29.9.26u D7).

$finder = PhpCsFixer\Finder::create()
    ->in([__DIR__ . '/src', __DIR__ . '/tests'])
    ->exclude(['Model', 'Stream', 'fixtures'])
    ->notPath(['ObjectSerializer.php', 'Internal/Operations.php', 'Version.php']);

return (new PhpCsFixer\Config())
    ->setRules(['@PER-CS2.0' => true])
    ->setCacheFile(__DIR__ . '/vendor/.php-cs-fixer.cache')
    ->setFinder($finder);
