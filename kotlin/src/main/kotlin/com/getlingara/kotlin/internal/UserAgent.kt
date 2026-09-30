package com.getlingara.kotlin.internal

/**
 * K6: `lingara-kotlin/<version> (kotlin/<KotlinVersion.CURRENT>; jvm/<Runtime.version()>)`, then a
 * caller's own product token after one space. The library's token always comes first (CONTRACT.md
 * K6; ADR 29.9.26s D9).
 */
internal object UserAgent {
    /** Visible ASCII with no `)`: what K6 allows inside the parentheses. */
    private val RUNTIME = Regex("[\\x20-\\x28\\x2A-\\x7E]+")

    fun of(suffix: String?): String {
        val kotlin = runtime(KotlinVersion.CURRENT.toString())
        val jvm = runtime(Runtime.version().toString())
        val own = "lingara-kotlin/${BuildInfo.VERSION} (kotlin/$kotlin; jvm/$jvm)"
        return if (suffix.isNullOrEmpty()) own else "$own $suffix"
    }

    fun runtime(version: String): String = if (RUNTIME.matches(version)) version else "unknown"
}
