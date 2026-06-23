package com.fartech.agents.tools.exec

import com.fartech.agents.tools.exec.SubprocessExecutor.*
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.WaitContainerResultCallback
import com.github.dockerjava.api.model.*
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import mu.KotlinLogging
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Production-mode subprocess executor that runs each command inside an ephemeral
 * Docker container with strict isolation.
 *
 * ## Security posture (constraint C8)
 *
 * - `--rm` + force-remove in finally block
 * - `--user 2000:2000` (non-root)
 * - `--cap-drop ALL`, `--security-opt no-new-privileges`
 * - Read-only root filesystem + tmpfs `/tmp`
 * - Per-step workspace bind-mounted at `/workspace`
 * - Per-request extra bind mounts for output/persistence/cache/skills
 * - Network restricted via `workflow-egress-only` bridge
 * - Memory and CPU limits enforced
 * - Container labels for audit trail (`braidrun.user`, `braidrun.created_at`)
 *
 * ## Image registry
 *
 * Maps [ExecRequest.imageHint] to a fully-qualified image tag:
 * - `"shell"` → `braidrun/exec-shell:1.0`
 * - `"python"` → `braidrun/exec-python-node:1.0`
 * - `"node"` → `braidrun/exec-node:1.0`
 */
class DockerSubprocessExecutor(
    private val dockerClient: DockerClient,
    private val imageRegistry: Map<String, String> = DEFAULT_IMAGE_REGISTRY,
    private val egressNetworkName: String = DEFAULT_EGRESS_NETWORK
) : SubprocessExecutor {

    override suspend fun execute(request: ExecRequest): ExecResult {
        val startMs = System.currentTimeMillis()
        val imageTag = imageRegistry[request.imageHint ?: "shell"]
            ?: error("Unknown image hint: '${request.imageHint}'. Available: ${imageRegistry.keys}")

        // Reject oversized stdin before we spawn the container — a multi-GB
        // string copied into the docker stdin pipe wedges this coroutine and
        // pins the bytes in heap. Cap matches the native executor.
        request.stdin?.let { stdin ->
            val byteCount = stdin.toByteArray(Charsets.UTF_8).size
            require(byteCount <= SubprocessExecutor.MAX_STDIN_BYTES) {
                "stdin payload (${byteCount} bytes) exceeds MAX_STDIN_BYTES=${SubprocessExecutor.MAX_STDIN_BYTES}"
            }
        }

        // Ensure workspace directory exists on host
        request.workingDir.mkdirs()

        val hostConfig = HostConfig.newHostConfig()
            .withAutoRemove(false) // we manage removal in finally block for reliable log collection
            .withReadonlyRootfs(true)
            .withTmpFs(mapOf("/tmp" to "rw,size=100M"))
            .withMemory(request.memoryLimitMb * 1024 * 1024)
            .withCpuQuota((request.cpuLimit * 100_000).toLong())
            .withCpuPeriod(100_000L)
            .withSecurityOpts(listOf("no-new-privileges"))
            .withCapDrop(Capability.ALL)
            // Cap process count to deflect fork-bomb-style DoS that would
            // otherwise exhaust host PIDs across containers.
            .withPidsLimit(DEFAULT_PIDS_LIMIT)
            // Cap file descriptors and rlimit/file size so a runaway loop
            // can't exhaust the host's nofile budget or fill the tmpfs by
            // creating millions of small files.
            .withUlimits(DEFAULT_ULIMITS)
            .withNetworkMode(networkMode(request.networkPolicy))
            .withBinds(buildBinds(request))

        val createCmd = dockerClient.createContainerCmd(imageTag)
            .withUser("2000:2000")
            .withWorkingDir("/workspace")
            .withHostConfig(hostConfig)
            .withEnv(buildContainerEnv(request))
            .withLabels(
                mapOf(
                    "braidrun.user" to request.userId,
                    "braidrun.created_at" to System.currentTimeMillis().toString()
                )
            )
            .withAttachStdin(request.stdin != null)
            .withStdinOpen(request.stdin != null)
            // StdinOnce: after the one-shot attach below finishes writing the input and
            // disconnects, Docker CLOSES the container's stdin (delivers EOF). Without
            // it, OpenStdin keeps stdin open forever and a stdin-reading child (e.g.
            // `codex exec`) blocks indefinitely waiting for an EOF that never comes —
            // the "runs 20+ min with no output" hang.
            .withStdInOnce(request.stdin != null)

        // Override the Dockerfile entrypoint: first element is the interpreter,
        // remaining elements are arguments. This allows running both inline code
        // (e.g. bash -c "cmd") and file-based execution (e.g. python3 code.py).
        if (request.command.size > 1) {
            createCmd.withEntrypoint(request.command.first())
            createCmd.withCmd(request.command.drop(1))
        } else {
            createCmd.withCmd(request.command)
        }

        val container = createCmd.exec()
        val containerId = container.id
        logger.info { "[DockerExec] Created container $containerId (image=$imageTag, user=${request.userId})" }

        try {
            dockerClient.startContainerCmd(containerId).exec()

            val stdoutCapture = LogCaptureCallback(request.stdoutLineCallback)
            val stderrCapture = LogCaptureCallback()
            dockerClient.logContainerCmd(containerId)
                .withStdOut(true)
                .withStdErr(false)
                .withFollowStream(true)
                .exec(stdoutCapture)
            dockerClient.logContainerCmd(containerId)
                .withStdOut(false)
                .withStdErr(true)
                .withFollowStream(true)
                .exec(stderrCapture)

            // Pipe stdin if provided, then explicitly close the attach so the StdinOnce
            // session ends and Docker delivers EOF to the child. Leaving the attach open
            // would keep the single stdin session alive and the child would never see EOF.
            if (request.stdin != null) {
                val stdinAttach = dockerClient.attachContainerCmd(containerId)
                    .withStdIn(request.stdin.byteInputStream())
                    .exec(object : ResultCallback.Adapter<Frame>() {})
                stdinAttach.awaitCompletion(30, TimeUnit.SECONDS)
                runCatching { stdinAttach.close() }
            }

            val exitCode = dockerClient.waitContainerCmd(containerId)
                .exec(WaitContainerResultCallback())
                .awaitStatusCode(request.timeoutSeconds, TimeUnit.SECONDS)

            stdoutCapture.awaitCompletion(10, TimeUnit.SECONDS)
            stderrCapture.awaitCompletion(10, TimeUnit.SECONDS)

            return ExecResult(
                exitCode = exitCode,
                stdout = stdoutCapture.text(),
                stderr = stderrCapture.text(),
                durationMs = System.currentTimeMillis() - startMs
            )
        } catch (e: Exception) {
            // Timeout or other failure — try to kill the container
            logger.warn(e) { "[DockerExec] Container $containerId failed or timed out" }
            runCatching { dockerClient.killContainerCmd(containerId).exec() }

            val stdout = runCatching { collectLogs(containerId, stdOut = true) }.getOrDefault("")
            val stderr = runCatching { collectLogs(containerId, stdOut = false) }.getOrDefault("")

            return ExecResult(
                exitCode = -1,
                stdout = stdout,
                stderr = "$stderr\n[Container error: ${e.message}]",
                durationMs = System.currentTimeMillis() - startMs
            )
        } finally {
            runCatching {
                dockerClient.removeContainerCmd(containerId).withForce(true).exec()
                logger.debug { "[DockerExec] Removed container $containerId" }
            }
        }
    }

    internal class LogCaptureCallback(
        private val lineCallback: ((String) -> Unit)? = null
    ) : ResultCallback.Adapter<Frame>() {
        private val buffer = java.io.ByteArrayOutputStream()
        private val lineBuffer = java.io.ByteArrayOutputStream()
        private var truncated = false

        @Synchronized
        override fun onNext(frame: Frame) {
            if (truncated) return
            val remaining = SubprocessExecutor.MAX_STREAM_BYTES - buffer.size()
            if (remaining <= 0) {
                truncated = true
                return
            }
            val payload = frame.payload
            val take = minOf(payload.size, remaining)
            buffer.write(payload, 0, take)
            emitCallbackLines(payload, take)
            if (take < payload.size) truncated = true
        }

        @Synchronized
        fun text(): String {
            flushPendingLine()
            return buffer.toString(Charsets.UTF_8) +
                if (truncated) SubprocessExecutor.STREAM_TRUNCATION_MARKER else ""
        }

        private fun emitCallbackLines(bytes: ByteArray, length: Int) {
            val callback = lineCallback ?: return
            for (index in 0 until length) {
                val byte = bytes[index]
                if (byte == '\n'.code.toByte()) {
                    val line = lineBuffer.toString(Charsets.UTF_8).trimEnd('\r')
                    lineBuffer.reset()
                    if (line.isNotEmpty()) {
                        runCatching { callback.invoke(line) }
                            .onFailure { e -> logger.debug(e) { "[DockerExec] stdout line callback failed" } }
                    }
                } else {
                    lineBuffer.write(byte.toInt())
                }
            }
        }

        private fun flushPendingLine() {
            val callback = lineCallback ?: return
            if (lineBuffer.size() == 0) return
            val line = lineBuffer.toString(Charsets.UTF_8).trimEnd('\r')
            lineBuffer.reset()
            if (line.isNotEmpty()) {
                runCatching { callback.invoke(line) }
                    .onFailure { e -> logger.debug(e) { "[DockerExec] stdout line callback failed" } }
            }
        }
    }

    private fun collectLogs(containerId: String, stdOut: Boolean): String {
        // Buffer raw bytes first, then decode as UTF-8 once at the end.
        // Decoding per-frame would corrupt multi-byte characters (CJK, emoji)
        // when a UTF-8 sequence straddles a frame boundary.
        //
        // Cap the buffer at SubprocessExecutor.MAX_STREAM_BYTES so a container
        // pumping unbounded output cannot OOM the JVM. Truncation marker is
        // appended once after we close the stream so the caller can tell the
        // output was clipped.
        val buffer = java.io.ByteArrayOutputStream()
        var truncated = false
        dockerClient.logContainerCmd(containerId)
            .withStdOut(stdOut)
            .withStdErr(!stdOut)
            .withFollowStream(false)
            .exec(object : ResultCallback.Adapter<Frame>() {
                override fun onNext(frame: Frame) {
                    if (truncated) return
                    val remaining = SubprocessExecutor.MAX_STREAM_BYTES - buffer.size()
                    if (remaining <= 0) {
                        truncated = true
                        return
                    }
                    if (frame.payload.size <= remaining) {
                        buffer.write(frame.payload)
                    } else {
                        buffer.write(frame.payload, 0, remaining)
                        truncated = true
                    }
                }
            })
            .awaitCompletion(10, TimeUnit.SECONDS)
        return buffer.toString(Charsets.UTF_8) +
            if (truncated) SubprocessExecutor.STREAM_TRUNCATION_MARKER else ""
    }

    private fun buildBinds(request: ExecRequest): Binds {
        val binds = mutableListOf<Bind>()

        // Standard RW mount: workspace. Canonicalize + check against the sensitive-host
        // blocklist, even though the workingDir is ostensibly caller-controlled — the caller
        // could legitimately hand us a symlink pointing at `/etc`, and we must not silently
        // bind-mount it read-write into the container.
        val workspaceHostPath = canonicalizeAndValidateHostPath(
            request.workingDir,
            role = "workspace"
        )
        binds.add(
            Bind(
                workspaceHostPath,
                Volume("/workspace"),
                AccessMode.rw
            )
        )

        // Extra mounts from request
        request.mounts.forEach { mount ->
            if (!mount.hostPath.exists()) {
                // Warn, don't debug — silently skipping an expected mount leads to
                // confusing "file not found" failures *inside* the container and has
                // been a real source of production debugging time.
                logger.warn {
                    "[DockerExec] Skipping mount ${mount.hostPath} → ${mount.containerPath} — host path does not exist. " +
                        "Tools relying on this mount will fail inside the container."
                }
                return@forEach
            }
            val canonical = runCatching {
                canonicalizeAndValidateHostPath(mount.hostPath, role = "extra-mount")
            }.getOrElse { ex ->
                logger.warn(ex) {
                    "[DockerExec] Rejecting mount ${mount.hostPath} → ${mount.containerPath} — " +
                        "failed canonicalization / hit sensitive-path guard."
                }
                return@forEach
            }
            binds.add(
                Bind(
                    canonical,
                    Volume(mount.containerPath),
                    if (mount.readOnly) AccessMode.ro else AccessMode.rw
                )
            )
        }

        return Binds(*binds.toTypedArray())
    }

    /**
     * Canonicalize a host path and reject it if the resolved path falls inside the
     * sensitive-host blocklist (e.g. `/etc`, `/var/run`, `/proc`, `/sys`).
     *
     * The previous implementation passed `canonicalPath` straight through — which means
     * a caller-controlled symlink (e.g. a workflow workspace dir that was created as a
     * symlink to `/etc`) would get bind-mounted read-write into the container. This
     * check closes that hole.
     */
    private fun canonicalizeAndValidateHostPath(hostPath: File, role: String): String {
        val canonical = try {
            hostPath.canonicalFile
        } catch (e: Exception) {
            // If canonicalization fails, refuse. Don't fall back to `absoluteFile`
            // (which wouldn't resolve symlinks) — that's exactly the failure mode that
            // lets symlink-based traversal through.
            throw IllegalArgumentException(
                "Unable to canonicalize $role host path: $hostPath",
                e
            )
        }
        val canonicalStr = canonical.path
        require(FORBIDDEN_HOST_PATHS.none { forbidden ->
            canonicalStr == forbidden || canonicalStr.startsWith("$forbidden${File.separator}")
        }) {
            "$role host path resolves to a sensitive location and will not be bind-mounted: $canonicalStr"
        }
        return canonicalStr
    }

    internal fun buildContainerEnv(request: ExecRequest): List<String> =
        request.env.entries
            .sortedBy { it.key }
            .map { (key, value) -> "$key=$value" }

    private fun networkMode(policy: NetworkPolicy) = when (policy) {
        NetworkPolicy.NONE -> "none"
        NetworkPolicy.EGRESS_ONLY -> egressNetworkName
        NetworkPolicy.HOST -> "host"
    }

    companion object {
        const val DEFAULT_EGRESS_NETWORK = "workflow-egress-only"

        /**
         * Cap on processes per container — prevents fork bombs from exhausting
         * host PIDs. 256 is comfortably above any legitimate tool we ship
         * (Node typically runs <40 processes; pip install peaks around ~50).
         * Override path is intentionally absent — workflow YAML should not
         * be able to widen this.
         */
        const val DEFAULT_PIDS_LIMIT: Long = 256L

        /**
         * File-descriptor and file-size ulimits applied to every container.
         *
         *   - `nofile=1024:1024` blocks "open millions of files" loops while
         *     leaving headroom for legitimate Node/Python tools (Node default
         *     soft limit on macOS is 256, on Linux 1024).
         *   - `fsize=512MiB` blocks runaway writes that could fill the tmpfs
         *     or, with a future bind-mounted output dir, the host disk.
         *
         *   Construct lazily in [DEFAULT_ULIMITS] to keep the import surface
         *   contained.
         */
        internal val DEFAULT_ULIMITS: List<Ulimit> = listOf(
            Ulimit("nofile", 1024L, 1024L),
            Ulimit("fsize", 512L * 1024L * 1024L, 512L * 1024L * 1024L)
        )

        val DEFAULT_IMAGE_REGISTRY = mapOf(
            "shell" to "braidrun/exec-shell:1.0",
            "python" to "braidrun/exec-python-node:1.0",
            "node" to "braidrun/exec-node:1.0"
        )

        internal fun resolveDockerHost(
            uri: String? = null,
            env: Map<String, String> = System.getenv()
        ): String = uri?.takeIf { it.isNotBlank() }
            ?: env["DOCKER_HOST"]?.takeIf { it.isNotBlank() }
            ?: "unix:///var/run/docker.sock"

        /**
         * Check whether the Docker daemon is reachable.
         * Used by production startup assertions.
         */
        fun canConnect(uri: String? = null): Boolean = runCatching {
            buildDockerClient(resolveDockerHost(uri)).use { client ->
                client.pingCmd().exec()
            }
            true
        }.getOrDefault(false)

        /**
         * Build a [DockerClient] for the given daemon URI using the httpclient5 transport.
         */
        fun buildDockerClient(uri: String? = null): DockerClient {
            val resolvedUri = resolveDockerHost(uri)
            val config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(resolvedUri)
                .build()
            val httpClient = ApacheDockerHttpClient.Builder()
                .dockerHost(URI.create(resolvedUri))
                .build()
            return DockerClientImpl.getInstance(config, httpClient)
        }

        /**
         * Check whether a specific image is available locally.
         */
        fun imageExists(
            dockerClient: DockerClient,
            imageTag: String
        ): Boolean = runCatching {
            dockerClient.inspectImageCmd(imageTag).exec()
            true
        }.getOrDefault(false)

        /**
         * Check that all required execution images are pulled.
         * Returns the list of missing image tags (empty = all present).
         * Self-contained — callers don't need docker-java on the classpath.
         */
        fun findMissingImages(
            uri: String? = null,
            requiredImages: List<String> = DEFAULT_IMAGE_REGISTRY.values.toList()
        ): List<String> {
            val client = buildDockerClient(resolveDockerHost(uri))
            return try {
                requiredImages.filterNot { imageExists(client, it) }
            } finally {
                client.close()
            }
        }

        /**
         * Check whether a Docker network exists.
         * Used by production startup assertions to fail early instead of
         * deferring to the first container launch.
         */
        fun networkExists(uri: String? = null, networkName: String = DEFAULT_EGRESS_NETWORK): Boolean {
            val client = buildDockerClient(resolveDockerHost(uri))
            return try {
                client.inspectNetworkCmd().withNetworkId(networkName).exec()
                true
            } catch (_: Exception) {
                false
            } finally {
                client.close()
            }
        }

        /**
         * Host paths that must never be bind-mounted into an LLM-driven container. The list
         * is deliberately conservative: any write there would grant near-root capabilities
         * even when `--user 2000:2000` and `--cap-drop ALL` are applied.
         */
        internal val FORBIDDEN_HOST_PATHS = setOf(
            "/etc",
            "/var/run",
            "/var/lib/docker",
            "/proc",
            "/sys",
            "/root",
            "/boot",
            "/dev"
        )

        private val FORBIDDEN_CONTAINER_PREFIXES = setOf(
            "/etc", "/var", "/root", "/home", "/proc", "/sys"
        )

        /**
         * Validate extra mounts from workflow-level config.
         *
         * Rejects two classes of problems:
         *
         *  1. **Literal `..` components.** Even when the normalized form is benign, a path
         *     that *declares* traversal is almost certainly an attempt to paper over a
         *     different intent; we refuse it outright (matches the pre-existing contract
         *     that the call site depends on).
         *  2. **Normalized path inside a sensitive host root.** For the cases where no
         *     `..` appears (or a raw absolute path directly inside `/etc`), we still
         *     canonicalize and cross-check against [FORBIDDEN_HOST_PATHS].
         */
        fun validateExtraMounts(mounts: List<String>) {
            mounts.forEach { spec ->
                require(":" in spec) {
                    "Extra mount must be in 'hostPath:containerPath' format, got: $spec"
                }
                val parts = spec.split(":", limit = 2)
                val hostPath = parts[0]
                val containerPath = parts[1]

                require(".." !in hostPath && ".." !in containerPath) {
                    "Path traversal (..) not allowed in extra mount: $spec"
                }

                val normalizedHost = runCatching {
                    java.nio.file.Paths.get(hostPath).toAbsolutePath().normalize().toString()
                }.getOrElse {
                    throw IllegalArgumentException("Unable to normalize host path in mount: $spec")
                }
                require(
                    FORBIDDEN_HOST_PATHS.none { forbidden ->
                        normalizedHost == forbidden ||
                            normalizedHost.startsWith("$forbidden${File.separator}")
                    }
                ) {
                    "Extra mount host path '$hostPath' resolves to sensitive location '$normalizedHost'"
                }

                val normalizedContainer = runCatching {
                    java.nio.file.Paths.get(containerPath).normalize().toString()
                }.getOrElse {
                    throw IllegalArgumentException("Unable to normalize container path in mount: $spec")
                }
                require(FORBIDDEN_CONTAINER_PREFIXES.none { normalizedContainer.startsWith(it) }) {
                    "Container path '$normalizedContainer' is a forbidden system path"
                }
            }
        }
    }
}
