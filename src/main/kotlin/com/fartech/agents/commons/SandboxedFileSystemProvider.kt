package com.fartech.agents.commons

import ai.koog.rag.base.files.FileMetadata
import ai.koog.rag.base.files.FileSystemProvider
import ai.koog.rag.base.files.JVMFileSystemProvider
import com.fartech.agents.tools.FileReadGuard
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.parameter
import kotlinx.io.Source
import kotlinx.io.Sink
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * 沙箱化的文件系统提供者，限制写入和读取操作。
 *
 * **写操作**（create/writeBytes/outputStream/move/copy/delete）验证目标路径是否在允许的目录中。
 * **读操作**（readBytes/inputStream）在 [readGuard] 启用时验证扩展名白名单和文件名黑名单
 *  （constraint C9, Phase 3c）。dev 模式下读操作不受限制。
 *
 * 当工作流启用目录隔离时，WorkflowExecutor 会通过参数注入 working_dir 和 output_dir，
 * 这些目录会成为允许写入的目录。
 *
 * ## 多用户约束 C7（phase 0 埋点 / phase 2 强制）
 *
 * 在多用户模式下，**任何不传 working_dir 的调用路径必须启动即崩**，不能退化成
 * 全盘 FS 访问。为此：
 *
 * 1. [createFromParameters] 当 working_dir 缺失时：
 *    - `strict = false`（默认，向后兼容）：返回 null，并打印一条 **WARN** 让运维看到
 *      这条代码路径正在绕过沙箱（单用户/dev 模式下是安全的）
 *    - `strict = true`：抛 [IllegalStateException]，让调用方立即暴露配置缺陷
 * 2. phase 2（权限与授权落地）会给 web 层的调用点全部切到 `strict = true`，并让
 *    [com.fartech.agents.workflow.WorkflowExecutor] 主动注入 `working_dir`，
 *    所以没有合法的"web 层走 strict = false"场景
 * 3. dev mode 的 CLI / 独立 agent 调用保持 `strict = false`，不破坏本地开发
 *
 * @param allowedDirectories 允许写入的目录列表（写入路径必须在这些目录之下）
 * @param delegate 底层的 ReadWrite 文件系统提供者
 */
