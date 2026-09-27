package com.fartech.ftapp2.commonsKt

import com.fartech.ftapp2.commonsKt.jackson.KotlinxDateTimeModule
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

object MyUtils {
    val mapper by lazy {
        jacksonObjectMapper().apply {
            registerModule(JavaTimeModule())
            registerModule(KotlinxDateTimeModule())
            enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }
    }

    @JvmStatic
    @Throws(JsonProcessingException::class)
    fun toJsonStr(obj: Any?): String = mapper.writeValueAsString(obj)

    @JvmStatic
    fun toMap(obj: Any?): Map<String, Any?> =
        mapper.convertValue(obj, object : TypeReference<Map<String, Any?>>() {})

    @JvmStatic
    fun <T> toObj(value: Any?, cls: Class<T>): T = mapper.convertValue(value, cls)

    @JvmStatic
    fun generateUniqueID(): String = UUID.randomUUID().toString().replace("-", "")

    @JvmStatic
    fun generateUUID(): String = UUID.randomUUID().toString()
}
