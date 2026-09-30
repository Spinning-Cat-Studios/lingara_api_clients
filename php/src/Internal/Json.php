<?php

declare(strict_types=1);

namespace Lingara\Internal;

/**
 * JSON decoding, as objects, with one refusal json_decode does not make: a
 * number of magnitude 2^63 or more. json_decode turns such an integer into a
 * float, and the generated models' settype would then silently corrupt it,
 * so the library refuses the document before a model sees it.
 *
 * @internal
 */
final class Json
{
    /** 2^63 as a decimal string, for comparing integer literals exactly. */
    private const INT_LIMIT = '9223372036854775808';

    /** A string literal (skipped) or a number token. */
    private const TOKENS = '/"(?:[^"\\\\]++|\\\\.)*+"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/';

    /**
     * The decoded value, objects as \stdClass.
     *
     * @throws \JsonException for text that is not JSON, or holds a number of
     *                        magnitude 2^63 or more
     */
    public static function decode(string $text): mixed
    {
        $value = json_decode($text, false, 512, JSON_THROW_ON_ERROR);
        if (self::hasWideNumber($text)) {
            throw new \JsonException('a JSON number of magnitude 2^63 or more cannot be represented');
        }
        return $value;
    }

    private static function hasWideNumber(string $text): bool
    {
        preg_match_all(self::TOKENS, $text, $matches);
        foreach ($matches[0] as $token) {
            if ($token[0] !== '"' && self::isWide($token)) {
                return true;
            }
        }
        return false;
    }

    private static function isWide(string $number): bool
    {
        $digits = ltrim($number, '-');
        if (ctype_digit($digits)) {
            // An integer literal is compared as a string: PHP_INT_MAX rounds
            // up to 2^63 as a float.
            $digits = ltrim($digits, '0');
            $length = strlen($digits);
            return $length > strlen(self::INT_LIMIT)
                || ($length === strlen(self::INT_LIMIT) && strcmp($digits, self::INT_LIMIT) >= 0);
        }
        return abs((float) $number) >= 9.2233720368547758E18;
    }
}
