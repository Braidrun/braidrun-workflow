package com.fartech.agents.workflow

import mu.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * 跨工作流引用解析器
 *
 * 用来把一个 [WorkflowReference](id / path / name)解析为一个具体的 [WorkflowDefinition]。
 * [WorkflowParser] 在做 sub_workflow 的跨工作流静态校验时调用这个接口;[WorkflowExecutor] 在
 * 运行到 `sub_workflow` 步骤时也调用它加载 child。
 *
 * 实现方:
 * - [NullWorkflowResolver] — 默认,不解析任何外部引用,只保证基本 parse 通过(用于早期 CLI、单元测试)
 * - [FileSystemWorkflowResolver] — CLI 场景,按相对 / 绝对路径从文件系统加载
 * - [InMemoryWorkflowResolver] — 测试场景,直接从内存 map 查
 * - `WebWorkflowResolver`(由 braidrun-web 提供) — Web 场景,从 `WorkflowService` 与
 *   `workflowVersions` 集合加载,并做契约版本兼容性检查
 *
 * 这个抽象层的目的是让 `braidrun-agent` 对存储层保持无感:Web / DB 的细节都在 Web 侧实现。
 */
interface WorkflowResolver {
    /**
     * 根据引用解析出具体的 child workflow。
     *
     * @throws WorkflowValidationException 找不到引用、版本不匹配、跨 owner 访问等场景
     */
    fun resolve(reference: WorkflowReference): ResolvedWorkflow

    /**
     * 生成一个稳定的身份键,用于静态循环检测的 DFS 节点标识。
     *
     * 同一个 child workflow 无论通过 `workflowId` 还是 `path` 引用都应返回相同的 identity key。
     */
    fun identityKey(reference: WorkflowReference): String
}

/**
 * 解析成功后的结果。
 *
 * @property identity 稳定身份键(参考 `identityKey`),DFS 循环检测用
 * @property definition 反序列化得到的 workflow 定义(已经执行过 parse,但跨工作流校验还没跑)
 * @property sourceLabel 人类可读的来源描述(错误信息里用),如 "id=abc-123" / "path=./child.yaml"
 */
data class ResolvedWorkflow(
    val identity: String,
    val definition: WorkflowDefinition,
    val sourceLabel: String
)

/**
 * 默认的 no-op 解析器:遇到任何引用都抛"未配置"异常。
 *
 * 使用场景:
 * - `WorkflowParser.validateWorkflow(workflow)` 老的无参 overload 用它占位
 * - 存在 `sub_workflow` 步骤但没配 resolver 时,静态校验只 warn 不 fail(参见 `WorkflowParser`)
 * - 运行时遇到 `sub_workflow` 时会抛错,提示必须配 resolver
 */
object NullWorkflowResolver : WorkflowResolver {
    override fun resolve(reference: WorkflowReference): ResolvedWorkflow {
        throw WorkflowValidationException(
            "No WorkflowResolver configured; cannot resolve sub-workflow reference: ${reference.label()}"
        )
    }

    override fun identityKey(reference: WorkflowReference): String {
        return "null-resolver:${reference.workflowId ?: reference.path ?: reference.name ?: "unknown"}"
    }
}

/**
 * 内存解析器,主要用于单元测试。
 *
 * ```kotlin
 * val resolver = InMemoryWorkflowResolver(
 *     byId = mapOf("child-1" to childDef),
 *     byPath = mapOf("./video.yaml" to videoDef)
 * )
 * ```
 */
class InMemoryWorkflowResolver(
    private val byId: Map<String, WorkflowDefinition> = emptyMap(),
    private val byPath: Map<String, WorkflowDefinition> = emptyMap(),
    private val byName: Map<String, WorkflowDefinition> = emptyMap()
) : WorkflowResolver {
    override fun resolve(reference: WorkflowReference): ResolvedWorkflow {
        val def = when {
            !reference.workflowId.isNullOrBlank() -> byId[reference.workflowId]
                ?: throw WorkflowValidationException("InMemoryResolver: workflow_id '${reference.workflowId}' not found")

            !reference.path.isNullOrBlank() -> byPath[reference.path]
                ?: throw WorkflowValidationException("InMemoryResolver: path '${reference.path}' not found")

            !reference.name.isNullOrBlank() -> byName[reference.name]
                ?: throw WorkflowValidationException("InMemoryResolver: name '${reference.name}' not found")

            else -> throw WorkflowValidationException("InMemoryResolver: empty reference")
        }
        return ResolvedWorkflow(
            identity = identityKey(reference),
            definition = def,
            sourceLabel = reference.label()
        )
    }

    override fun identityKey(reference: WorkflowReference): String {
        return when {
            !reference.workflowId.isNullOrBlank() -> "id:${reference.workflowId}"
            !reference.path.isNullOrBlank() -> "path:${reference.path}"
            !reference.name.isNullOrBlank() -> "name:${reference.name}"
            else -> "empty"
        }
    }
}

