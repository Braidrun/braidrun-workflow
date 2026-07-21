package com.fartech.ftapp2.commonsKt.jackson

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule
import kotlin.time.Instant

class KotlinxDateTimeModule : SimpleModule() {
    init {
        addSerializer(Instant::class.java, InstantSerializer())
        addDeserializer(Instant::class.java, InstantDeserializer())
    }
}

class InstantSerializer : JsonSerializer<Instant>() {
    override fun serialize(value: Instant, gen: JsonGenerator, serializers: SerializerProvider) {
        gen.writeNumber(value.toEpochMilliseconds())
    }
}

class InstantDeserializer : JsonDeserializer<Instant>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Instant {
        return Instant.fromEpochMilliseconds(p.longValue)
    }
}
