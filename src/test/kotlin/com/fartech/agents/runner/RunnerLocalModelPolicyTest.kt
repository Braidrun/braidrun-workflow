package com.fartech.agents.runner

import kotlinx.serialization.json.*
import com.fartech.agents.workflow.InMemoryWorkflowResolver
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class RunnerLocalModelPolicyTest {
    private fun fixture(dir: Path, edit: (JsonObject) -> JsonObject = { it }, rootEdit: (String) -> String = { it }): FrozenWorkflowBundle {
        val child = """
            name: local-child
            agents:
              writer:
                strategy: single_run
            workflow:
              - step: answer
                agent: writer
                input: hello
        """.trimIndent()
        val root = rootEdit(child.replace("local-child", "local-root") + "\n  - step: call_1\n    sub_workflow:\n      workflow_id: child\n")
        val files = mutableMapOf("root.yaml" to ("definition" to root), "child.yaml" to ("definition" to child))
        val hash = sha256(root.toByteArray())
        val binding = JsonObject(RunnerProtocolTest.binding() + ("definitionHash" to JsonPrimitive(hash)))
        val pin = RunnerLocalModelBinding(binding.string("runnerId"), binding.string("ownerId"), binding.string("executionId"), binding.string("attemptId"),
            "local", "http://127.0.0.1:11434", "0.12.6", "fixture:8b", "a".repeat(64), 8192, 512)
        fun metadata() = buildJsonObject {
            put("rootWorkflowId", "root")
            put("definitions", JsonArray(listOf("child" to child, "root" to root).map { (id, yaml) -> buildJsonObject { put("workflowId", id); put("path", "$id.yaml"); put("definitionHash", sha256(yaml.toByteArray())) } }))
            put("files", JsonArray(files.toSortedMap().map { (path, v) -> buildJsonObject { put("path", path); put("role", v.first); put("size", v.second.toByteArray().size); put("sha256", sha256(v.second.toByteArray())) } }))
        }
        val policy = edit(buildJsonObject {
            put("schemaVersion", 1); put("rootWorkflowId", "root"); put("rootDefinitionHash", hash); put("sourceBundleHash", sha256(canonical(metadata()).toByteArray()))
            put("childInvocationGraph", buildJsonArray { add(buildJsonObject { put("parentWorkflowId", "root"); put("callingStep", "call_1"); put("executionSegment", "call_1"); put("childWorkflowId", "child"); put("childDefinitionHash", sha256(child.toByteArray())) }) })
            put("entries", JsonArray(listOf("root" to root, "child" to child).map { (id, yaml) -> buildJsonObject {
                put("workflowId", id); put("definitionHash", sha256(yaml.toByteArray())); put("agentName", "writer"); put("stepName", "answer")
                put("childExecutionPath", JsonArray(if (id == "root") emptyList() else listOf(JsonPrimitive("call_1"))))
                put("binding", RunnerLocalModelProtocol.json(pin)); put("approvalId", "p0-fixture"); put("evidenceSha256", "b".repeat(64)); put("temperature", 0.0)
            } }))
        })
        files[RunnerLocalModelPolicy.PATH] = "runtime" to policy.toString()
        files.forEach { (path, value) -> Files.createDirectories(dir.resolve(path).parent); Files.writeString(dir.resolve(path), value.second) }
        val metadata = metadata()
        return FrozenWorkflowBundle(dir, JsonObject(metadata + ("bundleHash" to JsonPrimitive(sha256(canonical(metadata).toByteArray())))), binding)
    }
    @Test fun `carrier proves source bytes definition agent and exact static child pair`(@TempDir dir: Path) {
        val policy = RunnerLocalModelPolicy(fixture(dir))
        assertEquals("writer", assertNotNull(policy.resolve("root", "answer", emptyList())).agentName)
        assertEquals("writer", assertNotNull(policy.resolve("child", "answer", listOf("call_1", "call_2"))).agentName)
        assertNull(policy.resolve("root", "call_1", emptyList()))
        for (path in listOf(listOf("call_1"), listOf("call_1", "call_0"), listOf("call_1", "call_01"), listOf("missing", "call_1"), listOf("call_1", "call_1", "call_1", "call_1"))) {
            assertFails { policy.resolve("child", "answer", path) }
        }
        Files.writeString(dir.resolve(RunnerLocalModelPolicy.PATH), "{}")
        assertFails { policy.verify() }
    }
    @Test fun `changed proof attempt graph and undeclared fields refuse carrier`(@TempDir root: Path) {
        val edits: List<(JsonObject) -> JsonObject> = listOf(
            { JsonObject(it + ("sourceBundleHash" to JsonPrimitive("c".repeat(64)))) },
            { JsonObject(it + ("secret" to JsonPrimitive("forbidden"))) },
            { p -> JsonObject(p + ("childInvocationGraph" to JsonArray(emptyList()))) },
            { p -> JsonObject(p + ("entries" to JsonArray(p.array("entries").map { e -> JsonObject(e.jsonObject + ("binding" to JsonObject(e.jsonObject.obj("binding") + ("attemptId" to JsonPrimitive("old"))))) }))) },
            { p -> JsonObject(p + ("entries" to JsonArray(p.array("entries").map { e -> JsonObject(e.jsonObject + ("stepName" to JsonPrimitive("call_1"))) }))) }
        )
        edits.forEachIndexed { i, edit -> val dir = Files.createDirectory(root.resolve("case-$i")); assertFails { RunnerLocalModelPolicy(fixture(dir, edit)) } }
    }
    @Test fun `verified local-only admission needs local capability without public model route`(@TempDir dir: Path) {
        val bundle = fixture(dir); val policy = RunnerLocalModelPolicy(bundle)
        val localOnly = setOf("workflow.full.v1", "step.agent.v1", "step.subWorkflow.v1", RunnerLocalModelProtocol.CAPABILITY)
        val required = RunnerAdmission(bundle, localOnly, localModelPolicy = policy).inspect(bundle.rootDefinition)
        assertTrue(RunnerLocalModelProtocol.CAPABILITY in required)
        assertFalse("model.http.v1" in required)
        assertFails { RunnerAdmission(bundle, localOnly).inspect(bundle.rootDefinition) }
        assertFails { RunnerAdmission(bundle, localOnly - RunnerLocalModelProtocol.CAPABILITY, localModelPolicy = policy).inspect(bundle.rootDefinition) }
        assertFails { RunnerAdmission(InMemoryWorkflowResolver(), localOnly, localModelPolicy = policy).inspect(bundle.rootDefinition) }
    }

    @Test fun `mixed local and cloud admission retains public model requirement`(@TempDir dir: Path) {
        val bundle = fixture(dir, rootEdit = { yaml -> yaml.replace("\nworkflow:\n", "\n  cloud:\n    strategy: single_run\nworkflow:\n") + "\n  - step: cloud-answer\n    agent: cloud\n    input: hello\n" })
        val policy = RunnerLocalModelPolicy(bundle)
        val capabilities = setOf("workflow.full.v1", "step.agent.v1", "step.subWorkflow.v1", RunnerLocalModelProtocol.CAPABILITY)
        assertFails { RunnerAdmission(bundle, capabilities, localModelPolicy = policy).inspect(bundle.rootDefinition) }
        val required = RunnerAdmission(bundle, capabilities + "model.http.v1", localModelPolicy = policy).inspect(bundle.rootDefinition)
        assertTrue(required.containsAll(setOf("model.http.v1", RunnerLocalModelProtocol.CAPABILITY)))
    }

    @Test fun `partial selected-agent coverage refuses without turning uncovered uses into cloud`(@TempDir dir: Path) {
        val bundle = fixture(dir, rootEdit = { it + "\n  - step: uncovered\n    agent: writer\n    input: hello\n" })
        val policy = RunnerLocalModelPolicy(bundle)
        assertFails { RunnerAdmission(bundle, RunnerHostSession.CAPABILITIES + RunnerLocalModelProtocol.CAPABILITY, localModelPolicy = policy).inspect(bundle.rootDefinition) }
    }

    @Test fun `a second actual static edge cannot hide behind reachability`(@TempDir dir: Path) {
        val extra: (String) -> String = { it + "\n  - step: call_2\n    sub_workflow:\n      workflow_id: child\n" }
        assertFails { RunnerLocalModelPolicy(fixture(dir.resolve("omitted"), rootEdit = extra)) }
        val bundle = fixture(dir.resolve("uncovered"), edit = { policy ->
            val first = policy.array("childInvocationGraph").single().jsonObject
            val second = JsonObject(first + mapOf("callingStep" to JsonPrimitive("call_2"), "executionSegment" to JsonPrimitive("call_2")))
            JsonObject(policy + ("childInvocationGraph" to JsonArray(listOf(first, second))))
        }, rootEdit = extra)
        val policy = RunnerLocalModelPolicy(bundle)
        assertFails { RunnerAdmission(bundle, RunnerHostSession.CAPABILITIES + RunnerLocalModelProtocol.CAPABILITY, localModelPolicy = policy).inspect(bundle.rootDefinition) }
    }

}
