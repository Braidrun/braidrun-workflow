package com.fartech.agents.tools.exec

import com.fartech.agents.tools.ShellTools
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class DecoratedExecutorCapabilityTest {
    @TempDir lateinit var directory: File
    @Test fun `shell tools preserve Docker paths and mounts through a decorator`(): Unit = runBlocking {
        var captured: SubprocessExecutor.ExecRequest? = null
        val transport = object : SubprocessExecutor {
            override val isDocker: Boolean = true
            override suspend fun execute(request: SubprocessExecutor.ExecRequest): SubprocessExecutor.ExecResult {
                captured = request
                return SubprocessExecutor.ExecResult(0, "ok", "", 0)
            }
        }
        val decorated = object : SubprocessExecutor by transport {}
        val output = File(directory, "output").apply { mkdirs() }
        val skills = File(directory, "skills").apply { mkdirs() }
        val tool = ShellTools(decorated, "user", SubprocessToolContext(workspaceDir = directory, outputDir = output,
            skillsDir = skills, executionId = "root", stepName = "agent"))
        tool.executeShellCmd("echo ok")
        val request = assertNotNull(captured)
        assertEquals("/workspace", request.env["BRAIDRUN_WORKSPACE"])
        assertEquals("/output", request.env["BRAIDRUN_OUTPUT_DIR"])
        assertEquals("/skills", request.env["BRAIDRUN_SKILLS_DIR"])
        assertTrue(request.mounts.any { it.containerPath == "/output" && !it.readOnly })
        assertTrue(request.mounts.any { it.containerPath == "/skills" && it.readOnly })
        assertEquals("SUBPROCESS", request.admissionKind)
    }
}
