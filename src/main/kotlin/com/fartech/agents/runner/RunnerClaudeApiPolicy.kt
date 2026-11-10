package com.fartech.agents.runner

import com.fartech.agents.tools.exec.SubprocessExecutor
import kotlinx.serialization.json.*

/** Explicit Claude-compatible routes. A workflow cannot redirect a scoped model key to an arbitrary host. */
object RunnerClaudeApiPolicy {
    const val CAPABILITY = "agent.claudeCodingPlan.v1"
    data class Route(val provider: String, val baseUrl: String, val modelPrefix: String)
    val routes = listOf(
        Route("glm_coding_plan", "https://open.bigmodel.cn/api/anthropic", "glm-"),
        Route("qwen_coding_plan", "https://coding.dashscope.aliyuncs.com/apps/anthropic", "qwen"),
        Route("qwen_token_plan", "https://token-plan.cn-beijing.maas.aliyuncs.com/apps/anthropic", "qwen"),
        Route("deepseek", "https://api.deepseek.com/anthropic", "deepseek-")
    )
    fun route(baseUrl: String?): Route? = routes.singleOrNull { it.baseUrl == baseUrl?.trim()?.trimEnd('/') }
    fun route(parameters: Map<String, JsonElement>): Route? = route(parameters["external_agent_claude_base_url"]?.jsonPrimitive?.contentOrNull)
    fun requireSelection(parameters: Map<String, JsonElement>, credential: Boolean = true): Route {
        val route = requireNotNull(route(parameters)) { "Runner Claude API endpoint is not an approved provider" }
        require(parameters["external_agent_claude_auth_mode"] == JsonPrimitive("api_key")) { "Coding Plan requires explicit API-key mode" }
        val model = parameters["external_agent_claude_model"]?.jsonPrimitive?.contentOrNull.orEmpty()
        require(model.length in 1..512 && model.lowercase().startsWith(route.modelPrefix) && model.none { it.isWhitespace() || it.code < 32 }) { "Coding Plan requires an explicit provider model" }
        for (key in listOf("external_agent_claude_oauth_token", "external_agent_claude_credentials_json", "external_agent_codex_auth_json"))
            require(parameters[key]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) { "Coding Plan cannot fall back to subscription credentials" }
        if (credential) require(parameters["anthropic_api_key"]?.jsonPrimitive?.contentOrNull?.matches(RunnerAdmission.CREDENTIAL_REFERENCE) == true) { "Coding Plan requires a scoped model-key reference" }
        return route
    }
    /** Runs at the final subprocess boundary, including refreshed key material at start. No ambient model aliases. */
    internal fun request(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecRequest {
        val route = route(request.env["ANTHROPIC_BASE_URL"]) ?: return request
        val modelIndex = request.command.indexOf("--model")
        require(modelIndex >= 0) { "Coding Plan CLI model missing" }
        val model = request.command.getOrNull(modelIndex + 1).orEmpty()
        require(model.lowercase().startsWith(route.modelPrefix)) { "Coding Plan CLI model differs from provider" }
        fun mapped(env: Map<String, String>): Map<String, String> {
            require(RunnerClaudeApiPolicy.route(env["ANTHROPIC_BASE_URL"]) == route) { "Coding Plan endpoint changed at start" }
            val key = env["ANTHROPIC_API_KEY"].orEmpty()
            require(key.isNotBlank() && (route.provider != "qwen_coding_plan" || key.startsWith("sk-sp-"))) { "Coding Plan credential invalid" }
            return env + listOf("ANTHROPIC_MODEL", "ANTHROPIC_DEFAULT_HAIKU_MODEL", "ANTHROPIC_DEFAULT_SONNET_MODEL",
                "ANTHROPIC_DEFAULT_OPUS_MODEL", "ANTHROPIC_SMALL_FAST_MODEL", "CLAUDE_CODE_SUBAGENT_MODEL").associateWith { model } +
                mapOf("ANTHROPIC_AUTH_TOKEN" to key, "ANTHROPIC_API_KEY" to "", "CLAUDE_CODE_OAUTH_TOKEN" to "")
        }
        val start = request.environmentAtStart
        return request.copy(env = mapped(request.env), environmentAtStart = start?.let { { mapped(request.env + it()) } })
    }
}
