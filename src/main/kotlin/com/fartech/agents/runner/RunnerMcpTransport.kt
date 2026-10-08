package com.fartech.agents.runner

import com.fartech.agents.workflow.WorkflowInteractiveProcess
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

/** SDK JSON-RPC bytes travel through the registered supervisor channel, never a bare process. */
class RunnerMcpTransport private constructor(private val process: WorkflowInteractiveProcess, private val transport: Transport) : Transport by transport {
    constructor(process: WorkflowInteractiveProcess) : this(process,
        StdioClientTransport(process.stdout.asSource().buffered(), process.stdin.asSink().buffered(), process.stderr.asSource().buffered()))
    override suspend fun close() { try { transport.close() } finally { process.close() } }
}
