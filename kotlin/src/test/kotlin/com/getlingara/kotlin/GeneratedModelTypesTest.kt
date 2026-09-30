package com.getlingara.kotlin

import com.getlingara.kotlin.model.AllowanceRow
import com.getlingara.kotlin.model.LessonPlan
import com.getlingara.kotlin.model.UsageLedger
import kotlinx.serialization.KSerializer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class GeneratedModelTypesTest {
    /** The declared type of [field], read from its serializer descriptor, nullability stripped. */
    private fun declared(
        serializer: KSerializer<*>,
        field: String,
    ): String {
        val descriptor = serializer.descriptor
        return descriptor.getElementDescriptor(descriptor.getElementIndex(field)).serialName.removeSuffix("?")
    }

    /**
     * 29.9.26s AC35: a named uint64 field of a generated model is declared kotlin.ULong, a uint32
     * field kotlin.Long, and a date-time and a uuid field kotlin.String. The descriptor, not JVM
     * reflection, because a ULong property is erased to `long` in the bytecode. uint8 is left out
     * on purpose: its target, Int, is also openapi-generator's fallback for an unknown format, so a
     * wrong key there cannot be observed.
     */
    @Test
    fun formatsMapToTheirDeclaredTypes() {
        assertEquals("kotlin.ULong", declared(UsageLedger.serializer(), "calls"))
        assertEquals("kotlin.Long", declared(AllowanceRow.serializer(), "limit"))
        assertEquals("kotlin.String", declared(LessonPlan.serializer(), "created_at"))
        assertEquals("kotlin.String", declared(LessonPlan.serializer(), "id"))
    }
}
