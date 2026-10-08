package com.fartech.agents.runner

import com.fartech.agents.workflow.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Definitions are loaded once from verified bytes, then resolved only from this frozen map. */
class FrozenWorkflowBundle(private val root: Path, val manifest: JsonObject, private val binding: JsonObject) : WorkflowResolver {
    private data class Entry(val id: String, val path: String, val hash: String, val definition: WorkflowDefinition)
    private val entries: List<Entry>
    private val frozenFiles = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    val executionDirectory: Path get() = root
    val taskBinding: JsonObject get() = binding
    val rootWorkflowId: String = manifest.string("rootWorkflowId")

    init {
        val protocol = RunnerProtocol()
        protocol.validate(manifest, "bundle")
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)) { "Invalid execution directory" }
        val metadata = JsonObject(manifest.filterKeys { it != "bundleHash" } + mapOf(
            "files" to JsonArray(manifest.array("files").sortedBy { it.jsonObject.string("path") }),
            "definitions" to JsonArray(manifest.array("definitions").sortedBy { it.jsonObject.string("workflowId") })
        ))
        require(sha256(canonical(metadata).toByteArray(Charsets.UTF_8)) == manifest.string("bundleHash")) { "Bundle metadata hash mismatch" }
        val paths = HashSet<String>()
        val files = manifest.array("files").associate { item ->
            val file = item.jsonObject
            val relative = file.string("path")
            require(paths.add(relative)) { "Duplicate bundle file" }
            val path = confinedPath(root, relative)
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) == file.long("size")) { "Invalid bundle file" }
            require(file.long("size") <= 256L * 1024 * 1024) { "Bundle file exceeds runtime limit" }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { stream -> val buffer = ByteArray(8192); while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            require(digest.digest().joinToString("") { "%02x".format(it) } == file.string("sha256")) { "Bundle file hash mismatch" }
            if (file.string("role") in setOf("definition", "script")) {
                require(file.long("size") <= RunnerIpc.MAX_FRAME_BYTES) { "Executable bundle file exceeds limit" }
                val bytes = Files.readAllBytes(path)
                require(sha256(bytes) == file.string("sha256")) { "Executable file changed during freeze" }
                frozenFiles[relative] = bytes
            }
            relative to file
        }
        val ids = HashSet<String>()
        entries = manifest.array("definitions").map { item ->
            val def = item.jsonObject
            val id = def.string("workflowId")
            require(ids.add(id)) { "Duplicate definition identity" }
            val path = def.string("path")
            val file = files[path] ?: error("Definition is missing from verified files")
            require(file.string("role") == "definition" && file.string("sha256") == def.string("definitionHash")) { "Definition hash mismatch" }
            val bytes = Files.readAllBytes(confinedPath(root, path))
            require(bytes.size <= RunnerIpc.MAX_FRAME_BYTES && sha256(bytes) == def.string("definitionHash")) { "Definition changed during verification" }
            Entry(id, path, def.string("definitionHash"), WorkflowParser.parseYaml(bytes.toString(Charsets.UTF_8)).let { definition ->
                definition.copy(directoryIsolation = DirectoryIsolationConfig(
                    baseDir = root.resolve(".workflow-runs").toString(), sharedSkillsDir = root.resolve("skills").toString(),
                    sharedCacheDir = root.resolve(".prompt-cache").toString(), sharedHistoryDir = root.resolve(".agent-history").toString()
                ))
            })
        }
        require(entries.map { it.path }.distinct().size == entries.size) { "Duplicate definition path" }
        require(entries.single { it.id == rootWorkflowId }.hash == binding.string("definitionHash")) { "Root definition binding mismatch" }
    }

    val allDefinitions: List<WorkflowDefinition> get() = entries.map { it.definition }
    val rootDefinition: WorkflowDefinition get() = entries.single { it.id == rootWorkflowId }.definition
    override fun resolve(reference: WorkflowReference): ResolvedWorkflow {
        val entry = when {
            reference.workflowId != null -> entries.singleOrNull { it.id == reference.workflowId }
            reference.path != null -> entries.singleOrNull { it.path == reference.path?.removePrefix("./") }
            reference.name != null -> entries.singleOrNull { it.definition.name == reference.name }
            else -> null
        } ?: throw WorkflowValidationException("Definition is not in the authorized bundle")
        return ResolvedWorkflow(entry.id, entry.definition, entry.path)
    }
    override fun identityKey(reference: WorkflowReference): String = resolve(reference).identity
    fun dynamic(path: String): WorkflowDefinition {
        val requested = Path.of(path)
        val relative = if (requested.isAbsolute) {
            val normalized = requested.normalize()
            require(normalized.startsWith(root.toAbsolutePath().normalize())) { "Dynamic definition is outside bundle" }
            root.toAbsolutePath().normalize().relativize(normalized).toString()
        } else requested.normalize().toString().removePrefix("./")
        val entry = entries.singleOrNull { it.path == relative } ?: error("Dynamic definition was not authorized")
        require(sha256(Files.readAllBytes(confinedPath(root, relative))) == entry.hash) { "Dynamic definition hash changed" }
        return entry.definition
    }
    fun script(path: String): String {
        val requested = Path.of(path)
        val relative = if (requested.isAbsolute) {
            require(requested.normalize().startsWith(root.toAbsolutePath().normalize())) { "Script outside bundle" }
            root.toAbsolutePath().normalize().relativize(requested.normalize()).toString()
        } else requested.normalize().toString().removePrefix("./")
        val bytes = frozenFiles[relative] ?: error("Script was not frozen in bundle")
        require(sha256(Files.readAllBytes(confinedPath(root, relative))) == sha256(bytes)) { "Script changed since admission" }
        return bytes.toString(Charsets.UTF_8)
    }
    fun contains(definition: WorkflowDefinition): Boolean = entries.any { it.definition == definition }
}

/** No '..', absolute names, backslashes, symlink ancestors or NUL. */
internal fun confinedPath(root: Path, relative: String): Path {
    require(relative.isNotBlank() && !relative.contains('\\') && !relative.contains('\u0000')) { "Invalid relative path" }
    val part = Path.of(relative)
    require(!part.isAbsolute && part.none { it.toString() == ".." }) { "Invalid relative path" }
    val base = root.toAbsolutePath().normalize()
    require(!Files.isSymbolicLink(base)) { "Symlink directory refused" }
    var current = base
    for (component in part) {
        current = current.resolve(component)
        require(!Files.isSymbolicLink(current)) { "Symlink bundle path refused" }
    }
    val normalized = current.normalize()
    require(normalized.startsWith(base) && normalized != base) { "Path outside bundle" }
    return normalized
}
