package com.fartech.agents.commons

import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentDatabaseTest {
    @Test
    fun `explicit mongo connection parameter wins without requiring environment default url`() {
        val parameters = listOf(
            ConfigurationParameter("env", JsonPrimitive("braidrun")),
            ConfigurationParameter("mongo_connection_string", JsonPrimitive("mongodb://127.0.0.1:27017")),
            ConfigurationParameter("mongo_db_name", JsonPrimitive("agent_test"))
        )

        assertEquals("mongodb://127.0.0.1:27017" to "agent_test", resolveMongoConnection(parameters))
    }

    @Test
    fun `explicit mongo connection keeps named environment database default`() {
        val parameters = listOf(
            ConfigurationParameter("env", JsonPrimitive("braidrun")),
            ConfigurationParameter("mongo_connection_string", JsonPrimitive("mongodb://127.0.0.1:27017"))
        )

        assertEquals("mongodb://127.0.0.1:27017" to "braidrunpub", resolveMongoConnection(parameters))
    }

    @Test
    fun `scoped environment mongo url takes precedence over generic and fallback urls`() {
        val envVars = mapOf(
            "BRAIDRUN_AGENT_MONGO_URL" to "mongodb://generic:27017",
            "BRAIDRUN_AGENT_MONGO_URL_BRAIDRUN" to "mongodb://scoped:27017"
        )

        assertEquals(
            "mongodb://scoped:27017" to "braidrunpub",
            resolveMongoConnection("braidrun", envVars = envVars, defaultUrl = "mongodb://fallback:27017")
        )
    }
}
