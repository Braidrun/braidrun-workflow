package com.fartech.agents.workflow

/**
 * Process-wide guarantees a multi-tenant host (braidrun-web) declares once at startup.
 *
 * A [WorkflowExecutor] built without a `codeStepExecutor` runs `code:` steps with a bare
 * ProcessBuilder on the host. That is the CLI / library default, but inside a server JVM it
 * would bypass the Docker sandbox — and executors are not only built by the host: the agent
 * `workflow` tool ([WorkflowTools]) and tool registries assembled outside any executor build
 * their own. Declaring [requireCodeStepExecutor] makes every such executor in the process
 * refuse `code:` steps instead.
 *
 * Koog LLM clients run in-process, so a user-supplied `llm_config` base URL (or a provider
 * default such as LM Studio's `http://localhost:1234`) is a request the host JVM itself makes.
 * Declaring [requirePublicLlmEndpoints] makes every chat client and RAG embedder the engine
 * builds refuse anything but https on a public host
 * (see [com.fartech.agents.commons.LlmEndpointPolicy]).
 *
 * A public https endpoint can still belong to anyone, so a user-supplied base URL must never
 * receive the host's own provider keys (process environment such as `OPENAI_API_KEY`).
 * Declaring [requireExplicitKeysForCustomLlmEndpoints] makes a client whose base URL differs
 * from the provider default use only keys supplied with the run (`*_api_key` parameters,
 * `llm_provider_keys`). It is separate from [requirePublicLlmEndpoints] so a private deployment
 * that allows internal endpoints still never ships its environment keys to user-chosen hosts.
 *
 * The skill subsystem has the same problem: skill hook handler scripts and per-skill MCP
 * servers run with a plain ProcessBuilder in this JVM, and the mutating skill tools
 * ([com.fartech.agents.tools.SkillAdminTools]) let a model install or delete skills on the
 * host. [restrictSkillSideEffects] turns all of that off for every agent in the process.
 *
 * Deliberately not a ConfigurationParameter: those are user-controlled on the web. A
 * `skills_config` flag (e.g. `hookScriptExecutionEnabled`) can only narrow these, never widen.
 */
object WorkflowHostPolicy {

    @Volatile
    private var codeStepExecutorRequired = false

    @Volatile
    private var publicLlmEndpointsRequired = false

    @Volatile
    private var explicitKeysForCustomLlmEndpointsRequired = false

    @Volatile
    private var skillSideEffectsRestricted = false

    @Volatile
    private var skillMcpAutoStartOptedIn = false

    @Volatile
    private var singleLlmChoiceRequired = false

    /** True once the host required every `code:` step to go through an injected executor. */
    val requiresCodeStepExecutor: Boolean
        get() = codeStepExecutorRequired

    /** True once the host required every LLM endpoint to be https on a public host. */
    val requiresPublicLlmEndpoints: Boolean
        get() = publicLlmEndpointsRequired

    /** True once the host forbade environment API keys for non-default LLM base URLs. */
    val requiresExplicitKeysForCustomLlmEndpoints: Boolean
        get() = explicitKeysForCustomLlmEndpointsRequired

    /** True once the host capped every agent LLM request at one choice. */
    val requiresSingleLlmChoice: Boolean
        get() = singleLlmChoiceRequired

    /** One-way: a host cannot relax this after startup. */
    fun requireCodeStepExecutor() {
        codeStepExecutorRequired = true
    }

    /** One-way: a host cannot relax this after startup. */
    fun requirePublicLlmEndpoints() {
        publicLlmEndpointsRequired = true
    }

    /** One-way: a host cannot relax this after startup. */
    fun requireExplicitKeysForCustomLlmEndpoints() {
        explicitKeysForCustomLlmEndpointsRequired = true
    }

    /**
     * One-way: every agent built in this process ignores a workflow's `num_choices > 1`.
     * Multi-choice rounds go through Koog's `executeMultipleChoices`, which fires no
     * LLM-call events, so on a metered host a user-set `num_choices` would switch off
     * token / cost / quota accounting while the provider bills every choice.
     */
    fun requireSingleLlmChoice() {
        singleLlmChoiceRequired = true
    }

    /** True once the host declared [restrictSkillSideEffects]. */
    val restrictsSkillSideEffects: Boolean
        get() = skillSideEffectsRestricted

    /**
     * One-way: from now on no skill hook handler script runs, no per-skill MCP server is
     * prepared or started, the mutating skill tools are neither registered nor callable,
     * and host-initiated ClawHub installs drop the skill's `hooks/` and `mcp-servers/`.
     * Static HOOK.md bodies are text, not code, and stay subject to `hooksEnabled`.
     */
    fun restrictSkillSideEffects() {
        skillSideEffectsRestricted = true
    }

    /**
     * One-way host opt-in for per-skill MCP auto-start (`<skill>/mcp-servers/`), which is
     * off by default everywhere. Has no effect once [restrictSkillSideEffects] was declared.
     */
    fun allowSkillMcpAutoStart() {
        skillMcpAutoStartOptedIn = true
    }

    /** Whether a skill manager may prepare and start the per-skill MCP servers it discovers. */
    val allowsSkillMcpAutoStart: Boolean
        get() = skillMcpAutoStartOptedIn && !skillSideEffectsRestricted

    /** Whether skill hook handler scripts may run (still subject to `hookScriptExecutionEnabled`). */
    val allowsSkillHookScripts: Boolean
        get() = !skillSideEffectsRestricted

    /** Whether the mutating skill tools may be registered for, and called by, a model. */
    val allowsSkillAdminTools: Boolean
        get() = !skillSideEffectsRestricted

    internal fun resetForTests() {
        explicitKeysForCustomLlmEndpointsRequired = false
        codeStepExecutorRequired = false
        singleLlmChoiceRequired = false
        publicLlmEndpointsRequired = false
        skillSideEffectsRestricted = false
        skillMcpAutoStartOptedIn = false
    }
}
