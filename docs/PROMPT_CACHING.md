# Prompt Caching and Token Accounting

The engine has two independent caches:

| Cache | What it saves | Where | Config |
|---|---|---|---|
| **Provider prompt cache** (Anthropic `cache_control`) | Re-reading a stable prompt prefix bills at a fraction of the input price. | Anthropic's servers | `anthropic_prompt_caching` (default `true`) |
| **Response cache** | An identical request (same model, tools and prompt) replays the stored answer with no provider call. | This process, Redis or files | `cache_policy`, `memory_cache_max_entries`, … |

## Anthropic prompt caching

Every request to the direct Anthropic provider gets up to three 5-minute breakpoints, placed by `PromptCachingLLMClient`:

1. **Stable system prefix.** Placed on the last stable system block. Tools render before the system prompt, so this entry covers tools plus system and is shared by every request and run with the same tools and system prompt.
2. **Rolling tail in a tool loop.** Placed on the last block of a request that answers a tool call. The next round extends exactly this prefix, so it reads everything up to here and writes only the new assistant turn and tool results. Requests that are not tool-loop rounds get no tail marker, so a one-shot call never pays the write premium on a prefix nobody extends.
3. **Last tool.** A tools-only read point for steps that share a tool set but differ in system prompt. Writes are billed once per request however many breakpoints nest, so this one costs nothing extra.

Details that matter when you change prompt assembly:

- **The environment block is a volatile tail.** `buildAgent` sends the system prompt as two parts via `systemWithVolatileTail`: the stable prompt (skill catalog, operator prompt, hook content, bootstrap files), then `envInfo`, which contains `Date:` to the second. Only the first part is cached. Before this split, every agent build rewrote the whole system prefix. Providers without explicit caching still receive the single joined text, unchanged.
- **Anything that changes between requests must come after the cached prefix.** Put per-turn hints in the user input, not in the system prompt. braidrun-web's assistant moved its heuristic intent hint there for this reason.
- **Limits.** Anthropic allows at most 4 breakpoints per request, including markers a caller adds with `PromptCacheHints`. Caller markers count first, and the engine drops its own breakpoints (the last-tool one first) when slots run out. A 5-minute marker that renders before a 1-hour one is lengthened, because Anthropic requires the longer TTL to come first.
- **Minimum cacheable prefix.** 512 to 4096 tokens depending on the model. Shorter prefixes silently don't cache (`cache_creation_input_tokens: 0`, no error).
- **Provider safety.** Koog's Anthropic client throws `IllegalStateException` on a non-Anthropic `CacheControl`, and the Bedrock client throws on a non-Bedrock one. Because a prompt is shared with fallback and cascade tiers, `PromptCachingLLMClient` removes markers of another provider's type from every request.
- **Not covered by the automatic breakpoints:** OpenRouter's Claude models and other non-direct Anthropic routes.

Set `anthropic_prompt_caching: false` to send prompts without automatic breakpoints. Caller-supplied markers are still sent.

## Token accounting

`llm_call_completed` and `token_usage` events, and `LlmTokenUsage` (`ResponseMetaInfo.tokenUsage()`), normalize every provider to one convention:

| Field | Meaning |
|---|---|
| `inputTokens` | Prompt tokens billed at the full input rate. Excludes cache reads and writes. |
| `cacheReadTokens` | Prompt tokens served from the provider cache. |
| `cacheCreationTokens` | Prompt tokens written to the provider cache. |
| `outputTokens` | Output tokens billed at the output rate, reasoning included. |
| `totalTokens` | `inputTokens + outputTokens`. Cache reads and writes are not included. |

Event details also list `reasoning=N` when a provider reports reasoning separately (Gemini thinking). That share is already in `outputTokens`.

Total prompt size is `inputTokens + cacheReadTokens + cacheCreationTokens`.

