package com.fartech.agents.workflow

import com.charleskorn.kaml.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/**
 * 自定义序列化器：将 KAML 的 YAML 节点转换为 kotlinx.serialization 的 JsonElement。
 *
 * KAML 无法直接反序列化 JsonElement（多态密封类，需要类型标签），
 * 此序列化器通过访问 KAML 的底层 YamlNode 手动转换为 JsonElement，
 * 从而解决 "Value is missing a type tag" 错误。
 */
object YamlJsonElementSerializer : KSerializer<JsonElement> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): JsonElement {
        val yamlInput = decoder as? YamlInput
            ?: return JsonElement.serializer().deserialize(decoder)
        return convertYamlNode(yamlInput.node)
    }

    override fun serialize(encoder: Encoder, value: JsonElement) {
        if (encoder is kotlinx.serialization.json.JsonEncoder) {
            JsonElement.serializer().serialize(encoder, value)
        } else {
            encodeJsonElement(encoder, value)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    internal fun encodeJsonElement(encoder: Encoder, element: JsonElement) {
        when (element) {
            is JsonNull -> encoder.encodeNull()
            is JsonPrimitive -> encodeJsonPrimitive(encoder, element)
            is JsonArray -> encoder.encodeSerializableValue(
                ListSerializer(YamlJsonElementSerializer), element
            )

            is JsonObject -> encoder.encodeSerializableValue(
                MapSerializer(String.serializer(), YamlJsonElementSerializer), element.toMap()
            )
        }
    }

    private fun encodeJsonPrimitive(encoder: Encoder, primitive: JsonPrimitive) {
        if (primitive.isString) {
            encoder.encodeString(primitive.content)
            return
        }
        // Try boolean
        primitive.booleanOrNull?.let { encoder.encodeBoolean(it); return }
        // Try long
        primitive.longOrNull?.let { encoder.encodeLong(it); return }
        // Try double
        primitive.doubleOrNull?.let { encoder.encodeDouble(it); return }
        // Fallback to string
        encoder.encodeString(primitive.content)
    }

    internal fun convertYamlNode(node: YamlNode): JsonElement = when (node) {
        is YamlScalar -> convertScalar(node)
        is YamlMap -> convertMap(node)
        is YamlList -> JsonArray(node.items.map { convertYamlNode(it) })
        is YamlNull -> JsonNull
        is YamlTaggedNode -> convertYamlNode(node.innerNode)
    }

    private fun convertScalar(scalar: YamlScalar): JsonElement {
        val content = scalar.content

        // 布尔值
        if (content.equals("true", ignoreCase = true) || content.equals("false", ignoreCase = true)) {
            return JsonPrimitive(content.toBoolean())
        }

        // 整数
        content.toLongOrNull()?.let { return JsonPrimitive(it) }

        // 浮点数
        content.toDoubleOrNull()?.let {
            // 避免把普通字符串误判为浮点数（如 "NaN", "Infinity"）
            if (!content.equals("NaN", ignoreCase = true) &&
                !content.equals("Infinity", ignoreCase = true) &&
                !content.equals("-Infinity", ignoreCase = true) &&
                !content.equals(".inf", ignoreCase = true) &&
                !content.equals("-.inf", ignoreCase = true)
            ) {
                return JsonPrimitive(it)
            }
        }

        // 字符串
        return JsonPrimitive(content)
    }

    private fun convertMap(map: YamlMap): JsonObject {
        val result = mutableMapOf<String, JsonElement>()
        map.entries.forEach { (key, value) ->
            result[key.content] = convertYamlNode(value)
        }
        return JsonObject(result)
    }
}

/**
 * Map<String, JsonElement> 的序列化器，用于 AgentDefinition.overrides 字段。
 * 在 KAML 环境下将 YAML map 正确转换为 Map<String, JsonElement>。
 */
object YamlJsonElementMapSerializer : KSerializer<Map<String, JsonElement>> {
    private val delegateSerializer = MapSerializer(String.serializer(), YamlJsonElementSerializer)
    private val currentValueDecoderField by lazy {
        Class.forName("com.charleskorn.kaml.YamlMapLikeInputBase")
            .getDeclaredField("currentValueDecoder")
            .apply { isAccessible = true }
    }

    override val descriptor: SerialDescriptor = delegateSerializer.descriptor

    override fun deserialize(decoder: Decoder): Map<String, JsonElement> {
        val yamlInput = decoder as? YamlInput
            ?: return delegateSerializer.deserialize(decoder)

        val node = resolveCurrentYamlNode(yamlInput)
        if (node is YamlMap) {
            val result = mutableMapOf<String, JsonElement>()
            node.entries.forEach { (key, value) ->
                result[key.content] = YamlJsonElementSerializer.convertYamlNode(value)
            }
            return result
        }
        if (node is YamlNull) {
            return emptyMap()
        }

        return delegateSerializer.deserialize(decoder)
    }

    override fun serialize(encoder: Encoder, value: Map<String, JsonElement>) {
        if (encoder is kotlinx.serialization.json.JsonEncoder) {
            delegateSerializer.serialize(encoder, value)
        } else {
            val yamlMapSerializer = MapSerializer(String.serializer(), YamlJsonElementSerializer)
            yamlMapSerializer.serialize(encoder, value)
        }
    }

    private fun resolveCurrentYamlNode(yamlInput: YamlInput): YamlNode {
        if (yamlInput::class.qualifiedName != "com.charleskorn.kaml.YamlObjectInput") {
            return yamlInput.node
        }

        val currentValueDecoder = currentValueDecoderField.get(yamlInput) as? YamlInput
        return currentValueDecoder?.node ?: yamlInput.node
    }
}
