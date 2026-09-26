# Architecture

Braidrun Workflow has four runtime layers:

1. YAML parsing and validation via `WorkflowParser`.
2. Execution orchestration via `WorkflowExecutor`.
3. Agent construction from `AgentDefinition` and `AgentPresetRegistry`.
4. Tool registration through named tool groups and MCP-compatible tool surfaces.

Workflow YAML resolves presets into runtime parameters, and the executor builds a Koog-backed agent from those parameters.

Code, shell, Git, browser, Claude Code, and Codex subprocesses run through the shared subprocess executor. Use `subprocess_mode=native` for local development and `subprocess_mode=docker` for production-style isolation.

## LLM client and executor chain

The embedded runtime is built on Koog 1.3.0 (stable stream; beta-only modules
such as `agents-ext`, `agents-mcp` and the Google / DeepSeek / Mistral /
DashScope clients at 1.3.0-beta, see `build.gradle.kts`). A Koog agent sends
every request through this stack, outermost first:

```text
MeteringSafeCachedPromptExecutor(cache = ToolResultMediaBypassingPromptCache(<cache_policy>))
  └─ ProviderValidatingPromptExecutor
       └─ MultiLLMPromptExecutor                 (models + fallback)
            │   or CascadingFallbackPromptExecutor over one MultiLLMPromptExecutor per tier
            │   (cascade_fallback_enabled=true with cascade_fallbacks)
            └─ per provider: RetryingLLMClient
                   └─ ToolResultMediaAdaptingLLMClient
                        └─ ModelParamsSanitizingLLMClient
                             └─ PromptCachingLLMClient
                                  └─ Koog provider client (Anthropic, OpenAI, Google, ...)
                                     (direct Anthropic: inside AnthropicStreamUsageRecoveringClient)
```

- `createLLMClient` (`AgentModels.kt`) builds the provider client, checks its
  base URL with `LlmEndpointPolicy`, resolves keys (environment keys are
  withheld from custom base URLs under
  `requireExplicitKeysForCustomLlmEndpoints()`), and wraps it in
  `ModelParamsSanitizingLLMClient` and `PromptCachingLLMClient` (Anthropic
  prompt-cache breakpoints; see [Prompt Caching](PROMPT_CACHING.md)). The
  direct Anthropic client is also wrapped in
  `AnthropicStreamUsageRecoveringClient`, which restores the cache counts Koog
  drops from streamed responses. All of these are `DelegatingLLMClient`s.
- `createPromptExecutor` (`PromptExecutorFactory.kt`) adds
  `ToolResultMediaAdaptingLLMClient` and `RetryingLLMClient` (`retry_*`
  parameters, else `RetryConfig.PRODUCTION`) to every client of the primary
  and cascade tiers, composes them, and adds the provider-validation and cache
  layers. `disable_cache_for_streaming=true` drops the cache layer; streaming
  requests bypass it either way.
- `ProviderValidatingPromptExecutor` normalizes reasoning-only assistant
  messages that OpenAI-compatible APIs would reject.

### Per-request parameter fitting

The prompt's `LLMParams` are shared by the primary model, the fallback model
and every cascade tier, so `ModelParamsSanitizingLLMClient` fits them to the
model each request actually goes to (execute, streaming, multiple choices and
moderation):

- `temperature` is dropped when the model lacks the `temperature` capability or
  `ModelQuirks.rejectsSampling` matches (Claude Opus 4.7+, Sonnet / Fable /
  Mythos 5.x, Kimi K3).
- A forced tool choice (`Required` / `Named`) becomes `Auto` when the model
  lacks `tool_choice` or thinks by default (Claude Opus / Sonnet / Fable /
  Mythos 5.x).
- A direct-Anthropic request without `max_tokens` gets 16,000 (64,000 when
  streaming), capped by the model's output ceiling, instead of Koog's 2048.

### Metering

Token usage, cost and `llm_call_completed` events come from Koog's LLM-call
events. The multi-tool strategies (`just_work_parallel`, `single_run*`) send
single-choice rounds through `requestLLM()`, which fires those events and runs
the response processors (weak-model tool-call fix, long-term memory,
tracing). Rounds with `num_choices > 1` use `executeMultipleChoices`, which is
unmetered upstream; hosts that bill per token call
`WorkflowHostPolicy.requireSingleLlmChoice()`. `MeteringSafeCachedPromptExecutor`
reports cache hits with no token counts and the response metadata flag
`braidrun_prompt_cache_hit=true`, so a hit is never billed twice, and keys its
entries by a SHA-256 of model, full tool descriptors and prompt.

Usage is normalized across providers (`LlmTokenUsage`): `inputTokens` excludes
provider prompt-cache reads and writes, which `llm_call_completed` /
`token_usage` events carry as `cacheReadTokens` / `cacheCreationTokens`. See
[Prompt Caching and Token Accounting](PROMPT_CACHING.md).

### Multimodal tool results

`browser_screenshot` and MCP tools can return image parts.
`ToolResultMediaAdaptingLLMClient` keeps an image only when the route and model
can show it and the MIME type is accepted, up to
`tool_result_images_max_per_request` images; every other image becomes a text
placeholder such as `[image omitted (image/png, 245.3 KB): <reason>]`,
never base64. Prompts carrying tool-result images bypass the prompt cache.

| Route | Tool-result images |
| --- | --- |
| Anthropic (vision models) | Sent as `image` blocks in `tool_result` (png, jpeg, gif, webp) |
| Google Gemini 3+ (vision) | Sent as `inlineData` in `functionResponse.parts` (bytes only) |
| Google Gemini before 3 | Placeholder |
| OpenAI Responses API (not Azure): models that declare `openai.responses` but not `openai.completions` (the `-pro` and `-codex` entries), or explicit `OpenAIResponsesParams` | Sent as `input_image` in `function_call_output` |
| OpenAI Chat Completions (every GPT model that also declares `openai.completions`), any `openai.azure.com` base URL | Placeholder |
| OpenRouter, DeepSeek, DashScope, Mistral, Ollama, other clients | Placeholder |
| Non-vision models, durable (non-memory) persistence | Placeholder |