| Provider | What Koog 1.3.0 reports | Normalization |
|---|---|---|
| Anthropic | `input_tokens` is the uncached remainder. Cache counts are in `metaInfo.metadata`, but only for non-streamed responses. | Passed through. `AnthropicStreamUsageRecoveringClient` restores the cache counts on streamed `StreamFrame.End` frames, which Koog drops. |
| Bedrock Converse | Same as Anthropic (`cacheReadInputTokens` / `cacheWriteInputTokens`). | Passed through. |
| Google Gemini | `promptTokenCount` *includes* `cachedContentTokenCount`. Thinking tokens (`thoughtsTokenCount`) are only in `totalTokenCount`. | The cached share moves from input to `cacheReadTokens`. `GeminiThinkingUsageClient` adds the thinking share (total − prompt − candidates) to `outputTokens`, because Google bills it at the output rate. |
| OpenAI-compatible | Cached counts are not surfaced. | The full prompt counts as input, so cached reads are over-billed but never under-billed. |

`StepMetrics.getInputTokens()` and the other `StepMetrics` sums skip the `token_usage` display duplicate, so each call counts once. Before, every Koog call was counted twice, including in the `sub_workflow_completed` totals.

### Prices (verified 2026-09-26)

These rates are in braidrun-web's `CostCalculationService`. They are listed here for integrators who price events themselves:

- **Anthropic cache reads:** 0.1× input, except Claude Fable 5.1 / Mythos 5.1 at 0.025× ($0.25/MTok) and Claude Opus 5.5 at 0.05× ($0.20/MTok).
- **Anthropic cache writes:** 1.25× input for the 5-minute TTL (2× for 1 hour).
- **Google:** every Gemini model on the pricing page bills cached input at 0.1×, and implicit caching has no write charge.

Sources: [Anthropic pricing](https://platform.claude.com/docs/en/about-claude/pricing) and [Gemini API pricing](https://ai.google.dev/gemini-api/docs/pricing).

## Response cache

`MeteringSafeCachedPromptExecutor` wraps Koog's `CachedPromptExecutor`, with three differences:

- **Key.** Entries are keyed by a SHA-256 over the provider, model id, full tool descriptors (parameter schemas included) and the prompt, with timestamps and cache markers removed. Koog's own key cannot serialize a cache marker at all (`SerializationException`), so before this change a `PromptCacheHints` marker failed the call. Koog's own key is a 32-bit hash that ignores the model: after a model switch, an identical prompt replayed the other model's answer. Each stored answer also records its digest, so a storage-key collision in a shared Redis or file cache is a miss rather than someone else's answer.
- **Hits are unmetered.** A cache hit reports no token usage and sets `braidrun_prompt_cache_hit=true` in its metadata.
- **Streaming bypasses it.** Koog's cached streaming replays a non-streaming call as frames, which turned live typing into one burst at the end. `disable_cache_for_streaming` is no longer needed for live output.

## Measuring

`PromptCachingHarnessTest` runs real agent loops and the real Koog Anthropic client, over HTTP, against a local fake of `/v1/messages`. The fake computes `usage.cache_read_input_tokens` / `cache_creation_input_tokens` from a model of Anthropic's cache: prefix match up to each breakpoint, 20-block lookback, and a minimum cacheable length.

```bash
./gradlew test --tests '*PromptCachingHarnessTest' -i
```

It prints per-request usage. With a ~1.1K-token tools + system prefix:

| Scenario | Result |
|---|---|
| 3-round tool loop | Round 2 reads the tools + system prefix. Round 3 reads all of round 2 (1,848 tokens). Billed input: 3,492 vs 5,544 units uncached (−37%). The saving grows with loop length and prefix size. |
| New agent build with a different env tail | The first request reads the 1,128-token tools + system prefix. |
| Date change, old single-block system prompt | 0 tokens read. |
| Date change, split system prompt | 1,086 tokens read. |
| Streamed rounds | Requests stay streamed, and every `StreamFrame.End` reports the cache counts the API returned. |
| Workflow steps | Step 2's `llm_call_completed` event carries the cache reads written by step 1. |

To check against the live API, run two identical requests within 5 minutes and expect `cache_read_input_tokens > 0` on the second. Keep a check like that wherever prompt assembly changes, because a new per-request byte in the prefix makes caching fail silently.

## Known limits

- **History across turns isn't cached.** The env tail renders before the conversation and changes every second. The tools + system prefix is still read on every turn.
- **1-hour writes are priced at the 5-minute rate.** braidrun-web prices every cache write at 1.25× because the engine only places 5-minute breakpoints. A caller that adds 1-hour markers with `PromptCacheHints` is under-billed 0.75× on those writes.
- **OpenAI-family cached counts aren't reported.** Koog 1.3.0 does not surface them.
