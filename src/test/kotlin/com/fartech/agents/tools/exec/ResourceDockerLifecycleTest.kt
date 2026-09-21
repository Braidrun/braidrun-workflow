package com.fartech.agents.tools.exec

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.CreateContainerCmd
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

@EnabledIfEnvironmentVariable(named = "RESOURCE_TEST_DOCKER_IMAGE", matches = ".+")
class ResourceDockerLifecycleTest {
    @TempDir lateinit var workspace: Path
    @BeforeEach fun allowContainerUserToEnterWorkspace() {
        workspace.toFile().setReadable(true, false)
        workspace.toFile().setWritable(true, false)
        workspace.toFile().setExecutable(true, false)
    }

    @Test fun `container enforces limits and confirms settlement only after removal`(): Unit = runBlocking {
        DockerSubprocessExecutor.buildDockerClient(System.getenv("DOCKER_HOST")).use { client ->
            val identity = "resource-test-${UUID.randomUUID()}"
            val ready = CompletableDeferred<Unit>()
            val settled = AtomicBoolean(false)
            val executor = DockerSubprocessExecutor(client, mapOf("shell" to System.getenv("RESOURCE_TEST_DOCKER_IMAGE")))
            val run = async(Dispatchers.IO) { executor.execute(SubprocessExecutor.ExecRequest(
                command = listOf("/bin/sh", "-c", "echo ready; sleep 2; echo done"),
                workingDir = workspace.toFile(), userId = identity,
                memoryLimitMb = 128, cpuLimit = .5, networkPolicy = SubprocessExecutor.NetworkPolicy.NONE,
                timeoutSeconds = 10,
                stdoutLineCallback = { if (it == "ready") ready.complete(Unit) },
                onSettled = { settled.set(true) }
            )) }
            withTimeout(5000) { ready.await() }
            assertFalse(settled.get())
            val container = client.listContainersCmd().withLabelFilter(mapOf("braidrun.user" to identity)).exec().single()
            val inspected = client.inspectContainerCmd(container.id).exec()
            assertEquals(128L * 1024 * 1024, inspected.hostConfig.memory)
            assertEquals(50_000L, inspected.hostConfig.cpuQuota)
            assertEquals("none", inspected.hostConfig.networkMode)
            val result = withTimeout(10_000) { run.await() }
            assertEquals(0, result.exitCode, result.stderr)
            assertContains(result.stdout, "done")
            assertTrue(settled.get())
            assertTrue(client.listContainersCmd().withShowAll(true).withLabelFilter(mapOf("braidrun.user" to identity)).exec().isEmpty())
        }
    }

    @Test fun `lost create acknowledgement does not falsely confirm process termination`(): Unit = runBlocking {
        DockerSubprocessExecutor.buildDockerClient(System.getenv("DOCKER_HOST")).use { real ->
            var createdId: String? = null
            val client = Proxy.newProxyInstance(DockerClient::class.java.classLoader, arrayOf(DockerClient::class.java)) { _, method, arguments ->
                if (method.name == "createContainerCmd") {
                    val command = method.invoke(real, *(arguments ?: emptyArray())) as CreateContainerCmd
                    Proxy.newProxyInstance(CreateContainerCmd::class.java.classLoader, arrayOf(CreateContainerCmd::class.java)) { proxy, call, args ->
                        if (call.name == "exec") {
                            createdId = command.exec().id
                            throw IllegalStateException("simulated lost create acknowledgement")
                        }
                        val value = call.invoke(command, *(args ?: emptyArray()))
                        if (value === command) proxy else value
                    }
                } else method.invoke(real, *(arguments ?: emptyArray()))
            } as DockerClient
            val settled = AtomicBoolean(false)
            try {
                val executor = DockerSubprocessExecutor(client, mapOf("shell" to System.getenv("RESOURCE_TEST_DOCKER_IMAGE")))
                assertFailsWith<IllegalStateException> { executor.execute(SubprocessExecutor.ExecRequest(
                    command = listOf("/bin/sh", "-c", "echo unused"), workingDir = workspace.toFile(),
                    userId = "resource-ack-test-${UUID.randomUUID()}",
                    networkPolicy = SubprocessExecutor.NetworkPolicy.NONE,
                    onSettled = { settled.set(true) }
                )) }
                assertNotNull(createdId, "the daemon actually accepted the create")
                assertFalse(settled.get(), "unknown create outcome must retain the host reservation")
            } finally { createdId?.let { real.removeContainerCmd(it).withForce(true).exec() } }
        }
    }
    @Test fun `recovery leaves a running container untouched and seals a finished reservation`(): Unit = runBlocking {
        val host = System.getenv("DOCKER_HOST")
        DockerSubprocessExecutor.buildDockerClient(host).use { client ->
            val ticket = UUID.randomUUID().toString()
            val name = "braidrun-resource-$ticket"
            val image = System.getenv("RESOURCE_TEST_DOCKER_IMAGE")
            val ready = CompletableDeferred<Unit>()
            try {
                val executor = DockerSubprocessExecutor(client, mapOf("shell" to image))
                val run = async(Dispatchers.IO) { executor.execute(SubprocessExecutor.ExecRequest(
                    command = listOf("/bin/sh", "-c", "echo ready; sleep 2; echo finished"), workingDir = workspace.toFile(),
                    env = mapOf("BRAIDRUN_RESOURCE_TICKET_ID" to ticket), networkPolicy = SubprocessExecutor.NetworkPolicy.NONE,
                    stdoutLineCallback = { if (it == "ready") ready.complete(Unit) }, timeoutSeconds = 10
                )) }
                withTimeout(5000) { ready.await() }
                assertFalse(DockerSubprocessExecutor.recoverResourceReservation(ticket, host))
                assertEquals(0, withTimeout(10_000) { run.await() }.exitCode)
                assertTrue(DockerSubprocessExecutor.recoverResourceReservation(ticket, host))
                val fence = client.inspectContainerCmd(name).exec()
                assertFalse(fence.state.running == true)
                assertEquals(ticket, fence.config.labels?.get("braidrun.resource_recovery_fence"))
                assertTrue(DockerSubprocessExecutor.recoverResourceReservation(ticket, host))
                assertFailsWith<com.github.dockerjava.api.exception.ConflictException> {
                    client.createContainerCmd(image).withName(name).exec()
                }
            } finally {
                runCatching { client.removeContainerCmd(name).withForce(false).exec() }
            }
        }
    }

}
