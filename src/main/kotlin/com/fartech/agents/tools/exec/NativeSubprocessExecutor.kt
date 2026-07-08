package com.fartech.agents.tools.exec

import com.fartech.agents.tools.exec.SubprocessExecutor.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Development-mode subprocess executor that wraps [ProcessBuilder].
 *
 * Zero external dependencies — no Docker daemon, no images. Satisfies constraint C1
 * ("dev mode needs only MongoDB"). Ignores Docker-specific fields in [ExecRequest]
 * (mounts, networkPolicy, memoryLimitMb, cpuLimit, imageHint).
 *
 * ## Environment-variable policy
 *
 * By default, the JVM's full environment is **not** inherited by the child process —
 * `ProcessBuilder` normally leaks every parent env var (including `AWS_SECRET_ACCESS_KEY`,
 * `DATABASE_URL`, OAuth tokens, etc.) which is unsafe for LLM-driven tools. Instead we:
 *
 * 1. `environment().clear()` everything inherited.
 * 2. Copy over only a minimal "safe" whitelist (`PATH`, `HOME`, `LANG`, `TZ`, temp-dir hints)
 *    so typical binaries still work.
 * 3. Apply [ExecRequest.env] on top, which the caller controls deliberately.
 *
 * Callers that legitimately need extra env vars should pass them via `request.env`. The
 * whitelist can be extended at deploy time via `BRAIDRUN_NATIVE_EXEC_ENV_ALLOWLIST`
 * (comma-separated list of additional var names).
 */
class NativeSubprocessExecutor : SubprocessExecutor {

    override suspend fun execute(request: ExecRequest): ExecResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()

        // Reject oversized stdin before spawning. An unbounded string written
        // to the process pipe pins the bytes in heap and wedges the writer
        // coroutine if the child is slow to drain. Same cap as the docker
        // executor so behaviour is consistent across modes.
        request.stdin?.let { stdin ->
            val byteCount = stdin.toByteArray(Charsets.UTF_8).size
            require(byteCount <= SubprocessExecutor.MAX_STDIN_BYTES) {
                "stdin payload (${byteCount} bytes) exceeds MAX_STDIN_BYTES=${SubprocessExecutor.MAX_STDIN_BYTES}"
            }
        }

        // Resolve working directory once, with a clear log line if the caller passed an
        // invalid directory (previously we silently fell back to "." which masked bugs).
        val workingDir = if (request.workingDir.isDirectory) {
            request.workingDir
        } else {
            logger.warn {
                "[NativeExec] workingDir '${request.workingDir}' is not a directory; falling back to current dir. " +
                    "Caller should ensure the directory exists before invoking the executor."
            }
            File(".")
        }

