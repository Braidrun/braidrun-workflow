package com.fartech.ftapp2.commonsKt.jackson

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class KotlinxDateTimeModuleTest {
    private val mapper = jacksonObjectMapper().registerModule(KotlinxDateTimeModule())

    @Test
    fun `serializes instant as epoch milliseconds`() {
        val instant = Instant.parse("2026-07-21T07:31:29.123Z")

        assertEquals("1784619089123", mapper.writeValueAsString(instant))
    }

    @Test
    fun `deserializes epoch milliseconds as instant`() {
        val instant = mapper.readValue("1784619089123", Instant::class.java)

        assertEquals(Instant.parse("2026-07-21T07:31:29.123Z"), instant)
    }
}
