package com.fartech.agents.workflow

import com.charleskorn.kaml.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class YamlJsonElementSerializerTest {

    private val path = YamlPath.root

    // =========================================================================
    // convertYamlNode - Scalars
    // =========================================================================

    @Nested
    inner class ScalarConversionTest {

        @Test
        fun `string scalar converts to JsonPrimitive string`() {
            val node = YamlScalar("hello world", path)
            val result = YamlJsonElementSerializer.convertYamlNode(node)
            assertTrue(result is JsonPrimitive)
            assertEquals("hello world", (result as JsonPrimitive).content)
            assertTrue(result.isString)
        }

        @Test
        fun `integer scalar converts to JsonPrimitive long`() {
            val node = YamlScalar("42", path)
            val result = YamlJsonElementSerializer.convertYamlNode(node)
            assertTrue(result is JsonPrimitive)
            assertEquals(42L, (result as JsonPrimitive).long)
        }

        @Test
        fun `negative integer converts correctly`() {
            val node = YamlScalar("-100", path)
            val result = YamlJsonElementSerializer.convertYamlNode(node)
            assertEquals(-100L, (result as JsonPrimitive).long)
        }

        @Test
        fun `float scalar converts to JsonPrimitive double`() {
            val node = YamlScalar("3.14", path)
            val result = YamlJsonElementSerializer.convertYamlNode(node)
            assertTrue(result is JsonPrimitive)
            assertEquals(3.14, (result as JsonPrimitive).double, 0.001)
        }

        @Test
        fun `boolean true converts to JsonPrimitive boolean`() {
            val node = YamlScalar("true", path)
            val result = YamlJsonElementSerializer.convertYamlNode(node)
            assertTrue(result is JsonPrimitive)
            assertEquals(true, (result as JsonPrimitive).boolean)
        }

        @Test
        fun `boolean false converts to JsonPrimitive boolean`() {
            val node = YamlScalar("false", path)
            val result = YamlJsonElementSerializer.convertYamlNode(node)
            assertTrue(result is JsonPrimitive)
            assertEquals(false, (result as JsonPrimitive).boolean)
        }

        @Test
        fun `boolean TRUE case insensitive`() {
            val node = YamlScalar("TRUE", path)
            val result = YamlJsonElementSerializer.convertYamlNode(node)
            assertEquals(true, (result as JsonPrimitive).boolean)
        }

        @Test
        fun `boolean False case insensitive`() {
            val node = YamlScalar("False", path)
            val result = YamlJsonElementSerializer.convertYamlNode(node)
            assertEquals(false, (result as JsonPrimitive).boolean)
        }

        @Test
        fun `NaN is treated as string not number`() {
            val node = YamlScalar("NaN", path)
            val result = YamlJsonElementSerializer.convertYamlNode(node)
            assertTrue(result is JsonPrimitive)
            assertTrue((result as JsonPrimitive).isString)
            assertEquals("NaN", result.content)
        }

        @Test
        fun `Infinity is treated as string`() {
            val node = YamlScalar("Infinity", path)
            val result = YamlJsonElementSerializer.convertYamlNode(node)
            assertTrue(result is JsonPrimitive)
            assertTrue((result as JsonPrimitive).isString)
        }

        @Test
        fun `negative Infinity is treated as string`() {
            val node = YamlScalar("-Infinity", path)
            val result = YamlJsonElementSerializer.convertYamlNode(node)
            assertTrue((result as JsonPrimitive).isString)
        }

        @Test
        fun `inf YAML alias treated as string`() {
            val node = YamlScalar(".inf", path)
            val result = YamlJsonElementSerializer.convertYamlNode(node)
            assertTrue((result as JsonPrimitive).isString)
        }

        @Test
        fun `plain string stays string`() {
            val node = YamlScalar("not-a-number", path)
            val result = YamlJsonElementSerializer.convertYamlNode(node)
            assertTrue(result is JsonPrimitive)
            assertTrue((result as JsonPrimitive).isString)
            assertEquals("not-a-number", result.content)
        }

        @Test
        fun `empty string stays string`() {
            val node = YamlScalar("", path)
            val result = YamlJsonElementSerializer.convertYamlNode(node)
            assertTrue(result is JsonPrimitive)
            assertEquals("", (result as JsonPrimitive).content)
        }

        @Test
        fun `zero converts to long`() {
            val node = YamlScalar("0", path)
            val result = YamlJsonElementSerializer.convertYamlNode(node)
            assertEquals(0L, (result as JsonPrimitive).long)
        }

        @Test
        fun `large integer converts to long`() {
            val node = YamlScalar("9999999999", path)
            val result = YamlJsonElementSerializer.convertYamlNode(node)
            assertEquals(9999999999L, (result as JsonPrimitive).long)
        }
    }

    // =========================================================================
    // convertYamlNode - Null
    // =========================================================================

    @Nested
    inner class NullConversionTest {

        @Test
        fun `YamlNull converts to JsonNull`() {
            val node = YamlNull(path)
            val result = YamlJsonElementSerializer.convertYamlNode(node)
            assertEquals(JsonNull, result)
        }
    }

    // =========================================================================
    // convertYamlNode - Lists
    // =========================================================================

    @Nested
    inner class ListConversionTest {

        @Test
        fun `empty list converts to empty JsonArray`() {
            val node = YamlList(emptyList(), path)
            val result = YamlJsonElementSerializer.convertYamlNode(node)
            assertTrue(result is JsonArray)
            assertTrue((result as JsonArray).isEmpty())
        }

        @Test
        fun `list of scalars converts correctly`() {
            val items = listOf(
                YamlScalar("hello", path),
                YamlScalar("42", path),
                YamlScalar("true", path)
            )
            val node = YamlList(items, path)
            val result = YamlJsonElementSerializer.convertYamlNode(node) as JsonArray
            assertEquals(3, result.size)
            assertEquals("hello", result[0].jsonPrimitive.content)
            assertEquals(42L, result[1].jsonPrimitive.long)
            assertEquals(true, result[2].jsonPrimitive.boolean)
        }

        @Test
        fun `nested list converts correctly`() {
            val inner = YamlList(listOf(YamlScalar("a", path), YamlScalar("b", path)), path)
            val outer = YamlList(listOf(inner, YamlScalar("c", path)), path)
            val result = YamlJsonElementSerializer.convertYamlNode(outer) as JsonArray
            assertEquals(2, result.size)
            assertTrue(result[0] is JsonArray)
            assertEquals(2, (result[0] as JsonArray).size)
        }
    }

    // =========================================================================
    // convertYamlNode - Maps
    // =========================================================================

    @Nested
    inner class MapConversionTest {

        @Test
        fun `empty map converts to empty JsonObject`() {
            val node = YamlMap(emptyMap(), path)
            val result = YamlJsonElementSerializer.convertYamlNode(node)
            assertTrue(result is JsonObject)
            assertTrue((result as JsonObject).isEmpty())
        }

        @Test
        fun `map with scalar values converts correctly`() {
            val entries = mapOf(
                YamlScalar("name", path) to YamlScalar("test", path) as com.charleskorn.kaml.YamlNode,
                YamlScalar("count", path) to YamlScalar("5", path) as com.charleskorn.kaml.YamlNode
            )
            val node = YamlMap(entries, path)
            val result = YamlJsonElementSerializer.convertYamlNode(node) as JsonObject
            assertEquals("test", result["name"]?.jsonPrimitive?.content)
            assertEquals(5L, result["count"]?.jsonPrimitive?.long)
        }

        @Test
        fun `nested map converts correctly`() {
            val inner = YamlMap(
                mapOf(YamlScalar("key", path) to YamlScalar("value", path) as com.charleskorn.kaml.YamlNode),
                path
            )
            val outer = YamlMap(
                mapOf(YamlScalar("nested", path) to inner as com.charleskorn.kaml.YamlNode),
                path
            )
            val result = YamlJsonElementSerializer.convertYamlNode(outer) as JsonObject
            assertTrue(result["nested"] is JsonObject)
            assertEquals("value", result["nested"]?.jsonObject?.get("key")?.jsonPrimitive?.content)
        }

        @Test
        fun `map with null value`() {
            val entries = mapOf(
                YamlScalar("key", path) to YamlNull(path) as com.charleskorn.kaml.YamlNode
            )
            val node = YamlMap(entries, path)
            val result = YamlJsonElementSerializer.convertYamlNode(node) as JsonObject
            assertEquals(JsonNull, result["key"])
        }

        @Test
        fun `map with list value`() {
            val list = YamlList(
                listOf(YamlScalar("a", path), YamlScalar("b", path)),
                path
            )
            val entries = mapOf(
                YamlScalar("items", path) to list as com.charleskorn.kaml.YamlNode
            )
            val node = YamlMap(entries, path)
            val result = YamlJsonElementSerializer.convertYamlNode(node) as JsonObject
            assertTrue(result["items"] is JsonArray)
            assertEquals(2, (result["items"] as JsonArray).size)
        }
    }

    // =========================================================================
    // Complex Structures
    // =========================================================================

    @Nested
    inner class ComplexStructureTest {

        @Test
        fun `deeply nested map-list-map structure`() {
            val innerMap = YamlMap(
                mapOf(YamlScalar("key", path) to YamlScalar("value", path) as com.charleskorn.kaml.YamlNode),
                path
            )
            val list = YamlList(listOf(innerMap, YamlScalar("item", path)), path)
            val outer = YamlMap(
                mapOf(YamlScalar("data", path) to list as com.charleskorn.kaml.YamlNode),
                path
            )
            val result = YamlJsonElementSerializer.convertYamlNode(outer) as JsonObject
            val dataArr = result["data"] as JsonArray
            assertEquals(2, dataArr.size)
            assertTrue(dataArr[0] is JsonObject)
            assertEquals("value", (dataArr[0] as JsonObject)["key"]?.jsonPrimitive?.content)
            assertEquals("item", dataArr[1].jsonPrimitive.content)
        }

        @Test
        fun `map with mixed value types`() {
            val entries = mapOf(
                YamlScalar("str", path) to YamlScalar("hello", path) as com.charleskorn.kaml.YamlNode,
                YamlScalar("num", path) to YamlScalar("42", path) as com.charleskorn.kaml.YamlNode,
                YamlScalar("bool", path) to YamlScalar("true", path) as com.charleskorn.kaml.YamlNode,
                YamlScalar("nil", path) to YamlNull(path) as com.charleskorn.kaml.YamlNode
            )
            val node = YamlMap(entries, path)
            val result = YamlJsonElementSerializer.convertYamlNode(node) as JsonObject
            assertEquals("hello", result["str"]?.jsonPrimitive?.content)
            assertEquals(42L, result["num"]?.jsonPrimitive?.long)
            assertEquals(true, result["bool"]?.jsonPrimitive?.boolean)
            assertEquals(JsonNull, result["nil"])
        }

        @Test
        fun `list with null elements`() {
            val items = listOf(
                YamlScalar("a", path),
                YamlNull(path),
                YamlScalar("b", path)
            )
            val node = YamlList(items, path)
            val result = YamlJsonElementSerializer.convertYamlNode(node) as JsonArray
            assertEquals(3, result.size)
            assertEquals("a", result[0].jsonPrimitive.content)
            assertEquals(JsonNull, result[1])
            assertEquals("b", result[2].jsonPrimitive.content)
        }
    }

    // =========================================================================
    // YAML Serialization (encode) - regression tests for YamlOutput support
    // =========================================================================

    @Serializable
    data class OverridesHolder(
        @Serializable(with = YamlJsonElementMapSerializer::class)
        val overrides: Map<String, JsonElement> = emptyMap()
    )

    @Nested
    inner class YamlSerializationTest {

        private val yaml = Yaml.default

        @Test
        fun `serialize empty overrides to YAML`() {
            val holder = OverridesHolder(overrides = emptyMap())
            val result = yaml.encodeToString(OverridesHolder.serializer(), holder)
            assertNotNull(result)
            assertTrue(result.contains("overrides"))
        }

        @Test
        fun `serialize string overrides to YAML`() {
            val holder = OverridesHolder(
                overrides = mapOf("name" to JsonPrimitive("test"))
            )
            val result = yaml.encodeToString(OverridesHolder.serializer(), holder)
            assertTrue(result.contains("name"))
            assertTrue(result.contains("test"))
        }

        @Test
        fun `serialize mixed type overrides to YAML`() {
            val holder = OverridesHolder(
                overrides = mapOf(
                    "str" to JsonPrimitive("hello"),
                    "num" to JsonPrimitive(42),
                    "bool" to JsonPrimitive(true),
                    "nil" to JsonNull
                )
            )
            val result = yaml.encodeToString(OverridesHolder.serializer(), holder)
            assertTrue(result.contains("str"))
            assertTrue(result.contains("hello"))
            assertTrue(result.contains("num"))
            assertTrue(result.contains("42"))
            assertTrue(result.contains("bool"))
            assertTrue(result.contains("true"))
        }

        @Test
        fun `serialize nested object overrides to YAML`() {
            val holder = OverridesHolder(
                overrides = mapOf(
                    "config" to JsonObject(
                        mapOf(
                            "key" to JsonPrimitive("value"),
                            "count" to JsonPrimitive(10)
                        )
                    )
                )
            )
            val result = yaml.encodeToString(OverridesHolder.serializer(), holder)
            assertTrue(result.contains("config"))
            assertTrue(result.contains("key"))
            assertTrue(result.contains("value"))
        }

        @Test
        fun `serialize list overrides to YAML`() {
            val holder = OverridesHolder(
                overrides = mapOf(
                    "items" to JsonArray(
                        listOf(
                            JsonPrimitive("a"),
                            JsonPrimitive("b"),
                            JsonPrimitive(3)
                        )
                    )
                )
            )
            val result = yaml.encodeToString(OverridesHolder.serializer(), holder)
            assertTrue(result.contains("items"))
        }

        @Test
        fun `round-trip serialize and deserialize overrides via YAML`() {
            val original = OverridesHolder(
                overrides = mapOf(
                    "strategy" to JsonPrimitive("just_work_parallel"),
                    "max_iterations" to JsonPrimitive(8196),
                    "enabled" to JsonPrimitive(true)
                )
            )
            val yamlString = yaml.encodeToString(OverridesHolder.serializer(), original)
            val restored = yaml.decodeFromString(OverridesHolder.serializer(), yamlString)
            assertEquals(original.overrides["strategy"], restored.overrides["strategy"])
            assertEquals(original.overrides["max_iterations"], restored.overrides["max_iterations"])
            assertEquals(original.overrides["enabled"], restored.overrides["enabled"])
        }
    }
}