        var process: Process? = null
        try {
            val processBuilder = ProcessBuilder(request.command)
                .directory(workingDir)
                .redirectErrorStream(false)

            // Scrub inherited parent environment before injecting caller-controlled vars.
            applyEnvironmentPolicy(processBuilder, request.env)

            process = processBuilder.start()
            val proc = process

            // Pipe stdin if provided. Wrap in runCatching so a broken pipe (process exited
            // before consuming stdin) doesn't prevent us from collecting whatever output it
            // did produce.
            if (request.stdin != null) {
                runCatching {
                    proc.outputStream.bufferedWriter().use { it.write(request.stdin) }
                }.onFailure { e ->
                    logger.debug(e) { "[NativeExec] stdin write failed (process may have exited early)" }
                }
            }

            // Read stdout and stderr concurrently to avoid pipe buffer deadlock.
            // Cap each stream at SubprocessExecutor.MAX_STREAM_BYTES so a runaway
            // child can't OOM the JVM by spamming output; appends a marker on
            // truncation so callers can distinguish from legitimate empty output.
            val stdoutFuture = CompletableFuture.supplyAsync {
                readBoundedStream(proc.inputStream, request.stdoutLineCallback)
            }
            val stderrFuture = CompletableFuture.supplyAsync {
                readBoundedStream(proc.errorStream)
            }

            val completed = proc.waitFor(request.timeoutSeconds, TimeUnit.SECONDS)

            if (!completed) {
                destroyProcessTree(proc)
                val stdout = runCatching { stdoutFuture.get(TIMEOUT_STREAM_DRAIN_SECONDS, TimeUnit.SECONDS) }.getOrDefault("")
                val stderr = runCatching { stderrFuture.get(TIMEOUT_STREAM_DRAIN_SECONDS, TimeUnit.SECONDS) }.getOrDefault("")
                logger.warn { "[NativeExec] Process timed out after ${request.timeoutSeconds}s: ${request.command}" }
                ExecResult(
                    exitCode = -1,
                    stdout = stdout,
                    stderr = "$stderr\n[TIMED OUT after ${request.timeoutSeconds}s]",
                    durationMs = System.currentTimeMillis() - startMs
                )
            } else {
                val stdout = stdoutFuture.get(5, TimeUnit.SECONDS)
                val stderr = stderrFuture.get(5, TimeUnit.SECONDS)
                ExecResult(
                    exitCode = proc.exitValue(),
                    stdout = stdout,
                    stderr = stderr,
                    durationMs = System.currentTimeMillis() - startMs
                )
            }
        } catch (e: Exception) {
            // Coroutine cancellation (parent timed out or was cancelled) — propagate after
            // destroying the child so we don't leak a running process. Without this branch,
            // a cancelled `withTimeout` leaves the subprocess running until its internal
            // timeout fires, which can be minutes later.
            //
            // NonCancellable ensures destroyForcibly() actually runs; otherwise the cleanup
            // itself would be a no-op on an already-cancelled job.
            if (e is kotlinx.coroutines.CancellationException) {
                process?.takeIf { it.isAlive }?.let { p ->
                    withContext(NonCancellable) { runCatching { p.destroyForcibly() } }
                }
                throw e
            }
            logger.error(e) { "[NativeExec] Failed to execute: ${request.command}" }
            // Make sure we don't leak a half-started process if start() succeeded but a
            // later step threw (e.g. the stream-reader future timeout).
            process?.takeIf { it.isAlive }?.let { runCatching { it.destroyForcibly() } }
            ExecResult(
                exitCode = -1,
                stdout = "",
                stderr = "Error executing command: ${e.message}",
                durationMs = System.currentTimeMillis() - startMs
            )
        }
    }

    /**
     * Clear the subprocess environment and repopulate it from:
     *   1. A hard-coded minimal whitelist that is universally safe (PATH, HOME, LANG, ...)
     *   2. Any additional var names provided by the operator via
     *      `BRAIDRUN_NATIVE_EXEC_ENV_ALLOWLIST` (comma-separated).
     *   3. The caller-provided [requestEnv], which takes precedence over any inherited value.
     *
     * Nothing else from the JVM environment reaches the child. This prevents the LLM's
     * shell/code/git tools from reading production credentials that happen to be in the
     * parent process environment.
     */
    private fun applyEnvironmentPolicy(
        pb: ProcessBuilder,
        requestEnv: Map<String, String>
    ) {
        val env = pb.environment()
        val parentSnapshot = HashMap(env)
        env.clear()

        val deployAllowlist = (System.getenv("BRAIDRUN_NATIVE_EXEC_ENV_ALLOWLIST") ?: "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        (DEFAULT_ENV_ALLOWLIST + deployAllowlist).forEach { name ->
            parentSnapshot[name]?.let { env[name] = it }
        }

        // Caller-supplied env always wins.
        requestEnv.forEach { (k, v) -> env[k] = v }
    }

    /**
     * Read a process stream into a [String], capping at
     * [SubprocessExecutor.MAX_STREAM_BYTES]. Drains the rest of the stream
     * after the cap is hit so the child process is not blocked on a full
     * pipe buffer (which would deadlock waitFor); any bytes past the cap
     * are discarded.
     */
    private fun readBoundedStream(
        input: java.io.InputStream,
        lineCallback: ((String) -> Unit)? = null
    ): String {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        var truncated = false
        val lineBuffer = StringBuilder()

        fun emitCallbackLines(text: String) {
            if (lineCallback == null) return
            text.forEach { ch ->
                if (ch == '\n') {
                    val line = lineBuffer.toString().trimEnd('\r')
                    lineBuffer.setLength(0)
                    if (line.isNotEmpty()) {
                        runCatching { lineCallback.invoke(line) }
                            .onFailure { e -> logger.debug(e) { "[NativeExec] stdout line callback failed" } }
                    }
                } else {
                    lineBuffer.append(ch)
                }
            }
        }

        input.use { stream ->
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                if (truncated) continue
                val remaining = SubprocessExecutor.MAX_STREAM_BYTES - buffer.size()
                if (remaining <= 0) {
                    truncated = true
                    continue
                }
                if (read <= remaining) {
                    buffer.write(chunk, 0, read)
                    emitCallbackLines(String(chunk, 0, read, Charsets.UTF_8))
                } else {
                    buffer.write(chunk, 0, remaining)
                    emitCallbackLines(String(chunk, 0, remaining, Charsets.UTF_8))
                    truncated = true
                }
            }
        }
        if (lineCallback != null && lineBuffer.isNotEmpty()) {
            val line = lineBuffer.toString().trimEnd('\r')
            if (line.isNotEmpty()) {
                runCatching { lineCallback.invoke(line) }
                    .onFailure { e -> logger.debug(e) { "[NativeExec] stdout line callback failed" } }
            }
        }
        val text = buffer.toString(Charsets.UTF_8)
        return if (truncated) text + SubprocessExecutor.STREAM_TRUNCATION_MARKER else text
    }

    private fun destroyProcessTree(process: Process) {
        val handle = process.toHandle()
        handle.descendants().forEach { child ->
            runCatching { child.destroyForcibly() }
        }
        runCatching { process.destroyForcibly() }
        runCatching { process.waitFor(TIMEOUT_PROCESS_DESTROY_SECONDS, TimeUnit.SECONDS) }
    }

    companion object {
        private const val TIMEOUT_PROCESS_DESTROY_SECONDS = 1L
        private const val TIMEOUT_STREAM_DRAIN_SECONDS = 1L

        /**
         * Minimal set of environment variables copied from the parent into the child. Chosen
         * to keep common binaries functional (PATH for command lookup, HOME/TMPDIR for
         * tool caches, locale/timezone for correct output) without leaking secrets.
         *
         * If you find yourself tempted to add `AWS_*`, `*_TOKEN`, `*_KEY`, `*_SECRET`,
         * `DATABASE_URL`, or similar — don't. Pass them explicitly via [ExecRequest.env]
         * at the call site, where the code review can reason about the leak surface.
         */
        private val DEFAULT_ENV_ALLOWLIST = setOf(
            "PATH",
            "HOME",
            "USER",
            "LOGNAME",
            "LANG",
            "LC_ALL",
            "LC_CTYPE",
            "TZ",
            "TMPDIR",
            "TEMP",
            "TMP",
            "SHELL",
            "PWD",
            "SYSTEMROOT", // Windows: needed for basic shell operations
            "COMSPEC"
        )
    }
}
