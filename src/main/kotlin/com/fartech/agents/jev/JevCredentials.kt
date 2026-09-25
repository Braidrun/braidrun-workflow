package com.fartech.agents.jev

import com.fartech.agents.commons.resolveConfiguredApiKeyFromParams
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.parameter
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

// ============================================================================
// Provider identity / env names
// ============================================================================

/**
 * Provider ids that mean TypeSafe Jev, canonical first. Mirrors
 * `providerKeyAliases` in AgentModels (→ params `typesafe_api_key`,
 * `typesafe_ai_api_key`, `jev_api_key`, and `llm_provider_keys[typesafe|typesafe_ai|jev]`).
 */
val TYPESAFE_PROVIDER_ALIASES: List<String> = listOf(TYPESAFE_PROVIDER_ID, "typesafe_ai", "jev")

/** API key env var (official SDK name). Only read when the host enables the env fallback. */
const val TYPESAFE_API_KEY_ENV = "TYPESAFE_API_KEY"

/** Base URL env var (official SDK name). Operator setting; never taken from workflow parameters. */
const val TYPESAFE_BASE_URL_ENV = "TYPESAFE_BASE_URL"

/** Default model env var (official SDK name). */
const val TYPESAFE_DEFAULT_MODEL_ENV = "TYPESAFE_DEFAULT_MODEL"

/** Runtime parameter (`--param typesafe_model=...`) choosing the default Jev model. */
const val TYPESAFE_MODEL_PARAM = "typesafe_model"

/** Thrown (as IllegalArgumentException) when a TypeSafe provider id is used as a chat model. */
const val JEV_NOT_A_CHAT_MODEL_MESSAGE =
    "TypeSafe Jev is a decision model, not a chat model. Use it via classifier.jev or repeat_until.jev."

/** Message of the [JevApiException.Kind.MISSING_CREDENTIALS] error. */
const val JEV_MISSING_CREDENTIALS_MESSAGE =
    "TypeSafe API key is not configured. Set TYPESAFE_API_KEY, pass --param typesafe_api_key=..., " +
        "or connect \"TypeSafe (Jev)\" in the Braidrun credential center."

/** True for `typesafe`, `typesafe_ai` (also `typesafe-ai`) and `jev`, case-insensitive. */
fun isTypeSafeProviderId(provider: String?): Boolean {
    val normalized = provider?.trim()?.lowercase()?.replace('-', '_') ?: return false
    return normalized in TYPESAFE_PROVIDER_ALIASES
}

// ============================================================================
// Host credentials
// ============================================================================

/**
 * TypeSafe credentials injected by the host (e.g. the web credential vault) through
 * the `WorkflowExecutor` constructor. Deliberately not a data class: [toString]
 * must never print the key.
 */
class JevCredentials(
    val apiKey: String,
    val defaultModel: String? = null,
) {
    override fun toString(): String = "JevCredentials(apiKey=***, defaultModel=$defaultModel)"

    override fun equals(other: Any?): Boolean =
        other is JevCredentials && other.apiKey == apiKey && other.defaultModel == defaultModel

    override fun hashCode(): Int = 31 * apiKey.hashCode() + (defaultModel?.hashCode() ?: 0)
}

/** Where the resolved API key came from (for diagnostics — never the key itself). */
enum class JevKeySource { HOST, PARAMETERS, ENVIRONMENT }

/**
 * Everything one Jev call needs: key, model and base URL, resolved by [resolveJevCall].
 * [toString] redacts the key.
 */
class JevResolvedCall(
    val apiKey: String,
    val model: String,
    val baseUrl: String,
    val keySource: JevKeySource,
) {
    /** [defaults] with this call's [baseUrl] (timeouts / retries stay as given). */
    fun clientSettings(defaults: JevClientSettings = JevClientSettings()): JevClientSettings =
        defaults.copy(baseUrl = baseUrl)

    override fun toString(): String =
        "JevResolvedCall(apiKey=***, model=$model, baseUrl=$baseUrl, keySource=$keySource)"
}

// ============================================================================
// Resolution (single source of truth for every Jev call)
// ============================================================================

