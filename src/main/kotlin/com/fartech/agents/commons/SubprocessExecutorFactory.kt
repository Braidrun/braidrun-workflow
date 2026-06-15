package com.fartech.agents.commons

import com.fartech.agents.tools.exec.DockerSubprocessExecutor
import com.fartech.agents.tools.exec.NativeSubprocessExecutor
import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.agents.tools.exec.SubprocessToolContext
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.parameter
import java.io.File

/**
 * Factory for the [SubprocessExecutor] that shell/code/git tools run commands through.
 *
 * Extracted from `AgentCommon.kt` in Phase 4 (that file is a 1,500-line god class and
 * this factory was one of the more self-contained responsibilities).
 *
 * The factory reads a small number of well-known configuration parameters:
 *   - `subprocess_mode` — `"native"` (default, dev) or `"docker"` (staging/prod)
 *   - `docker_exec_image_tag` — image tag appended to the exec image registry
 *   - `docker_egress_network` — Docker network used by container egress-only policy
 *
 * Callers also compose a [SubprocessToolContext] via [buildSubprocessToolContext] so
 * tool invocations can surface the same workspace / output / skills directories both
 * natively and through bind mounts.
 */
object SubprocessExecutorFactory {

    /**
     * Build a [SubprocessExecutor] from workflow / agent parameters.
     *
     * Mode selection:
     *   - `"native"` (default) → [NativeSubprocessExecutor] — bare ProcessBuilder, zero
     *     Docker dependency (constraint C1, development mode).
     *   - `"docker"` → [DockerSubprocessExecutor] — ephemeral container per call,
     *     constraint C8 (RO rootfs, cap-drop ALL, UID 2000:2000, egress-only network).
     *
     * `braidrun-web` injects `subprocess_mode=docker` for STAGING / PRODUCTION
     * environments; CLI and tests default to native.
     */
    fun create(parameters: List<ConfigurationParameter>): SubprocessExecutor {
        val mode = parameters.parameter("subprocess_mode", "native")
        return when (mode) {
            "docker" -> {
                val dockerHost = System.getenv("DOCKER_HOST") ?: "unix:///var/run/docker.sock"
                val imageTag = validateImageTag(parameters.parameter("docker_exec_image_tag", "1.0"))
                val networkName = validateDockerNetworkName(
                    parameters.parameter(
                        "docker_egress_network",
                        DockerSubprocessExecutor.DEFAULT_EGRESS_NETWORK
                    )
                )
                val registry = mapOf(
                    "shell" to "braidrun/exec-shell:$imageTag",
                    "python" to "braidrun/exec-python-node:$imageTag",
                    "node" to "braidrun/exec-node:$imageTag"
                )
                DockerSubprocessExecutor(
                    DockerSubprocessExecutor.buildDockerClient(dockerHost),
                    imageRegistry = registry,
                    egressNetworkName = networkName
                )
            }

            else -> NativeSubprocessExecutor()
        }
    }

    /**
     * Reject image tags that don't match the OCI reference format. We compose
     * the tag into `braidrun/exec-*:$tag` strings — without validation, an
     * operator (or upstream config layer) could smuggle in a different
     * registry / image / digest by passing e.g. `1.0,attacker-registry.io/evil`
     * (Docker accepts comma-separated multi-tag in some flows) or a tag
     * containing a forward slash that points docker pull at the wrong
     * canonical name. Pattern matches OCI's published spec for the tag
     * portion: ASCII letters/digits/`._-`, 1..128 chars, no leading `.` or `-`.
     */
    internal fun validateImageTag(tag: String): String {
        require(tag.isNotEmpty() && tag.length <= 128 && IMAGE_TAG_REGEX.matches(tag)) {
            "docker_exec_image_tag '$tag' is not a valid image tag (must match $IMAGE_TAG_REGEX)"
        }
        return tag
    }

    /**
     * Reject Docker network names that contain characters Docker doesn't
     * accept, so a typo / injection attempt fails at agent boot rather than
     * during the first container launch. Pattern follows Docker's own
     * `^[a-zA-Z0-9][a-zA-Z0-9_.-]+$` rule for network names.
     */
    internal fun validateDockerNetworkName(name: String): String {
        require(name.isNotEmpty() && name.length <= 128 && DOCKER_NETWORK_NAME_REGEX.matches(name)) {
            "docker_egress_network '$name' is not a valid Docker network name"
        }
        return name
    }

    private val IMAGE_TAG_REGEX = Regex("^[A-Za-z0-9_][A-Za-z0-9_.\\-]*$")
    private val DOCKER_NETWORK_NAME_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9_.-]*$")

    /**
     * Build the per-invocation [SubprocessToolContext]. Pulls workspace / output / skills
     * directories + execution / step / session IDs from parameters so every tool invocation
     * has consistent audit metadata and bind-mount hints.
     */
    fun buildToolContext(parameters: List<ConfigurationParameter>): SubprocessToolContext {
        fun parameterFile(key: String): File? =
            parameters.parameter(key, "")
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.let(::File)

        return SubprocessToolContext(
            workspaceDir = parameterFile("working_dir"),
            outputDir = parameterFile("output_dir"),
            skillsDir = parameterFile("shared_skills_dir"),
            executionId = parameters.parameter("execution_id", "").takeIf { it.isNotBlank() },
            stepName = parameters.parameter("step_name", "").takeIf { it.isNotBlank() },
            sessionId = parameters.parameter("session_id", "").takeIf { it.isNotBlank() }
        )
    }
}

/**
 * Backward-compat top-level function kept so existing callers in AgentCommon and
 * elsewhere don't have to migrate in the same Phase-4 change. New code should call
 * [SubprocessExecutorFactory.create] directly.
 */
fun createSubprocessExecutor(parameters: List<ConfigurationParameter>): SubprocessExecutor =
    SubprocessExecutorFactory.create(parameters)

/**
 * Backward-compat top-level helper mirroring [SubprocessExecutorFactory.buildToolContext].
 * AgentCommon's private helper delegated here.
 */
internal fun buildSubprocessToolContext(parameters: List<ConfigurationParameter>): SubprocessToolContext =
    SubprocessExecutorFactory.buildToolContext(parameters)
