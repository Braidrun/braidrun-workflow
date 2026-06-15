package com.fartech.agents.commons

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import com.fartech.ftapp2.commonsKt.AnsiColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Multi-tier on-error fallback wrapper for [PromptExecutor].
 *
 * ## Motivation
 *
 * Koog 0.8.0's [ai.koog.prompt.executor.llms.MultiLLMPromptExecutor] and
 * [ai.koog.prompt.executor.llms.RoutingLLMPromptExecutor] both expose a
 * single-level fallback via `FallbackPromptExecutorSettings`, but that
 * fallback only fires when **no client is registered for the requested
 * provider** — not when a client is registered and errors out mid-request.
 *
 * In production we observe transient outages (OpenRouter 5xx waves,
 * Anthropic tool-use regressions, DeepSeek capacity spikes) where the
 * primary provider is wired and *responds*, but with an error. The built-in
 * fallback is silent in that case; the retrying client retries within the
 * same provider; and the agent run bubbles up the original exception.
 *
 * This wrapper adds a per-call on-error cascade: try the primary executor,
 * catch any [LLMClientException] (or wrapped I/O error), try the next
 * executor in the tier list, repeat until success or all tiers exhaust.
 *
 * ## What's in scope
 *
 * - `execute(Prompt, LLModel, List<ToolDescriptor>)` — list-of-messages path.
 * - `executeStreaming(...)` — streams from the first tier that emits without
 *   error; if the **first frame** already errors, we fall through. Once
 *   streaming has emitted any frame we do NOT switch tiers — that would
 *   require buffering the stream and re-emitting a second provider's frames
 *   with the same stream ID, which is both lossy (reasoning/tool-call deltas
 *   can't be restated mid-session) and confusing (the client would see a
 *   provider switch mid-message).
 * - `executeMultipleChoices(...)` — same semantic as `execute`.
 * - `moderate(...)` — tiered; a moderation outage in tier 1 falls through.
 *
 * ## What's explicitly NOT in scope
 *
 * - **Cross-model fallback fitness.** If tier 1 is Anthropic Claude and
 *   tier 2 is OpenRouter DeepSeek, the downstream prompt (tool schemas,
 *   reasoning instructions, structured-output shape) must make sense on
 *   both. This class does not coerce the model parameter per tier — the
 *   caller passes `LLModel` and that model is used on every tier. A caller
 *   wanting provider-specific model selection should compose multiple
 *   executors upstream of this wrapper, not request it at runtime.
 * - **Cancellation.** [CancellationException] is rethrown immediately on
 *   every tier so cooperative cancellation works. Only real errors trigger
 *   tier advancement.
 * - **Warmup / health-check prefetch.** A failing tier stays in the list;
 *   subsequent calls will re-probe it. If a provider is down for an
 *   extended period the caller should reconfigure rather than rely on us
 *   to permanently skip it.
 *
 * ## Configuration
 *
 * Callers construct a [CascadingFallbackPromptExecutor] from an ordered
 * list of [PromptExecutor]s. Tier 0 is the preferred provider; subsequent
 * tiers are tried in order on error.
 *
 * Typical wiring (see [createPromptExecutor] in `PromptExecutorFactory.kt`):
 *
 * ```
 * CascadingFallbackPromptExecutor(
 *     listOf(
 *         primary,           // Anthropic via MultiLLMPromptExecutor
 *         secondary,         // OpenRouter route
 *         emergency,         // DeepSeek route
 *     )
 * )
 * ```
 *
 * Each tier can itself be a `MultiLLMPromptExecutor` with its own retry
 * policy — this wrapper operates above the retry layer, so retries are
 * exhausted within a tier before the cascade kicks in.
 */
