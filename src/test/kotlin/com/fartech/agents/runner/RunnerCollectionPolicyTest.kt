package com.fartech.agents.runner

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

internal data class CollectionFixture(val binding: JsonObject, val bundle: JsonObject, val policy: JsonObject, val policyHash: String)
internal fun collectionFixture(dir: Path, rootCode: String = "true", childCode: String = "true", callingStep: String = "nested", change: (JsonObject) -> JsonObject = { it }): CollectionFixture {
    val root = """
        name: collection-root
        agents: {}
        workflow:
          - step: produce
            code:
              language: bash
              timeout: 7000
              script: "$rootCode"
          - step: $callingStep
            sub_workflow:
              workflow_id: child
    """.trimIndent()
    val child = """
        name: collection-child
        agents: {}
        workflow:
          - step: produce
            code:
              language: bash
              timeout: 7200
              script: "$childCode"
    """.trimIndent()
    val policy = change(buildJsonObject {
        put("schemaVersion", 1); put("rootWorkflowId", "root")
        put("declarations", JsonArray(listOf("root", "child").map { id -> buildJsonObject {
            put("declarationId", "decl-$id"); put("producerWorkflowId", id); put("producerStep", "produce")
            put("childSteps", JsonArray(if (id == "root") emptyList() else listOf(JsonPrimitive(callingStep))))
            put("logicalPath", "run/$id.txt"); put("kind", "MATERIAL"); put("maximumBytes", 1024)
            put("sourcePolicy", "UNAVAILABLE"); put("codeSourceSha256", JsonNull)
        } }))
    })
    val files = mapOf("root.yaml" to ("definition" to root), "child.yaml" to ("definition" to child), RunnerCollectionPolicy.PATH to ("runtime" to policy.toString()), "inputs/static.txt" to ("input" to "sealed"))
    files.forEach { (path, value) -> Files.createDirectories(dir.resolve(path).parent); Files.writeString(dir.resolve(path), value.second) }
    val hash = sha256(root.toByteArray()); val binding = JsonObject(RunnerProtocolTest.binding() + ("definitionHash" to JsonPrimitive(hash)))
    val metadata = buildJsonObject {
        put("rootWorkflowId", "root")
        put("files", JsonArray(files.toSortedMap().map { (path, v) -> buildJsonObject { put("path", path); put("role", v.first); put("size", v.second.toByteArray().size); put("sha256", sha256(v.second.toByteArray())) } }))
        put("definitions", JsonArray(listOf("child" to child, "root" to root).map { (id, yaml) -> buildJsonObject { put("workflowId", id); put("path", "$id.yaml"); put("definitionHash", sha256(yaml.toByteArray())) } }))
    }
    return CollectionFixture(binding, JsonObject(metadata + ("bundleHash" to JsonPrimitive(sha256(canonical(metadata).toByteArray())))), policy, sha256(policy.toString().toByteArray()))
}
internal fun collectFrame(f: CollectionFixture, id: String, start: String = "original-start", size: Int = 4, hash: String = sha256("file".toByteArray())) = buildJsonObject {
    put("protocolVersion", 1); put("frameId", "request-frame-$id"); put("type", "artifactCollectRequest"); put("binding", f.binding)
    put("payload", buildJsonObject {
        put("requestId", "request-$id"); put("policySha256", f.policyHash); put("declarationId", "decl-$id")
        put("publicationId", "publication-$id"); put("artifactId", "artifact-$id"); put("logicalPath", "run/$id.txt"); put("size", size); put("sha256", hash)
        put("childExecutionPath", JsonArray(if (id == "root") emptyList() else listOf(JsonPrimitive("nested"), JsonPrimitive("call_1"))))
        put("producerWorkflowId", id); put("producerStep", "produce"); put("producerStartEventId", start); put("codeSourceSha256", JsonNull)
    })
}

