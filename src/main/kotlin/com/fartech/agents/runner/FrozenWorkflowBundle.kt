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
    private val assetHashes = linkedMapOf<String, Pair<Long, String>>()
    private val skillHashes = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val frozenFiles = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val boundInputs = linkedMapOf<String, Pair<Long, String>>()
    /** The launch attachments and saved documents this run binds (role `input` under `inputs/uploads/` or
     * `inputs/documents/`), execution-relative path -> (size, sha256). Agents' file tools may read exactly these,
     * read-only; other `inputs/` files (App Factory's licensed assets) stay out of reach. */
    val boundInputFiles: Map<String, Pair<Long, String>> get() = boundInputs
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
            require(relative == Path.of(relative).normalize().toString() && !relative.startsWith(".runner-")) { "Noncanonical or private bundle path" }
            val alias = java.text.Normalizer.normalize(relative, java.text.Normalizer.Form.NFC).lowercase(java.util.Locale.ROOT)
            require(paths.add(alias)) { "Duplicate bundle file" }
            val path = confinedPath(root, relative)
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) == file.long("size")) { "Invalid bundle file" }
            require(file.long("size") <= 256L * 1024 * 1024) { "Bundle file exceeds runtime limit" }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { stream -> val buffer = ByteArray(8192); while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            require(digest.digest().joinToString("") { "%02x".format(it) } == file.string("sha256")) { "Bundle file hash mismatch" }
            assetHashes[relative] = file.long("size") to file.string("sha256")
            if (file.string("role") == "input" && BOUND_INPUT_ROOTS.any { relative.startsWith(it) }) boundInputs[relative] = file.long("size") to file.string("sha256")
            if (file.string("role") == "skill") {
                require(relative.startsWith("skills/")) { "Skill asset is not under the scoped skill root" }
                skillHashes[relative] = file.string("sha256")
            }
            if (file.string("role") in setOf("definition", "script")) {
                // Definitions are parsed in-process and never travel in an IPC frame, so they get their own
                // bound (the platform planner's limit). Scripts keep the frame bound.
                val limit = if (file.string("role") == "definition") MAX_DEFINITION_BYTES.toLong() else RunnerIpc.MAX_FRAME_BYTES.toLong()
                require(file.long("size") <= limit) { "Executable bundle file exceeds limit" }
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
            require(bytes.size <= MAX_DEFINITION_BYTES && sha256(bytes) == def.string("definitionHash")) { "Definition changed during verification" }
            Entry(id, path, def.string("definitionHash"), WorkflowParser.parseYaml(bytes.toString(Charsets.UTF_8)).let { definition ->
                definition.copy(agents = definition.agents.mapValues { RunnerSkillsPolicy.normalize(it.value, root) }, directoryIsolation = DirectoryIsolationConfig(
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
    /** Supervisor seals these namespaces; revalidate all declared bytes and closed inventories
     * before host operations. Writable run outputs are deliberately outside this inventory. */
    fun verifyPortableAssets() {
        assetHashes.forEach { (relative, expected) ->
            val path = confinedPath(root, relative)
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) == expected.first) { "Frozen asset missing or changed" }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { stream ->
                val buffer = ByteArray(8192)
                while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            require(digest.digest().joinToString("") { "%02x".format(it) } == expected.second) { "Frozen asset hash changed" }
        }
        for (namespace in listOf(".runtime/project", "inputs", "skills")) {
            val folder = confinedPath(root, namespace)
            if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) continue
            require(Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) { "Frozen namespace is not a directory" }
            Files.walk(folder).use { paths -> paths.forEach { path ->
                require(!Files.isSymbolicLink(path)) { "Frozen asset symlink refused" }
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && root.relativize(path).toString() in assetHashes) { "Unlisted or special frozen asset" }
                }
            } }
        }
    }

    /** Explicit text delivery for native CLIs; no CLI skill registry, hooks or HOME discovery. */
    fun nativeSkillInstructions(config: com.fartech.agents.commons.SkillsConfiguration): String {
        verifySkills()
        if (!config.enabled) return ""
        val selected = skillHashes.keys.sorted().filter { path ->
            val parts = path.split('/')
            parts.size == 3 && parts.last() == "SKILL.md" && config.isSkillEnabled(parts[1])
        }
        require(selected.size <= config.maxSkillsPerRequest.coerceIn(0, 32)) { "Too many selected native text skills" }
        var total = 0L
        return selected.joinToString("\n\n") { relative ->
            val path = confinedPath(root, relative)
            val size = Files.size(path)
            total += size
            require(size <= minOf(config.maxSkillContentSize, 128 * 1024L) && total <= 128 * 1024L) { "Native text skills exceed prompt budget" }
            val bytes = Files.readAllBytes(path)
            require(sha256(bytes) == skillHashes.getValue(relative)) { "Skill changed before native delivery" }
            val content = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            "Frozen workflow skill: $relative\nResource directory: ${path.parent}\n$content"
        }
    }

    fun verifySkills() {
        val folder = root.resolve("skills")
        if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) { require(skillHashes.isEmpty()) { "Frozen skill assets missing" }; return }
        require(!Files.isSymbolicLink(folder)) { "Skill root is a symlink" }
        val observed = HashSet<String>()
        Files.walk(folder).use { paths -> paths.forEach { path ->
            require(!Files.isSymbolicLink(path)) { "Skill asset symlink refused" }
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                val relative = root.relativize(path).toString()
                val expected = skillHashes[relative] ?: error("Unlisted skill asset appeared after freeze")
                require(Files.size(path) <= 256L * 1024 * 1024 && sha256(Files.readAllBytes(path)) == expected) { "Skill asset changed after freeze" }
                observed.add(relative)
            }
        } }
        require(observed == skillHashes.keys) { "Frozen skill inventory changed" }
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
    fun definition(id: String): WorkflowDefinition = entries.single { it.id == id }.definition
    fun definitionHashes(): Set<String> = entries.map { it.hash }.toSet()
    fun hasFile(path: String): Boolean = manifest.array("files").any { it.jsonObject.string("path") == path }
    fun runtimeBytes(path: String): ByteArray {
        val declaration = manifest.array("files").map { it.jsonObject }.single { it.string("path") == path }
        require(declaration.string("role") == "runtime" && declaration.long("size") <= 16 * 1024 * 1024) { "Runtime policy declaration invalid" }
        val file = confinedPath(root, path)
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) == declaration.long("size"))
        return Files.readAllBytes(file).also { require(sha256(it) == declaration.string("sha256")) { "Runtime policy bytes changed" } }
    }
    fun identityOf(definition: WorkflowDefinition): String = (entries.singleOrNull { it.definition === definition }
        ?: entries.single { it.definition == definition }).id
    fun contains(definition: WorkflowDefinition): Boolean = entries.any { it.definition == definition }
    companion object {
        /** Largest frozen workflow definition (2.5 MiB); matches the platform planner's definition limit. */
        const val MAX_DEFINITION_BYTES: Int = 2_621_440
        /** Where the platform places a generic workflow's bound attachments and saved documents. */
        val BOUND_INPUT_ROOTS = listOf("inputs/uploads/", "inputs/documents/")
    }
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
