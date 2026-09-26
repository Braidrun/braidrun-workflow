package com.fartech.agents.workflow

import com.fartech.agents.commons.BraidrunAgentHook
import com.fartech.agents.commons.BraidrunHookContext
import com.fartech.agents.commons.BraidrunHookEvent
import com.fartech.agents.commons.BraidrunHookExecutor
import com.fartech.agents.commons.SkillInstallHygiene
import com.fartech.agents.commons.SkillLoader
import com.fartech.agents.commons.SkillManager
import com.fartech.agents.commons.SkillsConfiguration
import com.fartech.agents.commons.parseExactToolSet
import com.fartech.agents.tools.SkillAdminTools
import com.fartech.agents.tools.SkillTools
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Skill hook scripts, per-skill MCP servers and the mutating skill tools all act on the host
 * JVM's machine. A multi-tenant host switches them off with the one-way
 * [WorkflowHostPolicy.restrictSkillSideEffects] latch; `skills_config` can only narrow it.
 * The latch is process-global, so every test resets it (same isolation as
 * [NestedWorkflowSandboxTest]).
 */
class SkillSideEffectsHostPolicyTest {

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        WorkflowHostPolicy.resetForTests()
    }

    @AfterEach
    fun tearDown() {
        WorkflowHostPolicy.resetForTests()
    }

    // ==================== Fixtures ====================

    private val skillsDir get() = File(tempDir, "skills")
    private val hookMarker get() = File(tempDir, "hook-ran.marker")
    private val mcpMarker get() = File(tempDir, "mcp-ran.marker")

    private fun writeSkill(name: String): File = File(skillsDir, name).apply {
        mkdirs()
        File(this, "SKILL.md").writeText("---\nname: $name\ndescription: $name skill\n---\nBody\n")
    }

    /** A skill whose gateway:startup hook leaves [hookMarker] behind if its handler really runs. */
    private fun writeSkillWithHookScript(name: String = "hooked"): File {
        val skill = writeSkill(name)
        val hookDir = File(skill, "hooks/braidrun-workflow").apply { mkdirs() }
        File(hookDir, "HOOK.md").writeText(
            """
            ---
            name: $name-hook
            description: marker hook
            metadata: {"braidrun-workflow":{"events":["gateway:startup"]}}
            ---
            Hook body
            """.trimIndent()
        )
        File(hookDir, "handler.py").writeText(
            """
            import sys
            sys.stdin.read()
            open(${pythonString(hookMarker.absolutePath)}, "w").write("ran")
            print("{}")
            """.trimIndent()
        )
        return skill
    }

    /** A skill bundling a script MCP server whose start leaves [mcpMarker] behind. */
    private fun writeSkillWithMcpServer(name: String = "mcp-bundled"): File {
        val skill = writeSkill(name)
        val server = File(skill, "mcp-servers/marker").apply { mkdirs() }
        File(server, "run.sh").apply {
            writeText("#!/bin/sh\ntouch '${mcpMarker.absolutePath}'\nsleep 5\n")
            setExecutable(true)
        }
        return skill
    }

    private fun pythonString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun manager(config: SkillsConfiguration = baseConfig()) =
        SkillManager(config, SkillLoader(skillsDir.toPath(), config))

    private fun baseConfig() = SkillsConfiguration(
        skillsPath = skillsDir.absolutePath,
        scanStandardPaths = false,
        builtinSkillsEnabled = false,
        hookScriptExecutionEnabled = true
    )

    private fun skillParameters(): List<ConfigurationParameter> = listOf(
        ConfigurationParameter("skills_config", Json.encodeToJsonElement(SkillsConfiguration.serializer(), baseConfig()))
    )

    private fun python3Available(): Boolean = runCatching {
        val process = ProcessBuilder("python3", "--version").redirectErrorStream(true).start()
        process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)

    private fun waitFor(file: File, timeoutMillis: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (file.exists()) return true
            Thread.sleep(100)
        }
        return file.exists()
    }

    private val adminToolNames = setOf("downloadSkillFromClawHub", "downloadSkillFromGit", "clearSkillCache", "refreshSkills")
    private val readOnlyToolNames = setOf(
        "searchSkills", "getSkillDetails", "inspectSkill", "listCachedSkills",
        "useSkill", "findLocalSkills", "listConfiguredSkills"
    )

    // ==================== The latch ====================

    @Test
    fun `defaults keep CLI behaviour except per-skill MCP auto-start, which is off`() {
        assertFalse(WorkflowHostPolicy.restrictsSkillSideEffects)
        assertTrue(WorkflowHostPolicy.allowsSkillHookScripts)
        assertTrue(WorkflowHostPolicy.allowsSkillAdminTools)
        assertFalse(WorkflowHostPolicy.allowsSkillMcpAutoStart)

        WorkflowHostPolicy.allowSkillMcpAutoStart()

        assertTrue(WorkflowHostPolicy.allowsSkillMcpAutoStart)
    }

    @Test
    fun `restricting skill side effects is one-way and beats the MCP opt-in`() {
        WorkflowHostPolicy.allowSkillMcpAutoStart()
        WorkflowHostPolicy.restrictSkillSideEffects()
        // Opting in again after the restriction changes nothing.
        WorkflowHostPolicy.allowSkillMcpAutoStart()

        assertTrue(WorkflowHostPolicy.restrictsSkillSideEffects)
        assertFalse(WorkflowHostPolicy.allowsSkillHookScripts)
        assertFalse(WorkflowHostPolicy.allowsSkillAdminTools)
        assertFalse(WorkflowHostPolicy.allowsSkillMcpAutoStart)
    }

    // ==================== Hook scripts ====================

    @Test
    fun `an unrestricted host still runs hook scripts (control)`() {
        assumeTrue(python3Available(), "python3 is required to run the marker hook")
        writeSkillWithHookScript()

        manager().initialize()

        assertTrue(hookMarker.exists(), "the gateway:startup handler should have run")
    }

    @Test
    fun `a restricted host runs no hook script even when skills_config enables them`() {
        assumeTrue(python3Available(), "python3 is required for the control to be meaningful")
        writeSkillWithHookScript()
        WorkflowHostPolicy.restrictSkillSideEffects()

        manager(baseConfig().copy(hookScriptExecutionEnabled = true)).initialize()

        assertFalse(hookMarker.exists(), "skills_config must not widen the host policy")
    }

    @Test
    fun `skills_config can still narrow hook scripts on an unrestricted host`() {
        assumeTrue(python3Available(), "python3 is required for the control to be meaningful")
        writeSkillWithHookScript()

        manager(baseConfig().copy(hookScriptExecutionEnabled = false)).initialize()

        assertFalse(hookMarker.exists())
    }

    @Test
    fun `the public hook executor refuses scripts under the host policy`() {
        val skill = writeSkillWithHookScript()
        WorkflowHostPolicy.restrictSkillSideEffects()
        val hook = BraidrunAgentHook(
            name = "direct",
            description = "direct",
            events = listOf(BraidrunHookEvent.GATEWAY_STARTUP),
            content = "",
            skillName = "hooked",
            handlerScript = File(skill, "hooks/braidrun-workflow/handler.py").toPath()
        )

        val result = BraidrunHookExecutor(baseConfig()).execute(hook, BraidrunHookContext(event = "gateway:startup"))

        assertNull(result)
        assertFalse(hookMarker.exists())
    }

    // ==================== Per-skill MCP servers ====================

    @Test
    fun `bundled MCP servers are not started unless the host opts in`() {
        writeSkillWithMcpServer()

        val manager = manager()
        manager.initialize()
        manager.refresh()
        Thread.sleep(1_500)

        assertFalse(mcpMarker.exists(), "run.sh must not be started without the host opt-in")
        assertTrue(Thread.getAllStackTraces().keys.none { it.name.startsWith("MCP-Init-mcp-bundled") })
        manager.shutdown()
    }

    @Test
    fun `bundled MCP servers start when the host opts in (control)`() {
        assumeTrue(File("/bin/sh").canExecute(), "needs a POSIX shell")
        writeSkillWithMcpServer()
        WorkflowHostPolicy.allowSkillMcpAutoStart()

        val manager = manager()
        try {
            manager.initialize()
            assertTrue(waitFor(mcpMarker, 15_000), "the opted-in host should start run.sh")
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `a restricted host starts no bundled MCP server even after opting in`() {
        writeSkillWithMcpServer()
        WorkflowHostPolicy.allowSkillMcpAutoStart()
        WorkflowHostPolicy.restrictSkillSideEffects()

        val manager = manager()
        manager.initialize()
        Thread.sleep(1_500)

        assertFalse(mcpMarker.exists())
        manager.shutdown()
    }

    // ==================== Mutating skill tools ====================

    private fun registeredSkillToolNames(): Set<String> = parseExactToolSet(
        parameters = skillParameters() + ConfigurationParameter("tool_set", JsonArray(listOf(JsonPrimitive("skill_tools")))),
        httpAccess = HttpAccess(),
        tools = listOf("skill_tools"),
        skillManager = null
    ).tools.map { it.name }.toSet()

    @Test
    fun `skill_tools registers the admin tools on an unrestricted host`() {
        writeSkill("demo")

        val names = registeredSkillToolNames()

        assertTrue(names.containsAll(readOnlyToolNames), "missing: ${readOnlyToolNames - names}")
        assertTrue(names.containsAll(adminToolNames), "missing: ${adminToolNames - names}")
    }

    @Test
    fun `a restricted host registers only the read-only skill tools`() {
        writeSkill("demo")
        WorkflowHostPolicy.restrictSkillSideEffects()

        val names = registeredSkillToolNames()

        assertTrue(names.containsAll(readOnlyToolNames), "missing: ${readOnlyToolNames - names}")
        assertEquals(emptySet(), names intersect adminToolNames)
    }

    @Test
    fun `admin tools built before the latch refuse every call with an explicit error`() {
        val cached = writeSkill("pdf@1.0.0")
        val admin = SkillAdminTools(SkillTools(skillParameters(), HttpAccess()))
        WorkflowHostPolicy.restrictSkillSideEffects()

        assertContains(admin.clearSkillCache("pdf@1.0.0"), "disabled by host policy")
        assertContains(admin.refreshSkills(), "disabled by host policy")
        val gitError = assertFailsWith<IllegalStateException> {
            runBlocking { admin.downloadSkillFromGit("https://github.com/user/skill.git") }
        }
        assertContains(gitError.message.orEmpty(), "disabled by host policy")
        val clawHubError = assertFailsWith<IllegalStateException> {
            runBlocking { admin.downloadSkillFromClawHub("pdf") }
        }
        assertContains(clawHubError.message.orEmpty(), "disabled by host policy")
        assertTrue(cached.isDirectory, "a refused call must not delete anything")
    }

    @Test
    fun `listConfiguredSkills stops advertising install tools on a restricted host`() {
        writeSkill("demo")
        WorkflowHostPolicy.restrictSkillSideEffects()

        val listing = SkillTools(skillParameters(), HttpAccess()).listConfiguredSkills()

        assertFalse(listing.contains("downloadSkillFromClawHub"))
        assertFalse(listing.contains("downloadSkillFromGit"))
    }

    // ==================== Host-API ClawHub installs ====================

    private fun seedCachedClawHubSkill(dirName: String, declaredName: String): File =
        File(skillsDir, dirName).apply {
            mkdirs()
            File(this, "SKILL.md").writeText("---\nname: $declaredName\ndescription: cached\n---\nBody\n")
            File(this, "hooks/braidrun-workflow").mkdirs()
            File(this, "mcp-servers/srv").mkdirs()
        }

    /** Serves `/api/v1/download` as a zip holding one skill named [declaredName]. */
    private fun <T> withClawHubServer(declaredName: String, block: (baseUrl: String) -> T): T {
        val zip = java.io.ByteArrayOutputStream().also { bytes ->
            java.util.zip.ZipOutputStream(bytes).use { out ->
                out.putNextEntry(java.util.zip.ZipEntry("SKILL.md"))
                out.write("---\nname: $declaredName\ndescription: served\n---\nBody\n".toByteArray())
                out.closeEntry()
            }
        }.toByteArray()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/download") { exchange ->
            exchange.sendResponseHeaders(200, zip.size.toLong())
            exchange.responseBody.use { it.write(zip) }
        }
        server.start()
        return try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a restricted host strips hooks and mcp-servers from a cached ClawHub entry too`() {
        val cached = seedCachedClawHubSkill("innocuous@1.0.0", declaredName = "pdf")
        WorkflowHostPolicy.restrictSkillSideEffects()

        // Version pinned and not forced: served from the cache, no HTTP call.
        val ref = runBlocking { SkillTools(skillParameters(), HttpAccess()).downloadSkillFromClawHub("innocuous", "1.0.0") }

        assertEquals(cached.absolutePath, ref.skillPath)
        assertFalse(File(cached, "hooks").exists())
        assertFalse(File(cached, "mcp-servers").exists())
        assertEquals("pdf", ref.skillName, "a cache hit reports the declared name so hosts can vet it")
    }

    @Test
    fun `an unrestricted host leaves a cached ClawHub entry untouched (control)`() {
        val cached = seedCachedClawHubSkill("innocuous@1.0.0", declaredName = "innocuous")

        runBlocking { SkillTools(skillParameters(), HttpAccess()).downloadSkillFromClawHub("innocuous", "1.0.0") }

        assertTrue(File(cached, "hooks").isDirectory)
    }

    @Test
    fun `a host install with refreshManager=false keeps the new skill out of the shared manager`() {
        writeSkill("demo")
        val mgr = manager()
        assertEquals(listOf("demo"), mgr.getAllSkills().map { it.name })

        withClawHubServer(declaredName = "fresh") { baseUrl ->
            val params = skillParameters() + ConfigurationParameter("clawhub_base_url", JsonPrimitive(baseUrl))
            val tools = SkillTools(params, HttpAccess(), mgr)

            val held = runBlocking { tools.downloadSkillFromClawHub("fresh", "1.0.0", refreshManager = false) }
            assertEquals("fresh", held.skillName)
            assertEquals(listOf("demo"), mgr.getAllSkills().map { it.name }, "nothing loaded before the host accepts it")

            runBlocking { tools.downloadSkillFromClawHub("fresh", "2.0.0") }
            assertTrue(mgr.getAllSkills().any { it.name == "fresh" }, "default still refreshes (control)")
        }
    }

    // ==================== Read-only tools on a restricted host ====================

    @Test
    fun `inspectSkill on a restricted host reads only skills the scoped manager loaded`() {
        writeSkill("mine")
        val foreign = writeSkill("someone-else@zip")
        val config = baseConfig().copy(enabledSkills = listOf("mine"))
        val params = listOf(
            ConfigurationParameter("skills_config", Json.encodeToJsonElement(SkillsConfiguration.serializer(), config))
        )
        WorkflowHostPolicy.restrictSkillSideEffects()
        val tools = SkillTools(params, HttpAccess(), manager(config))

        assertContains(tools.inspectSkill(File(skillsDir, "mine").absolutePath), "Name: mine")
        val refused = tools.inspectSkill(foreign.absolutePath)
        assertContains(refused, "outside the configured skills directories")
        assertFalse(refused.contains("someone-else@zip skill"))
        assertContains(tools.inspectSkill(tempDir.absolutePath), "outside the configured skills directories")

        val listing = tools.listCachedSkills()
        assertContains(listing, "mine")
        assertFalse(listing.contains("someone-else"), listing)
    }

    @Test
    fun `inspectSkill on an unrestricted host still reads any local path (control)`() {
        val elsewhere = File(tempDir, "elsewhere/local").apply {
            mkdirs()
            File(this, "SKILL.md").writeText("---\nname: local\ndescription: local skill\n---\nBody\n")
        }

        assertContains(SkillTools(skillParameters(), HttpAccess()).inspectSkill(elsewhere.absolutePath), "Name: local")
    }

    // ==================== Install hygiene ====================

    @Test
    fun `side-effect directories are stripped from a skill root only`() {
        val skill = writeSkill("frontend")
        File(skill, "hooks/braidrun-workflow").mkdirs()
        File(skill, "MCP-Servers/srv").mkdirs()
        val nestedHooks = File(skill, "templates/src/hooks").apply { mkdirs() }
        File(nestedHooks, "useThing.ts").writeText("export {}")

        val removed = SkillInstallHygiene.stripSideEffectDirectories(skill)

        assertEquals(listOf("MCP-Servers", "hooks"), removed)
        assertFalse(File(skill, "hooks").exists())
        assertFalse(File(skill, "MCP-Servers").exists())
        assertTrue(File(nestedHooks, "useThing.ts").isFile, "ordinary content named hooks must stay")
        assertTrue(File(skill, "SKILL.md").isFile)
    }

    @Test
    fun `symlink-safe delete removes links without touching their targets`() {
        val outside = File(tempDir, "outside").apply { mkdirs() }
        val precious = File(outside, "precious.txt").apply { writeText("keep") }
        val victim = File(tempDir, "victim").apply { mkdirs() }
        File(victim, "file.txt").writeText("x")
        Files.createSymbolicLink(File(victim, "escape").toPath(), outside.toPath())

        assertTrue(SkillInstallHygiene.deleteRecursivelyNoFollow(victim))

        assertFalse(victim.exists())
        assertTrue(precious.isFile)
    }
}
