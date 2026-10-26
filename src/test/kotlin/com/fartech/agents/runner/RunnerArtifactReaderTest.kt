package com.fartech.agents.runner

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*

class RunnerArtifactReaderTest {
    private fun index(directory: Path, binding: JsonObject, logical: String, bytes: ByteArray): Pair<String, JsonObject> {
        val hash = sha256(bytes)
        val id = "artifact_" + sha256((canonical(binding) + "\u0000" + logical + "\u0000" + hash).toByteArray())
        val relative = ".runner-state/upload-spool/$id"
        Files.createDirectories(directory.resolve(".runner-state/upload-spool")); Files.createDirectories(directory.resolve(".runner-state/artifact-refs"))
        Files.write(directory.resolve(relative), bytes); Files.setPosixFilePermissions(directory.resolve(relative), PosixFilePermissions.fromString("r--------"))
        val count = maxOf(1, (bytes.size + RunnerArtifactReader.CHUNK_BYTES - 1) / RunnerArtifactReader.CHUNK_BYTES)
        val reference = buildJsonObject {
            put("formatVersion", 1); put("binding", binding); put("artifactId", id); put("fileHash", hash); put("size", bytes.size)
            put("relativePath", relative); put("durableUploadAck", true)
            put("chunkReceipts", JsonArray((0 until count).map { n ->
                val chunk = bytes.copyOfRange(n * RunnerArtifactReader.CHUNK_BYTES, minOf((n + 1) * RunnerArtifactReader.CHUNK_BYTES, bytes.size))
                buildJsonObject { put("artifactId", id); put("logicalPath", logical); put("chunkIndex", n); put("chunkCount", count); put("sha256", sha256(chunk)); put("size", chunk.size) }
            }))
        }
        Files.writeString(directory.resolve(".runner-state/artifact-refs/$id.json"), canonical(reference))
        return id to reference
    }
    private fun replace(directory: Path, id: String, reference: JsonObject) = Files.writeString(directory.resolve(".runner-state/artifact-refs/$id.json"), canonical(reference))
    @Test fun `real large local process output reads complete ACKed chunks not truncated inline`(@TempDir dir: Path) {
        val source = dir.resolve("capture.txt")
        val process = ProcessBuilder("/bin/bash", "-c", "for ((i=0; i<50000; i++)); do printf 'native-output-%06d-abcdefghijklmnopqrstuvwxyz\\n' \"\$i\"; done")
            .redirectOutput(source.toFile()).redirectError(dir.resolve("stderr").toFile()).start()
        assertEquals(0, process.waitFor())
        val bytes = Files.readAllBytes(source); assertTrue(bytes.size > RunnerArtifactReader.CHUNK_BYTES)
        val logical = "process-output/registration.stdout"; val binding = RunnerProtocolTest.binding()
        val (id, _) = index(dir, binding, logical, bytes)
        assertEquals(bytes.toString(Charsets.UTF_8), RunnerArtifactReader(dir, binding, RunnerSecrets()).read(id, logical))
    }
    @Test fun `changed index hash missing ACK chunks path identity and cross execution are refused`(@TempDir dir: Path) {
        val binding = RunnerProtocolTest.binding(); val logical = "process-output/registration.stdout"
        val (id, original) = index(dir, binding, logical, "proof".toByteArray())
        val reader = RunnerArtifactReader(dir, binding, RunnerSecrets())
        for (changed in listOf(
            JsonObject(original + ("durableUploadAck" to JsonPrimitive(false))),
            JsonObject(original + ("chunkReceipts" to JsonArray(emptyList()))),
            JsonObject(original + ("relativePath" to JsonPrimitive("/etc/passwd"))),
            JsonObject(original + ("fileHash" to JsonPrimitive("f".repeat(64)))),
            JsonObject(original + ("binding" to JsonObject(binding + ("executionId" to JsonPrimitive("another-execution"))))),
            JsonObject(original + ("chunkReceipts" to JsonArray(listOf(JsonObject(original.array("chunkReceipts")[0].jsonObject + ("sha256" to JsonPrimitive("f".repeat(64))))))))
        )) {
            replace(dir, id, changed); assertFailsWith<IllegalArgumentException> { reader.read(id, logical) }
        }
        replace(dir, id, original)
        assertFailsWith<IllegalArgumentException> { reader.read(id, "process-output/other-registration.stdout") }
        assertFailsWith<IllegalArgumentException> { reader.read("process-output/registration.stdout", logical) }
        Files.setPosixFilePermissions(dir.resolve(original.string("relativePath")), PosixFilePermissions.fromString("rw-------"))
        Files.writeString(dir.resolve(original.string("relativePath")), "edited")
        assertFailsWith<IllegalArgumentException> { reader.read(id, logical) }
    }
    @Test fun `symlink spool or index ancestor cannot expose files outside attempt`(@TempDir dir: Path) {
        val binding = RunnerProtocolTest.binding(); val logical = "process-output/registration.stderr"
        val (id, original) = index(dir, binding, logical, "proof".toByteArray())
        val spool = dir.resolve(original.string("relativePath")); val outside = Files.createTempFile("runner-artifact-negative", ".txt")
        try {
            Files.writeString(outside, "proof"); Files.delete(spool); Files.createSymbolicLink(spool, outside)
            assertFailsWith<IllegalArgumentException> { RunnerArtifactReader(dir, binding, RunnerSecrets()).read(id, logical) }
            Files.delete(spool); Files.writeString(spool, "proof")
            val folder = dir.resolve(".runner-state/artifact-refs"); Files.move(folder, dir.resolve("old-refs")); Files.createSymbolicLink(folder, dir.resolve("old-refs"))
            assertFailsWith<IllegalArgumentException> { RunnerArtifactReader(dir, binding, RunnerSecrets()).read(id, logical) }
        } finally { Files.deleteIfExists(outside) }
    }
}
