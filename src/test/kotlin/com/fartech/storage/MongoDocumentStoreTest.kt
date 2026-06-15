package com.fartech.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MongoDocumentStoreTest {

    @Test
    fun `coerceLong accepts numeric bson-like values`() {
        assertEquals(1234L, coerceLong(1234L))
        assertEquals(1234L, coerceLong(1234))
        assertEquals(1234L, coerceLong(1234.0))
        assertEquals(1234L, coerceLong(1234f))
        assertEquals(1234L, coerceLong("1234"))
    }

    @Test
    fun `coerceLong rejects unsupported values`() {
        assertNull(coerceLong(Double.NaN))
        assertNull(coerceLong(Double.POSITIVE_INFINITY))
        assertNull(coerceLong("not-a-number"))
        assertNull(coerceLong(mapOf("bad" to "value")))
    }
}
