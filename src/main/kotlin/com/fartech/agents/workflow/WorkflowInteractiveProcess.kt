package com.fartech.agents.workflow

import java.io.InputStream
import java.io.OutputStream

/** Trusted supervised process channel, not a JVM ProcessBuilder handle. The concrete runner
 * broker supplies registration, gated I/O and independent stop proof through the official wire.
 * Closing means request scoped stop; it is never a confirmed process-tree termination receipt.
 */
interface WorkflowInteractiveProcess : AutoCloseable {
    val registrationId: String
    val stdin: OutputStream
    val stdout: InputStream
    val stderr: InputStream
    suspend fun awaitExit(): Int
}