class SandboxedFileSystemProvider(
    private val allowedDirectories: List<Path>,
    private val delegate: FileSystemProvider.ReadWrite<Path> = JVMFileSystemProvider.ReadWrite,
    private val readGuard: FileReadGuard = FileReadGuard.DISABLED
) : FileSystemProvider.ReadWrite<Path> by delegate {

    private val normalizedAllowedDirs: List<Path> = allowedDirectories.map {
        it.toFile().canonicalFile.toPath()
    }

    init {
        logger.info {
            "[Sandbox] File write sandbox enabled. Allowed directories: ${normalizedAllowedDirs.joinToString(", ")}"
        }
    }

    // ── Phase 3c: read-path validation (C9) ──
    //
    // Two distinct gates apply to read-side operations:
    //   1. **Sandbox dir check** (always): the path must be inside one of the
    //      caller-allowed directories. Applies to BOTH metadata-only ops and
    //      content reads.
    //   2. **Content gate** (extension whitelist + filename blacklist): only
    //      applies to operations that return file CONTENTS (`readBytes`,
    //      `inputStream`). Metadata-only ops (`exists`, `metadata`, `list`,
    //      `size`, `getFileContentType`) reveal at most filename / size /
    //      type, which doesn't leak the sensitive bytes the content gate is
    //      designed to protect (.env values, .pem keys, etc.).
    //
    // Why split: pre-2026-04-26, `exists()` ran the content gate, which
    // meant `__write_file__` (which probes existence first via Koog's
    // WriteFileTool) silently failed for any non-whitelisted extension.
    // PWA workflows can't write `.webmanifest`, modern-web workflows can't
    // write `.mjs`, etc. Adding extensions to the whitelist papers over the
    // symptom but doesn't fix the structural overreach. See
    // execution-process-868c6031-...-failed.yaml for the trigger case.

    override suspend fun metadata(path: Path): FileMetadata? {
        validateSandboxedReadPath(path)
        return delegate.metadata(path)
    }

    override suspend fun getFileContentType(path: Path): FileMetadata.FileContentType {
        validateSandboxedReadPath(path)
        return delegate.getFileContentType(path)
    }

    override suspend fun list(directory: Path): List<Path> {
        validateSandboxedReadPath(directory)
        return delegate.list(directory)
    }

    override suspend fun exists(path: Path): Boolean {
        validateSandboxedReadPath(path)
        return delegate.exists(path)
    }

    override suspend fun readBytes(path: Path): ByteArray {
        validateContentReadPath(path)
        return delegate.readBytes(path)
    }

    override suspend fun inputStream(path: Path): Source {
        validateContentReadPath(path)
        return delegate.inputStream(path)
    }

    override suspend fun size(path: Path): Long {
        validateSandboxedReadPath(path)
        return delegate.size(path)
    }

    // ── Write-path validation (C7) ──

    /**
     * 验证路径是否在允许的写入目录范围内。
     * 如果不在，抛出 IllegalArgumentException，消息中包含允许的目录列表，
     * 引导 LLM 在后续工具调用中使用正确的路径。
     */
    private fun validateWritePath(path: Path) {
        validatePathWithinAllowedDirectories(path, "Write")
    }

    /**
     * Sandbox-only read check. Confirms the path lies inside the caller's
     * allowed directories but does NOT enforce the extension whitelist /
     * filename blacklist. Used by metadata-only operations
     * (`exists`, `list`, `metadata`, `size`, `getFileContentType`) where
     * the operation reveals at most a filename / size / type — none of
     * which carries the secret-content the readGuard was designed to
     * protect.
     */
    private fun validateSandboxedReadPath(path: Path) {
        validatePathWithinAllowedDirectories(path, "Read")
    }

    /**
     * Full content-read check. Sandbox dir + readGuard (extension whitelist +
     * filename blacklist). Used by `readBytes` / `inputStream` only.
     */
    private fun validateContentReadPath(path: Path) {
        validatePathWithinAllowedDirectories(path, "Read")
        readGuard.validateReadPath(path)
    }

    /**
     * Operations such as copy/move do not return bytes directly, but they do
     * materialize source contents under another path. Treat regular-file
     * sources as content reads so a caller cannot rename/copy `.env` to
     * `safe.txt` and bypass [readGuard].
     */
    private fun validateContentSourcePath(path: Path, operation: String) {
        validatePathWithinAllowedDirectories(path, operation)
        if (Files.isRegularFile(path)) {
            readGuard.validateReadPath(path)
        }
    }

    private fun validatePathWithinAllowedDirectories(path: Path, operation: String) {
        val normalizedPath = path.toFile().canonicalFile.toPath()
        val isAllowed = normalizedAllowedDirs.any { allowedDir ->
            normalizedPath == allowedDir || normalizedPath.startsWith(allowedDir)
        }
        if (!isAllowed) {
            val allowedDirsStr = normalizedAllowedDirs.joinToString("\n  - ")
            logger.warn {
                "[Sandbox] $operation access denied for path: $normalizedPath. " +
                    "Allowed directories: $normalizedAllowedDirs"
            }
            throw IllegalArgumentException(
                "$operation access denied: '$normalizedPath' is outside the allowed working directories. " +
                    "You MUST operate within one of these directories:\n  - $allowedDirsStr\n" +
                    "Please use an absolute path within these directories."
            )
        }
    }

    override suspend fun create(path: Path, type: FileMetadata.FileType) {
        validateWritePath(path)
        delegate.create(path, type)
    }

    override suspend fun writeBytes(path: Path, data: ByteArray) {
        validateWritePath(path)
        delegate.writeBytes(path, data)
    }

    override suspend fun outputStream(path: Path, append: Boolean): Sink {
        validateWritePath(path)
        return delegate.outputStream(path, append)
    }

    override suspend fun move(source: Path, target: Path) {
        validateContentSourcePath(source, "Move source")
        validateWritePath(target)
        delegate.move(source, target)
    }

    override suspend fun copy(source: Path, target: Path) {
        validateContentSourcePath(source, "Copy source")
        validateWritePath(target)
        delegate.copy(source, target)
    }

    override suspend fun delete(path: Path) {
        validateWritePath(path)
        delegate.delete(path)
    }

    companion object {
        /**
         * Parameter key that lets the caller force strict sandbox mode without touching the
         * [createFromParameters] call signature. When this parameter is present and equals
         * "true", a missing `working_dir` triggers an [IllegalStateException] instead of the
         * soft `null` fallback. `braidrun-web` flips this to `"true"` in STAGING and
         * PRODUCTION environments as part of phase 2.
         */
        const val STRICT_SANDBOX_PARAMETER: String = "sandbox_strict"

        /**
         * 从 Agent 参数中创建沙箱化文件系统提供者。
         *
         * 如果参数中包含 working_dir，则创建沙箱化提供者，
         * 限制写入操作只能在 working_dir / output_dir / 系统 /tmp 下进行。
         *
         * 当 working_dir 缺失时的行为由 [strict] 控制：
         *  - `strict = false`（默认）：打印一条 WARN 日志，返回 null（调用方通常会退化成
         *     [JVMFileSystemProvider.ReadWrite]）。这是**单用户 / dev 模式**下的合法兜底。
         *  - `strict = true`：抛 [IllegalStateException]，明确告诉调用方配置有问题。
         *     多用户 / SaaS 部署必须走这条分支。
         *
         * 调用方也可以通过设置 `parameters` 里的 [STRICT_SANDBOX_PARAMETER]=`"true"` 来强制
         * 开启 strict 模式而不必改调用签名；这样 `braidrun-web` 只需要在构造
         * `WorkflowExecutor` 参数时注入一个布尔即可打开全局 strict，不需要跨 10+ 个 agent
         * 工具构造器改 API。
         *
         * @param parameters Agent 配置参数列表
         * @param strict 是否强制 strict 模式。当 parameters 里已经注入了
         *               [STRICT_SANDBOX_PARAMETER]=`"true"` 时，该值会被 OR 进来
         * @return 沙箱化的 ReadWrite 提供者，或 null（strict=false 且 working_dir 缺失时）
         * @throws IllegalStateException strict=true 且 working_dir 缺失时
         */
        fun createFromParameters(
            parameters: List<ConfigurationParameter>,
            strict: Boolean = false,
            readGuard: FileReadGuard = FileReadGuard.DISABLED
        ): SandboxedFileSystemProvider? {
            val workingDir = parameters.parameter("working_dir", "")
            val outputDir = parameters.parameter("output_dir", "")
            val parameterStrict = parameters.parameter(STRICT_SANDBOX_PARAMETER, "false")
                .equals("true", ignoreCase = true)
            val effectiveStrict = strict || parameterStrict

            if (workingDir.isBlank()) {
                if (effectiveStrict) {
                    throw IllegalStateException(
                        "[Sandbox/C7] Refusing to create an unsandboxed FileSystemProvider: " +
                            "`working_dir` parameter is missing or blank. In multi-user mode this " +
                            "would expose the full host filesystem to workflow agents. " +
                            "Either (a) inject `working_dir` via WorkflowExecutor / DirectoryIsolationConfig, " +
                            "or (b) run in single-user mode by clearing `${STRICT_SANDBOX_PARAMETER}`."
                    )
                }
                logger.warn {
                    "[Sandbox/C7] Falling back to full-FS JVMFileSystemProvider.ReadWrite because " +
                        "`working_dir` parameter is missing. This is only safe in SINGLE-USER / dev mode. " +
                        "Set `${STRICT_SANDBOX_PARAMETER}=true` in the agent parameters (or pass " +
                        "`strict = true` to createFromParameters) to make this a hard failure."
                }
                return null
            }

            val allowedDirs = mutableListOf<Path>()

            // 工作目录（步骤级隔离目录）
            allowedDirs.add(Path.of(workingDir))

            // 输出目录（执行级共享输出目录）
            if (outputDir.isNotBlank()) {
                allowedDirs.add(Path.of(outputDir))
            }

            // 系统临时目录（Agent 可能需要写入临时文件）
            val tempDir = System.getProperty("java.io.tmpdir", "/tmp")
            allowedDirs.add(Path.of(tempDir))

            return SandboxedFileSystemProvider(allowedDirs, readGuard = readGuard)
        }
    }
}
