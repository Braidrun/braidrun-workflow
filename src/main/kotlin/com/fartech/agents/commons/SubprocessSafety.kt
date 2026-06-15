package com.fartech.agents.commons

import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Small, dependency-free helpers for spawning **short-lived** auxiliary subprocesses safely
 * (e.g. `git clone`, `which`, `xcodegen --version`, `tesseract --version`).
 *
 * For the heavy lifting (code-step / shell-tool sandbox, Docker isolation, env allowlisting,
 * stream caps) prefer [com.fartech.agents.tools.exec.SubprocessExecutor]. This file fills the
 * remaining gap: small auxiliary spawns inside library code that historically were written as
 * a one-liner `ProcessBuilder(...).start().waitFor()` and forgot **timeout** and/or **stream
 * draining**. Both omissions are real hang vectors:
 *
 *   * A bare `process.waitFor()` blocks forever — an attacker-supplied (or simply
 *     wedged) child process pins a JVM thread until the JVM exits.
 *   * A naive `process.inputStream.readText()` blocks the calling thread waiting for
 *     EOF. If the child writes > pipe-buffer (typically 64 KiB) **before** exiting,
 *     the pipe fills, the child blocks on its `write`, and `readText()` waits forever.
 *     Adding `redirectErrorStream(true)` doesn't help — it merges the streams but does
 *     not change who blocks.
 *
 * [runCapturedWithTimeout] solves both: drains stdout on a background daemon thread with a
 * byte cap (mirrors the `MCPServerManager.runPrepareStep` pattern), then `waitFor(timeout)`s
 * and forcibly kills on timeout.
 */
internal object SubprocessSafety {

    /** Same single-source value [com.fartech.agents.tools.exec.SubprocessExecutor] uses. */
    internal const val DEFAULT_MAX_OUTPUT_BYTES = 8 * 1024 * 1024 // 8 MiB

    data class Result(
        /** Process exit code, or `null` if the process was killed on timeout. */
        val exitCode: Int?,
        /** Combined stdout (and stderr if [ProcessBuilder.redirectErrorStream] was set). Truncated at [maxOutputBytes]. */
        val output: String,
        val timedOut: Boolean
    )

    /**
     * Spawn a process, drain stdout (and stderr if `redirectErrorStream=true`) on a daemon
     * thread up to [maxOutputBytes], then `waitFor(timeoutSeconds)`. On timeout the process is
     * `destroyForcibly()`'d and the result has `exitCode = null, timedOut = true`.
     *
     * The drainer keeps reading-and-discarding past the cap so the producer doesn't wedge
     * on a full OS pipe.
     */
    fun runCapturedWithTimeout(
        command: List<String>,
        workDir: File? = null,
        timeoutSeconds: Long = 30,
        maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
        redirectErrorStream: Boolean = true,
        env: Map<String, String>? = null
    ): Result {
        require(command.isNotEmpty()) { "command cannot be empty" }
        require(timeoutSeconds > 0) { "timeoutSeconds must be > 0" }
        require(maxOutputBytes > 0) { "maxOutputBytes must be > 0" }

        val pb = ProcessBuilder(command).redirectErrorStream(redirectErrorStream)
        if (workDir != null) pb.directory(workDir)
        if (env != null) {
            val envMap = pb.environment()
            envMap.clear()
            envMap.putAll(env)
        }
        val process = pb.start()

        val captured = StringBuilder()
        val stdoutDrainer = Thread({ drainBounded(process.inputStream, captured, maxOutputBytes) }, "subprocess-stdout-drain")
        stdoutDrainer.isDaemon = true
        stdoutDrainer.start()

        val stderrDrainer: Thread? = if (!redirectErrorStream) {
            // Keep stderr drained even if the caller doesn't care, otherwise a chatty
            // stderr pinned by a full pipe would still hang the child.
            Thread({ drainBounded(process.errorStream, captured, maxOutputBytes) }, "subprocess-stderr-drain").also {
                it.isDaemon = true
                it.start()
            }
        } else null

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            stdoutDrainer.join(2_000)
            stderrDrainer?.join(2_000)
            return Result(
                exitCode = null,
                output = renderOutput(captured, maxOutputBytes),
                timedOut = true
            )
        }
        stdoutDrainer.join(2_000)
        stderrDrainer?.join(2_000)
        return Result(
            exitCode = process.exitValue(),
            output = renderOutput(captured, maxOutputBytes),
            timedOut = false
        )
    }

    /**
     * Convenience: run a short-lived "probe" command (e.g. `which python3`) that we only
     * care whether it exited 0. Returns `false` on non-zero exit, exception, or timeout.
     */
    fun runProbe(
        command: List<String>,
        timeoutSeconds: Long = 5
    ): Boolean = try {
        val r = runCapturedWithTimeout(command, timeoutSeconds = timeoutSeconds)
        !r.timedOut && r.exitCode == 0
    } catch (_: Exception) {
        false
    }

    private fun drainBounded(stream: InputStream, sink: StringBuilder, capBytes: Int) {
        try {
            val buf = ByteArray(8 * 1024)
            stream.use { s ->
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    synchronized(sink) {
                        if (sink.length < capBytes) {
                            val take = minOf(n, capBytes - sink.length)
                            sink.append(String(buf, 0, take, Charsets.UTF_8))
                        }
                    }
                    // continue draining past cap to keep producer unblocked
                }
            }
        } catch (_: Exception) {
            // pipe closed (process killed); return what we have
        }
    }

    private fun renderOutput(captured: StringBuilder, capBytes: Int): String {
        val snapshot = synchronized(captured) { captured.toString() }
        return if (snapshot.length >= capBytes) "$snapshot\n... [truncated at $capBytes bytes]" else snapshot
    }
}
