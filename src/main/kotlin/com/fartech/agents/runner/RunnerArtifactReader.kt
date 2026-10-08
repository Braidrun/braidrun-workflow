package com.fartech.agents.runner

import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Reads only supervisor ACK-bound immutable capture spools. Index format is owned by
 * workflow_runner/artifacts.py; no local path or guessed upload ID becomes an artifact ID.
 * The authenticated processExited ID, canonical identity and each raw chunk digest all agree.
 */
class RunnerArtifactReader(private val directory: Path, private val binding: JsonObject, private val secrets: RunnerSecrets) {
    fun read(artifactId: String, expectedLogicalPath: String): String {
        require(artifactId.matches(Regex("artifact_[a-f0-9]{64}"))) { "Expected acknowledged remote artifact identity" }
        require(expectedLogicalPath.matches(Regex("process-output/[A-Za-z0-9_-]{1,128}\\.(stdout|stderr)"))) { "Expected scoped process capture" }
        val index = confinedPath(directory, ".runner-state/artifact-refs/$artifactId.json")
        val indexBytes = readBounded(index, RunnerIpc.MAX_FRAME_BYTES)
        val reference = runnerJson.parseToJsonElement(decode(indexBytes)).jsonObject
        require(reference.keys == setOf("formatVersion", "binding", "artifactId", "fileHash", "size", "relativePath", "durableUploadAck", "chunkReceipts")) { "Unknown artifact index shape" }
        require(reference.long("formatVersion") == 1L && reference.obj("binding") == binding && reference.string("artifactId") == artifactId && reference["durableUploadAck"] == JsonPrimitive(true)) { "Artifact index authority mismatch" }
        val relative = reference.string("relativePath")
        require(relative == ".runner-state/upload-spool/$artifactId") { "Artifact spool location mismatch" }
        val size = reference.long("size")
        require(size in 0..com.fartech.agents.tools.exec.SubprocessExecutor.MAX_STREAM_BYTES.toLong()) { "Captured stream exceeds engine budget" }
        val hash = reference.string("fileHash")
        require(hash.matches(Regex("[a-f0-9]{64}"))) { "Invalid capture hash" }
        val identity = "artifact_" + sha256((canonical(binding) + "\u0000" + expectedLogicalPath + "\u0000" + hash).toByteArray(Charsets.UTF_8))
        require(identity == artifactId) { "Capture ID is not bound to its acknowledged content/path" }
        val receipts = reference.array("chunkReceipts").map { it.jsonObject }
        val count = maxOf(1, ((size + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt())
        require(receipts.size == count) { "Capture has incomplete ACK receipts" }
        receipts.forEachIndexed { indexNumber, receipt ->
            require(receipt.keys == RECEIPT_FIELDS && receipt.string("artifactId") == artifactId && receipt.string("logicalPath") == expectedLogicalPath &&
                receipt.long("chunkIndex") == indexNumber.toLong() && receipt.long("chunkCount") == count.toLong()) { "Capture receipt sequence or binding mismatch" }
        }
        val spool = confinedPath(directory, relative)
        val bytes = readBounded(spool, com.fartech.agents.tools.exec.SubprocessExecutor.MAX_STREAM_BYTES)
        require(bytes.size.toLong() == size && sha256(bytes) == hash) { "Capture content changed after ACK" }
        receipts.forEachIndexed { number, receipt ->
            val start = number * CHUNK_BYTES; val end = minOf(start + CHUNK_BYTES, bytes.size)
            val chunk = bytes.copyOfRange(start, end)
            require(receipt.long("size") == chunk.size.toLong() && receipt.string("sha256") == sha256(chunk)) { "Capture chunk hash or size mismatch" }
        }
        val text = decode(bytes)
        require(!secrets.containsSecret(JsonPrimitive(text))) { "Secret material cannot be exposed as captured output" }
        return text
    }
    private fun readBounded(path: Path, limit: Int): ByteArray {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= limit) { "Capture must be a bounded regular file" }
        val result = java.io.ByteArrayOutputStream()
        Files.newByteChannel(path, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
            require(channel.size() <= limit) { "Capture exceeds limit" }
            val buffer = ByteBuffer.allocate(8192)
            while (true) {
                buffer.clear(); val read = channel.read(buffer); if (read < 0) break
                require(result.size() + read <= limit) { "Capture grew while reading" }
                result.write(buffer.array(), 0, read)
            }
        }
        return result.toByteArray()
    }
    private fun decode(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    companion object {
        const val CHUNK_BYTES = 1024 * 1024
        val RECEIPT_FIELDS = setOf("artifactId", "logicalPath", "chunkIndex", "chunkCount", "sha256", "size")
    }
}
