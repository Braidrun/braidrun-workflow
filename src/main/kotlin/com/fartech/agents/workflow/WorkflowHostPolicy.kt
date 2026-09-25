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
 * Deliberately not a ConfigurationParameter: those are user-controlled on the web.
 */
object WorkflowHostPolicy {

    @Volatile
    private var codeStepExecutorRequired = false

    /** True once the host required every `code:` step to go through an injected executor. */
    val requiresCodeStepExecutor: Boolean
        get() = codeStepExecutorRequired

    /** One-way: a host cannot relax this after startup. */
    fun requireCodeStepExecutor() {
        codeStepExecutorRequired = true
    }

    internal fun resetForTests() {
        codeStepExecutorRequired = false
    }
}