/**
 * Resolves the API key, model and base URL for one Jev call.
 *
 * API key (first non-blank wins):
 * 1. host [credentials] (`jevCredentials` constructor argument, e.g. the web vault);
 * 2. executor base [parameters]: `typesafe_api_key` / `typesafe_ai_api_key` / `jev_api_key`,
 *    then `llm_provider_keys[typesafe|typesafe_ai|jev]` — params only, never the process env;
 * 3. `TYPESAFE_API_KEY` from [env], and only when [envKeyFallback] is true (the web host
 *    disables it so an operator key never silently pays for users' steps).
 *
 * Model: [stepModel] → `credentials.defaultModel` → param `typesafe_model` →
 * env `TYPESAFE_DEFAULT_MODEL` → [JEV_DEFAULT_MODEL].
 *
 * Base URL: env `TYPESAFE_BASE_URL` → [TYPESAFE_DEFAULT_BASE_URL]. Never from parameters,
 * so a workflow author cannot redirect the key to a host of their choosing.
 * `TYPESAFE_DEFAULT_MODEL` / `TYPESAFE_BASE_URL` are honored even when [envKeyFallback] is false.
 *
 * @throws JevApiException kind [JevApiException.Kind.MISSING_CREDENTIALS] when no key resolves.
 */
internal fun resolveJevCall(
    stepModel: String?,
    credentials: JevCredentials?,
    parameters: List<ConfigurationParameter>,
    envKeyFallback: Boolean,
    env: Map<String, String> = System.getenv(),
): JevResolvedCall {
    val hostKey = credentials?.apiKey?.trim()?.takeIf { it.isNotEmpty() }
    val (apiKey, source) = when {
        hostKey != null -> hostKey to JevKeySource.HOST
        else -> {
            val paramKey = resolveTypeSafeKeyFromParams(parameters)
            val envKey = if (envKeyFallback) env[TYPESAFE_API_KEY_ENV]?.trim()?.takeIf { it.isNotEmpty() } else null
            when {
                paramKey != null -> paramKey to JevKeySource.PARAMETERS
                envKey != null -> envKey to JevKeySource.ENVIRONMENT
                else -> throw JevApiException(
                    kind = JevApiException.Kind.MISSING_CREDENTIALS,
                    status = null,
                    message = JEV_MISSING_CREDENTIALS_MESSAGE,
                )
            }
        }
    }

    val model = sequenceOf(
        stepModel,
        credentials?.defaultModel,
        stringParameter(parameters, TYPESAFE_MODEL_PARAM),
        env[TYPESAFE_DEFAULT_MODEL_ENV],
    ).firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) } ?: JEV_DEFAULT_MODEL

    return JevResolvedCall(
        apiKey = apiKey,
        model = model,
        baseUrl = resolveBaseUrl(env),
        keySource = source,
    )
}

private fun resolveTypeSafeKeyFromParams(parameters: List<ConfigurationParameter>): String? {
    // A malformed parameter value must not surface in an exception message (it may be the key).
    val keys = runCatching { parameters.parameter("llm_provider_keys", mapOf<String, String>()) }
        .getOrDefault(emptyMap())
    return runCatching { resolveConfiguredApiKeyFromParams(parameters, TYPESAFE_PROVIDER_ID, keys) }
        .getOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

private fun stringParameter(parameters: List<ConfigurationParameter>, key: String): String? =
    runCatching { parameters.parameter(key, "") }.getOrNull()

private fun resolveBaseUrl(env: Map<String, String>): String {
    val configured = env[TYPESAFE_BASE_URL_ENV]?.trim()?.takeIf { it.isNotEmpty() }
        ?: return TYPESAFE_DEFAULT_BASE_URL
    if (configured.startsWith("https://", ignoreCase = true) || configured.startsWith("http://", ignoreCase = true)) {
        return configured
    }
    logger.warn { "Ignoring $TYPESAFE_BASE_URL_ENV: not an http(s) URL; using $TYPESAFE_DEFAULT_BASE_URL" }
    return TYPESAFE_DEFAULT_BASE_URL
}