class RunnerCollectionPolicyTest {
    @Test fun `supplement is strict and old81 rejects new frames without negotiation`(@TempDir dir: Path) {
        val f = collectionFixture(dir); val frame = collectFrame(f, "root")
        RunnerCollectionProtocol.validate(frame, "collectionRequest")
        assertFailsWith<RunnerProtocolException> { RunnerProtocol().validate(frame, "ipcFrame") }
        IpcHarness(f.binding).use { h -> assertFailsWith<RunnerProtocolException> { h.ipc.accept(frame) } }
        assertFailsWith<RunnerProtocolException> { RunnerCollectionProtocol.validate(JsonObject(frame + ("privateField" to JsonPrimitive(true))), "collectionRequest") }
        assertEquals(RunnerCollectionProtocol.SHA256, sha256(javaClass.getResourceAsStream("/runner-workflow-v1/artifact-collection.schema.json")!!.readBytes()))
    }
    @Test fun `frozen policy resolves root and exact child edge ordinal without trusting request producer`(@TempDir dir: Path) {
        val f = collectionFixture(dir); val bundle = FrozenWorkflowBundle(dir, f.bundle, f.binding); val policy = RunnerCollectionPolicy(bundle)
        assertEquals(f.binding.string("executionId"), policy.validateRequest(collectFrame(f, "root").obj("payload")).second)
        val body = collectFrame(f, "child").obj("payload")
        assertEquals(f.binding.string("executionId") + "__nested__call_1", policy.validateRequest(body).second)
        for (override in listOf(
            mapOf("producerWorkflowId" to JsonPrimitive("root")), mapOf("producerStep" to JsonPrimitive("other")),
            mapOf("childExecutionPath" to buildJsonArray { add("nested"); add("call_01") }),
            mapOf("childExecutionPath" to buildJsonArray { add("other"); add("call_1") }),
            mapOf("policySha256" to JsonPrimitive("0".repeat(64))), mapOf("size" to JsonPrimitive(1025)),
            mapOf("logicalPath" to JsonPrimitive("run/undeclared.txt")), mapOf("codeSourceSha256" to JsonPrimitive("a".repeat(64)))
        )) assertFails { policy.validateRequest(JsonObject(body + override)) }
        Files.writeString(dir.resolve(RunnerCollectionPolicy.PATH), "{}")
        assertFails { policy.verify() }
    }
    @Test fun `real calling step named call one is preserved and cannot be stripped as ordinal`(@TempDir dir: Path) {
        val f = collectionFixture(dir, callingStep = "call_1")
        val policy = RunnerCollectionPolicy(FrozenWorkflowBundle(dir, f.bundle, f.binding))
        val body = JsonObject(collectFrame(f, "child").obj("payload") + ("childExecutionPath" to buildJsonArray { add("call_1"); add("call_2") }))
        assertEquals(f.binding.string("executionId") + "__call_1__call_2", policy.validateRequest(body).second)
        assertFails { policy.validateRequest(JsonObject(body + ("childExecutionPath" to buildJsonArray { add("call_2") }))) }
    }
    @Test fun `policy rejects invalid producer source and aliases before execution`(@TempDir root: Path) {
        val edits: List<(JsonObject) -> JsonObject> = listOf(
            { JsonObject(it + ("rootWorkflowId" to JsonPrimitive("other"))) },
            { p -> JsonObject(p + ("declarations" to JsonArray(p.array("declarations") + p.array("declarations")[0]))) },
            { p -> JsonObject(p + ("declarations" to JsonArray(p.array("declarations").mapIndexed { index, v -> if (index == 0) JsonObject(v.jsonObject + ("producerStep" to JsonPrimitive("nested"))) else v }))) },
            { p -> JsonObject(p + ("declarations" to JsonArray(p.array("declarations").mapIndexed { index, v -> if (index == 0) JsonObject(v.jsonObject + ("logicalPath" to JsonPrimitive("inputs/file"))) else v }))) },
            { p -> JsonObject(p + ("declarations" to JsonArray(p.array("declarations").mapIndexed { index, v -> if (index == 1) JsonObject(v.jsonObject + ("logicalPath" to JsonPrimitive("run/ROOT.txt"))) else v }))) },
            { p -> JsonObject(p + ("declarations" to JsonArray(p.array("declarations").map { JsonObject(it.jsonObject + ("codeSourceSha256" to JsonPrimitive("a".repeat(64)))) }))) }
        )
        edits.forEachIndexed { index, edit ->
            val dir = root.resolve("case-$index"); Files.createDirectories(dir); val f = collectionFixture(dir, change = edit)
            assertFails { RunnerCollectionPolicy(FrozenWorkflowBundle(dir, f.bundle, f.binding)) }
        }
    }
    @Test fun `separate dynamic staging does not weaken sealed static inputs`(@TempDir dir: Path) {
        val f = collectionFixture(dir); val bundle = FrozenWorkflowBundle(dir, f.bundle, f.binding)
        val staged = dir.resolve(".runner-state/dynamic-inputs/read-fixture"); Files.createDirectories(staged)
        Files.writeString(staged.resolve("content"), "source-fixture"); Files.writeString(staged.resolve("receipt.json"), "{\"state\":\"SEALED\"}")
        bundle.verifyPortableAssets() // layout-only fixture, not a source HTTP/receipt authority test
        Files.writeString(dir.resolve("inputs/unlisted.zip"), "not-declared")
        assertFails { bundle.verifyPortableAssets() }
    }
}
