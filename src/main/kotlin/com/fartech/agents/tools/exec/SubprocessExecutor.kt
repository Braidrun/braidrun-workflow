package com.fartech.agents.tools.exec

import java.io.File

/**
 * Abstraction over subprocess execution that supports both native (dev) and
 * Docker-isolated (staging/production) modes.
 *
 * ## Multi-user constraint C8
 *
 * In production, every shell/code step runs inside a Docker container with:
 * - `--rm`, `--cap-drop ALL`, `--security-opt no-new-privileges`
 * - Read-only root filesystem + tmpfs `/tmp`
 * - Per-step workspace isolation
 * - Network restricted to egress-only (no private ranges, no cloud metadata)
 *
 * In development, the native executor preserves the current `ProcessBuilder`
 * behaviour with zero Docker dependency (constraint C1).
 */
interface SubprocessExecutor {

    suspend fun execute(request: ExecRequest): ExecResult

    data class ExecRequest(
        /** Full command to execute, e.g. `["bash", "-c", "echo hello"]` or `["python3", "code.py"]`. */
        val command: List<String>,
        /** Working directory on the host. In Docker mode this is bind-mounted to `/workspace`. */
        val workingDir: File,
        /** Optional content piped to the process's stdin. */
        val stdin: String? = null,
        /** Maximum wall-clock seconds before the process/container is killed. */
        val timeoutSeconds: Long = 120,
        /** Extra environment variables injected into the process/container. */
        val env: Map<String, String> = emptyMap(),
        /** Extra bind mounts (Docker mode only; ignored by [NativeSubprocessExecutor]). */
        val mounts: List<Mount> = emptyList(),
        /** Network policy (Docker mode only; ignored by [NativeSubprocessExecutor]). */
        val networkPolicy: NetworkPolicy = NetworkPolicy.EGRESS_ONLY,
        /** Container memory limit in MiB (Docker mode only). */
        val memoryLimitMb: Long = 512,
        /** Container CPU quota (1.0 = one full core; Docker mode only). */
        val cpuLimit: Double = 1.0,
        /**
         * Hint used to select the Docker image: `"shell"`, `"python"`, `"node"`.
         * Ignored by [NativeSubprocessExecutor].
         */
        val imageHint: String? = null,
        /** User ID for audit labels (Docker) and logging. */
        val userId: String = "local-user"
    )

    data class Mount(
        val hostPath: File,
        val containerPath: String,
        val readOnly: Boolean
    )

    enum class NetworkPolicy {
        /** No network access at all. */
        NONE,
        /** Outbound HTTP/HTTPS/DNS only — private ranges and cloud metadata blocked. */
        EGRESS_ONLY,
        /** Full host network (unsafe in multi-user, dev only). */
        HOST
    }

    data class ExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val durationMs: Long
    ) {
        /** Combined stdout + stderr, matching the legacy single-stream behaviour. */
        val combinedOutput: String
            get() = if (stderr.isBlank()) stdout
            else if (stdout.isBlank()) stderr
            else "$stdout\n$stderr"
    }

    companion object {
        /**
         * Hard cap on the bytes captured per stream from a subprocess.
         *
         * A misbehaving (or hostile) process can pump megabytes per second to
         * stdout / stderr; without a cap, the JVM heap becomes the bottleneck
         * and a single tool call can OOM the whole agent runtime. Both
         * executors truncate at this size and append a clear marker so the
         * caller can tell that the stream was clipped (vs. legitimately
         * empty).
         *
         * 8 MiB per stream is comfortably above any legitimate tool output we
         * currently observe (the largest legit emitter is `pip install -v`
         * around 3 MiB). The constant lives here so both [NativeSubprocessExecutor]
         * and [DockerSubprocessExecutor] enforce the same ceiling.
         */
        const val MAX_STREAM_BYTES: Int = 8 * 1024 * 1024

        /**
         * Hard cap on the bytes accepted as stdin. Mirrors [MAX_STREAM_BYTES]:
         * an unbounded stdin string copied into a process pipe wedges the
         * caller's coroutine and pins the bytes in JVM heap. 8 MiB is more
         * than any legitimate `code:` step or `bash -c` we ship.
         */
        const val MAX_STDIN_BYTES: Int = 8 * 1024 * 1024

        /** Marker appended to truncated streams. */
        const val STREAM_TRUNCATION_MARKER: String =
            "\n[output truncated at $MAX_STREAM_BYTES bytes]"
    }
}

/**
 * Per-agent execution context used by shell/code tools to bridge the host
 * workflow runtime and the subprocess executor.
 *
 * In native mode, subprocesses should see host-absolute workflow paths.
 * In Docker mode, the same logical locations are surfaced through fixed
 * container mounts such as `/workspace`, `/output`, and `/skills`.
 */
data class SubprocessToolContext(
    val workspaceDir: File? = null,
    val outputDir: File? = null,
    val skillsDir: File? = null,
    val executionId: String? = null,
    val stepName: String? = null,
    val sessionId: String? = null
)
