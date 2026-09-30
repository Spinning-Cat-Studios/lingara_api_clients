package com.getlingara.kotlin

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeneratedImportsTest {
    /**
     * 29.9.26s AC25: no line of the generated sources names a java., javax., jakarta. or
     * org.openapitools token, an infrastructure package or @Contextual, and everything they name is
     * under kotlin.*, kotlinx.serialization.* or com.getlingara.kotlin.* — every line, not only
     * imports, because the Kotlin generator writes a mapped type fully qualified with no import.
     */
    @Test
    fun generatedSourcesReferenceOnlyAllowedPackages() {
        val files =
            File(System.getProperty("lingara.generated"))
                .walkTopDown()
                .filter { it.name.endsWith(".kt") }
                .toList()
        assertTrue(files.size > 30, "the generated tree was found: ${files.size}")
        val offences = mutableListOf<String>()
        for (file in files) {
            file.readLines().forEachIndexed { i, line ->
                val where = "${file.name}:${i + 1}: "
                if (FORBIDDEN.containsMatchIn(line)) offences += where + line
                QUALIFIED.findAll(line).forEach { m ->
                    if (ALLOWED.none { m.groupValues[1].startsWith(it) }) offences += where + m.value
                }
            }
        }
        assertEquals(listOf(), offences)
    }

    private companion object {
        val FORBIDDEN = Regex("\\bjava\\.|\\bjavax\\.|\\bjakarta\\.|org\\.openapitools|\\binfrastructure\\b|@Contextual")

        /** A package-qualified name: lower-case segments, then a type. */
        val QUALIFIED = Regex("\\b((?:[a-z][a-z0-9_]*\\.)+)[A-Z]\\w*")

        val ALLOWED = listOf("kotlin.", "kotlinx.serialization.", "com.getlingara.kotlin.")
    }
}