class CascadingFallbackPromptExecutor(
    private val tiers: List<PromptExecutor>,
) : PromptExecutor() {

    init {
        require(tiers.isNotEmpty()) { "CascadingFallbackPromptExecutor requires at least one tier" }
    }

    // Koog 1.0.0 narrowed PromptExecutorAPI.execute return from
    // `List<Message.Response>` to `Message.Assistant` (single message with
    // multi-part response). executeMultipleChoices switched from
    // `List<LLMChoice>` (a list of lists) to a single `LLMChoice`
    // (= `List<Message.Assistant>` typealias). Our cascade wrapper now
    // produces the post-1.0.0 shapes directly.
    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant = cascade("execute") { executor ->
        executor.execute(prompt, model, tools)
    }

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): LLMChoice = cascade("executeMultipleChoices") { executor ->
        executor.executeMultipleChoices(prompt, model, tools)
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = flow {
        // Unlike execute/executeMultipleChoices we can't call `cascade` here
        // because the body is non-suspend and the flow is cold. We iterate
        // tiers inside the flow builder. On the first tier that emits *any*
        // frame, we commit and stream to completion.
        var lastError: Throwable? = null
        for ((idx, executor) in tiers.withIndex()) {
            var emittedAny = false
            try {
                executor.executeStreaming(prompt, model, tools).collect { frame ->
                    emittedAny = true
                    emit(frame)
                }
                return@flow
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                if (emittedAny) {
                    // We cannot retry on a different provider mid-stream; the
                    // first frames have already been emitted. Propagate.
                    throw t
                }
                // Same retry filter as the sync paths — don't cascade through
                // JVM Errors or programming errors. Streaming-specific IO
                // errors (socket reset before first frame, handshake
                // failure) are caught by the generic `RuntimeException`
                // branch in [isRetryableAcrossProviders].
                if (!isRetryableAcrossProviders(t)) {
                    logProgress(
                        AnsiColor.YELLOW,
                        "CascadeFallback",
                        "executeStreaming tier=$idx raised non-retryable ${t::class.simpleName}; aborting cascade"
                    )
                    throw t
                }
                logProgress(
                    AnsiColor.YELLOW,
                    "CascadeFallback",
                    "executeStreaming tier=$idx/${tiers.lastIndex} failed: ${t.message?.take(200)}"
                )
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("CascadingFallbackPromptExecutor: all tiers failed without error")
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        cascade("moderate") { executor ->
            executor.moderate(prompt, model)
        }

    override suspend fun models(): List<LLModel> {
        // Return the union of models across tiers. Failures from a single
        // tier are logged but don't fail the aggregate (models discovery
        // is a best-effort diagnostic API).
        val acc = mutableListOf<LLModel>()
        tiers.forEachIndexed { idx, executor ->
            try {
                acc.addAll(executor.models())
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                logProgress(
                    AnsiColor.YELLOW,
                    "CascadeFallback",
                    "models() tier=$idx failed: ${t.message?.take(200)}"
                )
            }
        }
        return acc.distinct()
    }

    override fun close() {
        // Continue closing every tier even if one fails; surface each failure
        // so operators can see resource leaks (e.g. Mongo session close timeout)
        // instead of them disappearing into `runCatching { }`.
        tiers.forEachIndexed { idx, executor ->
            runCatching { executor.close() }.onFailure { t ->
                logProgress(
                    AnsiColor.YELLOW,
                    "CascadeFallback",
                    "close() tier=$idx failed (${t::class.simpleName}): ${t.message?.take(200) ?: "<no message>"}"
                )
            }
        }
    }

    private suspend inline fun <T> cascade(
        op: String,
        block: (PromptExecutor) -> T,
    ): T {
        var lastError: Throwable? = null
        for ((idx, executor) in tiers.withIndex()) {
            try {
                return block(executor)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // Only cascade on transport/provider-level errors. If the
                // last tier throws we rethrow; this preserves the original
                // exception shape at the top of the stack.
                if (!isRetryableAcrossProviders(t)) {
                    logProgress(
                        AnsiColor.YELLOW,
                        "CascadeFallback",
                        "$op tier=$idx raised non-retryable ${t::class.simpleName}; aborting cascade"
                    )
                    throw t
                }
                logProgress(
                    AnsiColor.YELLOW,
                    "CascadeFallback",
                    "$op tier=$idx/${tiers.lastIndex} failed (${t::class.simpleName}): ${t.message?.take(200)}"
                )
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException(
            "CascadingFallbackPromptExecutor: all tiers failed without error"
        )
    }

    /**
     * Decide whether an error from one provider is worth retrying against a
     * different provider. We cascade on:
     *   - [LLMClientException] (HTTP 4xx/5xx from provider, rate limits,
     *     malformed responses);
     *   - [java.io.IOException] and friends (socket timeouts, DNS failures);
     *   - generic [RuntimeException] — conservative but matches operational
     *     experience where wrapper layers re-throw provider errors as plain
     *     IllegalStateException.
     *
     * We do NOT cascade on:
     *   - [IllegalArgumentException] (bad input);
     *   - [NotImplementedError] (operation not supported, cross-provider
     *     retry wouldn't help);
     *   - Anything else extending [Error] directly — [OutOfMemoryError],
     *     [StackOverflowError], assertion failures etc. are process-wide
     *     problems; trying the next tier risks hiding a crash that
     *     operators need to see.
     */
    internal fun isRetryableAcrossProviders(t: Throwable): Boolean = when (t) {
        is IllegalArgumentException -> false
        is NotImplementedError -> false
        is LLMClientException -> true
        is java.io.IOException -> true
        is RuntimeException -> true
        // `Throwable` that isn't `RuntimeException` / `IOException` /
        // `LLMClientException` is almost certainly a JVM `Error`. Don't
        // cascade — let it propagate to the outer run so operators see
        // the crash. This includes `Error` and its subclasses.
        else -> false
    }
}
