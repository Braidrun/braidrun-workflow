package com.fartech.agents.runner

import kotlinx.serialization.json.*

/** Negotiated companion: only CODE_STEP may exceed the frozen v1 limit. */
internal object RunnerLongCodeProtocol {
    const val CAPABILITY = "process.longCode.v1"
    const val HASH = "5cc528f669bbd7f6d7a5ea9c981f9464c522364defa73b1966e254456979eb62"
    const val ORIGIN = "4d4d448f0e5933d671f5ec01d8b2dba17a6fdddb"
    const val MAX_SECONDS = 190000L
    private fun resource(name: String) = javaClass.getResourceAsStream("/runner-workflow-v1/$name")?.use { it.readBytes() }
    fun available() = resource("long-code-process.schema.json")?.let { sha256(it) == HASH } == true &&
        resource("long-code-process-origin.txt")?.toString(Charsets.UTF_8)?.trim() == ORIGIN
    private val validator by lazy {
        require(available())
        RunnerProtocol(runnerJson.parseToJsonElement(requireNotNull(resource("long-code-process.schema.json")).toString(Charsets.UTF_8)).jsonObject)
    }
    fun isLong(frame: JsonObject) = frame["type"]?.jsonPrimitive?.content in setOf("processLaunch", "processOpen") &&
        ((frame["payload"] as? JsonObject)?.get("timeoutSeconds") as? JsonPrimitive)?.doubleOrNull?.let { it > 86400 } == true
    fun validate(frame: JsonObject) = validator.validate(frame, "longProcessFrame")
}
