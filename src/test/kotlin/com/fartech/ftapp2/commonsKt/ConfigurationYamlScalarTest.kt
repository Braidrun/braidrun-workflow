package com.fartech.ftapp2.commonsKt

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlNode
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConfigurationYamlScalarTest {

    private fun scalarToJson(yaml: String): JsonPrimitive {
        val node: YamlNode = Yaml.default.parseToYamlNode(yaml)
        return node.toJsonElement() as JsonPrimitive
    }

    @Test
    fun `large integers survive without Double precision loss`() {
        // Routing integers through Double silently corrupted values above 2^53:
        // 123456789012345678 became ...680.
        assertEquals("123456789012345678", scalarToJson("123456789012345678").content)
    }

    @Test
    fun `regular integers and floats still parse`() {
        assertEquals("42", scalarToJson("42").content)
        assertEquals("3.5", scalarToJson("3.5").content)
        assertEquals("-7", scalarToJson("-7").content)
    }

    @Test
    fun `non-finite numerics are kept as literal strings`() {
        // JsonPrimitive(Double.NaN/Infinity) fails on re-encode — keep the literal.
        assertEquals("NaN", scalarToJson("NaN").content)
        assertEquals("Infinity", scalarToJson("Infinity").content)
    }

    @Test
    fun `plain strings pass through`() {
        assertEquals("hello world", scalarToJson("hello world").content)
    }
}