/**
 * 文件系统解析器,CLI 场景的主要实现。
 *
 * 解析顺序:
 * 1. 优先 `path` — 相对路径以 [searchRoots] 为基,绝对路径直接读
 * 2. 次优先 `workflowId` — 扫描搜索路径,找 `name == workflowId` 或文件名匹配 id 的 YAML
 * 3. 最后 `name` — 扫描搜索路径,返回第一个 `workflow.name == ref.name` 的匹配(多个时 warning)
 *
 * 会在构造期间缓存已解析的文件,避免同一 validation 加载多次。
 *
 * @param searchRoots 搜索根目录(相对 path 基于它们),一般传父 YAML 所在目录
 */
class FileSystemWorkflowResolver(
    private val searchRoots: List<File> = listOf(File(".")),
    private val followSymlinks: Boolean = false
) : WorkflowResolver {
    // ConcurrentHashMap: 并发 DAG 步骤可能同时 resolve sub_workflow。
    private val cache = java.util.concurrent.ConcurrentHashMap<String, WorkflowDefinition>()

    /**
     * name/id → 匹配列表 的一次性扫描索引。旧实现每次 [resolveByIdOrName] 都重新
     * walk 所有 search root 并完整 parse 每个 YAML —— 静态校验阶段对每个
     * sub_workflow 引用各调一次 resolve,O(refs × files × full-parse)。索引的
     * 生命周期与 resolver 实例一致,符合"同一 validation 内缓存"的既有语义。
     */
    @Volatile
    private var scanIndex: Map<String, List<Pair<WorkflowDefinition, File>>>? = null
    private val scanLock = Any()

    private fun nameIndex(): Map<String, List<Pair<WorkflowDefinition, File>>> {
        scanIndex?.let { return it }
        synchronized(scanLock) {
            scanIndex?.let { return it }
            val byKey = mutableMapOf<String, MutableList<Pair<WorkflowDefinition, File>>>()
            for (root in searchRoots) {
                if (!root.exists() || !root.isDirectory) continue
                root.walkTopDown()
                    .onEnter { f -> followSymlinks || !java.nio.file.Files.isSymbolicLink(f.toPath()) }
                    .filter { it.isFile && (it.extension == "yaml" || it.extension == "yml") }
                    .forEach { file ->
                        try {
                            val wf = WorkflowParser.parseFile(file.absolutePath)
                            byKey.getOrPut(wf.name) { mutableListOf() }.add(wf to file)
                            // KDoc 契约:workflowId 也允许按文件名匹配。
                            if (file.nameWithoutExtension != wf.name) {
                                byKey.getOrPut(file.nameWithoutExtension) { mutableListOf() }.add(wf to file)
                            }
                        } catch (e: Exception) {
                            logger.debug { "Skipping un-parseable workflow file during scan: ${file.absolutePath}" }
                        }
                    }
            }
            val built: Map<String, List<Pair<WorkflowDefinition, File>>> = byKey
            scanIndex = built
            return built
        }
    }

    override fun resolve(reference: WorkflowReference): ResolvedWorkflow {
        val (definition, identity, sourceLabel) = when {
            !reference.path.isNullOrBlank() -> resolveByPath(reference.path)
            !reference.workflowId.isNullOrBlank() -> resolveByIdOrName(reference.workflowId)
            !reference.name.isNullOrBlank() -> resolveByIdOrName(reference.name)
            else -> throw WorkflowValidationException("FileSystemWorkflowResolver: empty reference")
        }

        if (reference.versionStrategy == SubWorkflowVersionStrategy.PINNED) {
            val pinned = reference.pinnedVersion
            if (pinned != null && definition.version != pinned) {
                throw WorkflowValidationException(
                    "FileSystemWorkflowResolver: pinned version '$pinned' does not match child workflow version '${definition.version}' at $sourceLabel"
                )
            }
        }

        return ResolvedWorkflow(identity = identity, definition = definition, sourceLabel = sourceLabel)
    }

    override fun identityKey(reference: WorkflowReference): String {
        return when {
            !reference.path.isNullOrBlank() -> {
                val canonical = canonicalizePath(reference.path)
                "path:${canonical?.absolutePath ?: reference.path}"
            }

            !reference.workflowId.isNullOrBlank() -> "id:${reference.workflowId}"
            !reference.name.isNullOrBlank() -> "name:${reference.name}"
            else -> "empty"
        }
    }

    private fun resolveByPath(path: String): Triple<WorkflowDefinition, String, String> {
        val canonicalFile = canonicalizePath(path)
            ?: throw WorkflowValidationException(
                "FileSystemWorkflowResolver: path '$path' not found under search roots ${searchRoots.map { it.absolutePath }}"
            )
        val key = "path:${canonicalFile.absolutePath}"
        val cached = cache[key]
        if (cached != null) {
            return Triple(cached, key, "path=${canonicalFile.absolutePath}")
        }
        val parsed = try {
            WorkflowParser.parseFile(canonicalFile.absolutePath)
        } catch (e: Exception) {
            throw WorkflowValidationException(
                "FileSystemWorkflowResolver: failed to parse '${canonicalFile.absolutePath}': ${e.message}",
                e
            )
        }
        cache[key] = parsed
        return Triple(parsed, key, "path=${canonicalFile.absolutePath}")
    }

    private fun resolveByIdOrName(idOrName: String): Triple<WorkflowDefinition, String, String> {
        val matches = nameIndex()[idOrName].orEmpty()
        if (matches.isEmpty()) {
            throw WorkflowValidationException(
                "FileSystemWorkflowResolver: no workflow found with name/id '$idOrName' under $searchRoots"
            )
        }
        if (matches.size > 1) {
            logger.warn {
                "FileSystemWorkflowResolver: multiple workflows match '$idOrName', using first. Matches: ${matches.map { it.second.absolutePath }}"
            }
        }
        val (def, file) = matches.first()
        val key = "path:${file.absolutePath}"
        cache[key] = def
        return Triple(def, key, "name=$idOrName(${file.absolutePath})")
    }

    private fun canonicalizePath(path: String): File? {
        val asFile = File(path)
        val candidate = if (asFile.isAbsolute) {
            asFile.takeIf { it.exists() }?.canonicalFile
        } else {
            searchRoots.asSequence()
                .map { root -> File(root, path) }
                .firstOrNull { it.exists() }
                ?.canonicalFile
        } ?: return null

        if (!followSymlinks && !isWithinAnySearchRoot(candidate)) {
            // Guard against path-traversal / symlink escape. If the resolved canonical
            // path falls outside every configured search root, the caller is (probably
            // unintentionally) reading arbitrary files from disk via a `..` segment or a
            // symlink. Callers that genuinely need out-of-tree workflows should construct
            // the resolver with `followSymlinks = true`.
            logger.warn {
                "FileSystemWorkflowResolver: refusing path '$path' — canonical ${candidate.absolutePath} " +
                    "is outside search roots ${searchRoots.map { it.absolutePath }}. " +
                    "Pass followSymlinks=true to allow out-of-tree references."
            }
            return null
        }
        return candidate
    }

    private fun isWithinAnySearchRoot(candidate: File): Boolean {
        val canonicalCandidatePath = candidate.absolutePath
        return searchRoots.any { root ->
            // Security: if canonicalFile fails (e.g. permission, I/O), do NOT fall back to
            // the un-canonicalized root — a root declared as a symlink or containing `..`
            // would then compare equal to a traversal candidate, re-opening the exact
            // hole this guard exists to close. Instead, treat the root as "unusable" and
            // keep looking at the other roots.
            val rootCanonical = runCatching { root.canonicalFile }.getOrNull()?.absolutePath
                ?: return@any false
            val rootWithSep = if (rootCanonical.endsWith(File.separator)) rootCanonical else rootCanonical + File.separator
            canonicalCandidatePath == rootCanonical || canonicalCandidatePath.startsWith(rootWithSep)
        }
    }
}
