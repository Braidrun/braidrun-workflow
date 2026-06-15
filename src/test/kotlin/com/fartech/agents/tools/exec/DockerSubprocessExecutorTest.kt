package com.fartech.agents.tools.exec

import java.io.File
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import java.lang.reflect.Proxy
import com.github.dockerjava.api.DockerClient

/**
 * Unit tests for [DockerSubprocessExecutor] static utilities.
 *
 * Container-level integration tests (echo, timeout, OOM, network policy) require
 * a running Docker daemon and pre-built images — they are gated behind
 * [dockerAvailable] so they run in CI but are silently skipped on dev machines
 * without Docker.
 */
class DockerSubprocessExecutorTest {

    companion object {
        @JvmStatic
        fun dockerAvailable(): Boolean = DockerSubprocessExecutor.canConnect()
    }

    // ─── Static utility tests (no Docker required) ───

    @Test
    fun `DEFAULT_IMAGE_REGISTRY has all three image hints`() {
        val registry = DockerSubprocessExecutor.DEFAULT_IMAGE_REGISTRY
        assertTrue(registry.containsKey("shell"))
        assertTrue(registry.containsKey("python"))
        assertTrue(registry.containsKey("node"))
        assertEquals("braidrun/exec-python-node:1.0", registry.getValue("python"))
        assertEquals("workflow-egress-only", DockerSubprocessExecutor.DEFAULT_EGRESS_NETWORK)
    }

    @Test
    fun `validateExtraMounts rejects path traversal`() {
        assertThrows(IllegalArgumentException::class.java) {
            DockerSubprocessExecutor.validateExtraMounts(listOf("/data/../secret:/mnt"))
        }
    }

    @Test
    fun `validateExtraMounts rejects forbidden container paths`() {
        for (forbidden in listOf("/etc", "/var", "/root", "/home", "/proc", "/sys")) {
            assertThrows(IllegalArgumentException::class.java) {
                DockerSubprocessExecutor.validateExtraMounts(listOf("/data:$forbidden"))
            }
        }
    }

    @Test
    fun `validateExtraMounts accepts valid mount spec`() {
        assertDoesNotThrow {
            DockerSubprocessExecutor.validateExtraMounts(listOf("/data/shared:/datasets"))
        }
    }

    @Test
    fun `validateExtraMounts rejects missing colon`() {
        assertThrows(IllegalArgumentException::class.java) {
            DockerSubprocessExecutor.validateExtraMounts(listOf("/data/shared"))
        }
    }

    @Test
    fun `resolveDockerHost prefers explicit uri over environment`() {
        val resolved = DockerSubprocessExecutor.resolveDockerHost(
            uri = "unix:///tmp/explicit.sock",
            env = mapOf("DOCKER_HOST" to "unix:///tmp/from-env.sock")
        )

        assertEquals("unix:///tmp/explicit.sock", resolved)
    }

    @Test
    fun `resolveDockerHost falls back to DOCKER_HOST environment`() {
        val resolved = DockerSubprocessExecutor.resolveDockerHost(
            env = mapOf("DOCKER_HOST" to "unix:///tmp/from-env.sock")
        )

        assertEquals("unix:///tmp/from-env.sock", resolved)
    }

    @Test
    fun `resolveDockerHost falls back to default unix socket when unset`() {
        val resolved = DockerSubprocessExecutor.resolveDockerHost(env = emptyMap())

        assertEquals("unix:///var/run/docker.sock", resolved)
    }

    @Test
    fun `buildContainerEnv preserves request env entries`() {
        val executor = DockerSubprocessExecutor(dummyDockerClient())
        val env = executor.buildContainerEnv(
            SubprocessExecutor.ExecRequest(
                command = listOf("python3", "code.py"),
                workingDir = File("."),
                env = mapOf(
                    "WF_VAR_USERNAME" to "test-user",
                    "WF_VAR_PASSWORD" to "secret"
                )
            )
        )

        assertEquals(
            listOf(
                "WF_VAR_PASSWORD=secret",
                "WF_VAR_USERNAME=test-user"
            ),
            env
        )
    }

    // ─── Integration tests (Docker daemon required) ───

    @Test
    @EnabledIf("dockerAvailable")
    fun `canConnect returns true when Docker is running`() {
        assertTrue(DockerSubprocessExecutor.canConnect())
    }

    @Test
    @EnabledIf("dockerAvailable")
    fun `imageExists returns false for nonexistent image`() {
        val dockerHost = System.getenv("DOCKER_HOST") ?: "unix:///var/run/docker.sock"
        val client = DockerSubprocessExecutor.buildDockerClient(dockerHost)
        assertFalse(DockerSubprocessExecutor.imageExists(client, "braidrun/nonexistent:99.99"))
        client.close()
    }

    private fun dummyDockerClient(): DockerClient {
        return Proxy.newProxyInstance(
            DockerClient::class.java.classLoader,
            arrayOf(DockerClient::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "close" -> null
                "toString" -> "DummyDockerClient"
                else -> throw UnsupportedOperationException("Not used in this test: ${method.name}")
            }
        } as DockerClient
    }
}
