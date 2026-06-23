package com.fartech.agents.workflow

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import com.fartech.agents.commons.*
import com.fartech.agents.tools.ExternalAgentContext
import com.fartech.agents.tools.ExternalAgentTools
import com.fartech.agents.tools.RAGTools
import com.fartech.agents.tools.exec.SubprocessExecutor
import com.fartech.agents.tools.exec.SubprocessToolContext
import com.fartech.agents.tools.exec.DockerSubprocessExecutor
import com.fartech.agents.tools.EmbedderFactory
import com.fartech.ftapp2.commonsKt.AnsiColor
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import com.fartech.ftapp2.commonsKt.parameter
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}
private val workflowJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }
private val structuredOutputFileJson = Json { prettyPrint = true; encodeDefaults = false }
private const val MATERIALIZED_SKILLS_MARKER = ".materialized-skills.json"
private val skillMaterializationLocks: Array<Any> = Array(64) { Any() }
private val CODE_STEP_ENV_ALLOWLIST = setOf(
    "PATH",
    "HOME",
    "USER",
    "LOGNAME",
    "LANG",
    "LC_ALL",
    "LC_CTYPE",
    "TZ",
    "TMPDIR",
    "TEMP",
    "TMP",
    "SHELL",
    "PWD",
    "SYSTEMROOT",
    "COMSPEC"
)
private val PYTHON_SPILLED_ENV_BRIDGE = """
    import os
    from pathlib import Path

    _braidrun_original_getitem = type(os.environ).__getitem__
    _braidrun_original_contains = type(os.environ).__contains__
    _braidrun_original_iter = type(os.environ).__iter__
    _braidrun_original_len = type(os.environ).__len__

    def _braidrun_raw_get(key, default=None):
        try:
            return _braidrun_original_getitem(os.environ, key)
        except KeyError:
            return default

    def _braidrun_read_spilled_env(key, default=None):
        file_path = _braidrun_raw_get(f"{key}__FILE")
        if not file_path:
            return default
        try:
            return Path(file_path).read_text(encoding="utf-8")
        except OSError:
            return default

    def _braidrun_environ_get(key, default=None):
        value = _braidrun_raw_get(key)
        if value is not None:
            return value
        return _braidrun_read_spilled_env(key, default)

    def _braidrun_environ_getitem(self, key):
        try:
            return _braidrun_original_getitem(self, key)
        except KeyError:
            value = _braidrun_read_spilled_env(key)
            if value is None:
                raise
            return value

    def _braidrun_environ_contains(self, key):
        return _braidrun_original_contains(self, key) or _braidrun_raw_get(f"{key}__FILE") is not None

    def _braidrun_virtual_spilled_keys():
        original_keys = set(_braidrun_original_iter(os.environ))
        for file_key in list(original_keys):
            if not file_key.endswith("__FILE"):
                continue
            key = file_key[:-6]
            if key and key not in original_keys and _braidrun_read_spilled_env(key) is not None:
                yield key

    def _braidrun_environ_iter(self):
        yield from _braidrun_original_iter(self)
        yield from _braidrun_virtual_spilled_keys()

    def _braidrun_environ_len(self):
        return _braidrun_original_len(self) + sum(1 for _ in _braidrun_virtual_spilled_keys())

    def _braidrun_environ_copy():
        return dict(os.environ)

    def _braidrun_load_chained_sitecustomize():
        import importlib.util
        import sys

        current_file = Path(__file__).resolve()
        for path_entry in sys.path:
            if not path_entry:
                continue
            candidate = Path(path_entry) / "sitecustomize.py"
            try:
                if not candidate.is_file() or candidate.resolve() == current_file:
                    continue
            except OSError:
                continue
            spec = importlib.util.spec_from_file_location("_braidrun_chained_sitecustomize", candidate)
            if spec is not None and spec.loader is not None:
                module = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(module)
            break

    os.environ.get = _braidrun_environ_get
    os.environ.copy = _braidrun_environ_copy
    type(os.environ).__getitem__ = _braidrun_environ_getitem
    type(os.environ).__contains__ = _braidrun_environ_contains
    type(os.environ).__iter__ = _braidrun_environ_iter
    type(os.environ).__len__ = _braidrun_environ_len
    _braidrun_load_chained_sitecustomize()
""".trimIndent()
private val NODE_SPILLED_ENV_BRIDGE = """
    const fs = require('fs');

    const baseEnv = process.env;
    const cache = new Map();

    function hasSpilledEnv(key) {
      return Object.prototype.hasOwnProperty.call(baseEnv, `${'$'}{key}__FILE`);
    }

    function readSpilledEnv(key) {
      if (cache.has(key)) return cache.get(key);
      const filePath = baseEnv[`${'$'}{key}__FILE`];
      if (!filePath) return undefined;
      try {
        const value = fs.readFileSync(filePath, 'utf8');
        cache.set(key, value);
        return value;
      } catch {
        return undefined;
      }
    }

    process.env = new Proxy(baseEnv, {
      get(target, prop) {
        if (typeof prop !== 'string') return target[prop];
        if (Object.prototype.hasOwnProperty.call(target, prop)) return target[prop];
        return readSpilledEnv(prop);
      },
      has(target, prop) {
        if (typeof prop !== 'string') return prop in target;
        return prop in target || hasSpilledEnv(prop);
      },
      getOwnPropertyDescriptor(target, prop) {
        if (Reflect.has(target, prop)) return Reflect.getOwnPropertyDescriptor(target, prop);
        if (typeof prop === 'string' && hasSpilledEnv(prop)) {
          return {
            enumerable: true,
            configurable: true,
            writable: true,
            value: readSpilledEnv(prop)
          };
        }
        return undefined;
      },
      ownKeys(target) {
        const keys = new Set(Reflect.ownKeys(target));
        for (const key of Object.keys(target)) {
          if (key.endsWith('__FILE')) keys.add(key.slice(0, -6));
        }
        return Array.from(keys);
      }
    });
""".trimIndent()
private const val NODE_SPILLED_ENV_BRIDGE_MODULE = "braidrun-spilled-env-bridge"
private val SHELL_SPILLED_ENV_BRIDGE = """
    # Braidrun: restore spilled workflow env values as shell variables.
    # Values stay non-exported to avoid reintroducing ARG_MAX failures for child processes.
    _braidrun_restore_spilled_env() {
      local _braidrun_file_key _braidrun_var _braidrun_file
      while IFS='=' read -r _braidrun_file_key _; do
        case "${'$'}_braidrun_file_key" in
          *__FILE) ;;
          *) continue ;;
        esac
        _braidrun_var="${'$'}{_braidrun_file_key%__FILE}"
        case "${'$'}_braidrun_var" in
          ""|[0-9]*|*[!A-Za-z0-9_]*) continue ;;
        esac
        _braidrun_file="${'$'}{!_braidrun_file_key:-}"
        if [ -r "${'$'}_braidrun_file" ]; then
          IFS= read -r -d '' "${'$'}_braidrun_var" < "${'$'}_braidrun_file" || true
        fi
      done < <(env)
    }
    _braidrun_restore_spilled_env
    unset -f _braidrun_restore_spilled_env
""".trimIndent()

object WorkflowRuntimeParameterKeys {
    private const val CODE_INTERPRETER_PREFIX = "workflow_code_interpreter_"

    fun codeInterpreter(language: String): String = CODE_INTERPRETER_PREFIX + language.lowercase()
}

private val PROTECTED_RUNTIME_PARAMETER_KEYS = setOf(
    "subprocess_mode",
    "sandbox_strict",
    "user_id"
)

internal fun containsGroupChatTerminationSignal(response: String, terminationKeyword: String): Boolean {
    val keyword = terminationKeyword.trim()
    if (keyword.isEmpty()) return false

    val labelPattern = Regex(
        """(?:final\s+status|status|final|conclusion|consensus|decision|result|结论|共识|状态|最终结论|最终状态)\s*[:：-]\s*${Regex.escape(keyword)}""",
        RegexOption.IGNORE_CASE
    )

    val segments = response
        .replace("\r\n", "\n")
        .split('\n')
        .flatMap { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) emptyList()
            else trimmed.split(Regex("""(?<=[.!?。！？])\s+""")).map { it.trim() }
        }

    return segments.any { segment ->
        val normalized = normalizeGroupChatTerminationSegment(segment)
        normalized.equals(keyword, ignoreCase = true) || labelPattern.matches(normalized)
    }
}

private fun normalizeGroupChatTerminationSegment(segment: String): String {
    var text = segment.trim()
        .replace(Regex("""^[-*+>]+\s*"""), "")
        .replace(Regex("""^\d+[.)]\s*"""), "")
        .replace(Regex("""^#+\s*"""), "")
        .trim()

    val wrapperPairs = listOf(
        "**" to "**",
        "__" to "__",
        "`" to "`",
        "\"" to "\"",
        "'" to "'",
        "“" to "”",
        "‘" to "’",
        "(" to ")",
        "[" to "]",
        "{" to "}",
        "<" to ">"
    )

    var stripped = true
    while (stripped && text.isNotEmpty()) {
        stripped = false
        for ((prefix, suffix) in wrapperPairs) {
            if (text.startsWith(prefix) && text.endsWith(suffix) && text.length > prefix.length + suffix.length) {
                text = text.substring(prefix.length, text.length - suffix.length).trim()
                stripped = true
            }
        }
    }

    return text.trimEnd('.', '!', '?', '。', '！', '？', ',', '，', ';', '；').trim()
}

internal data class GroupChatTerminationReadiness(
    val canTerminate: Boolean,
    val missingParticipants: List<String> = emptyList(),
    val reason: String? = null
)

private data class MaterializedSkillsRuntime(
    val config: SkillsConfiguration,
    val primarySkillsDir: String?
)

@Serializable
private data class MaterializedSkillsMarker(
    val sourcePath: String
)

internal fun assessGroupChatTerminationReadiness(
    config: GroupChatConfig,
    speakerName: String,
    chatHistory: List<GroupChatMessage>,
    includeCurrentSpeaker: Boolean = false
): GroupChatTerminationReadiness {
    val spokenParticipants = chatHistory.asSequence()
        .map { it.speaker }
        .filter { it in config.participants }
        .toMutableSet()

    if (includeCurrentSpeaker) {
        spokenParticipants.add(speakerName)
    }

    val missingParticipants = config.participants.filterNot { it in spokenParticipants }

    if (config.moderator != null && speakerName != config.moderator) {
        return GroupChatTerminationReadiness(
            canTerminate = false,
            missingParticipants = missingParticipants,
            reason = "Only moderator '${config.moderator}' may terminate the discussion."
        )
    }

    if (missingParticipants.isNotEmpty()) {
        return GroupChatTerminationReadiness(
            canTerminate = false,
            missingParticipants = missingParticipants,
            reason = "Waiting for all participants to contribute at least once: ${missingParticipants.joinToString(", ")}"
        )
    }

    return GroupChatTerminationReadiness(canTerminate = true)
}

internal data class PersistedFileValidationResult(
    val checkedPaths: List<String>,
    val missingPaths: List<String>,
    val stalePaths: List<String>
) {
    val isValid: Boolean get() = missingPaths.isEmpty() && stalePaths.isEmpty()
}

private val persistedFileClaimPatterns = listOf(
    Regex("""(?:保存到|保存至|保存为|写入到|写入至|写到|输出到|输出至|落盘到)\s*[:：]?\s*[`"'“”]?([^\n\r`"'“”]+)"""),
    Regex("""(?:save|saved|write|written)(?:\s+[A-Za-z0-9._-]+){0,4}\s+(?:to|at|into)\s*[:：]?\s*[`"'“”]?([^\n\r`"'“”]+)""", RegexOption.IGNORE_CASE)
)

private val promptPersistedFilePathPatterns = listOf(
    Regex("""(?:保存到|保存至|保存为|写入到|写入至|写到|输出到|输出至|落盘到)\s*[:：]?\s*[`"'“”]?([^\n\r`"'“”]+)"""),
    Regex("""(?:save|write)(?:\s+[A-Za-z0-9._-]+){0,4}\s+(?:to|at|into)\s*[:：]?\s*[`"'“”]?([^\n\r`"'“”]+)""", RegexOption.IGNORE_CASE),
    Regex("""(?:创建|生成|更新|修改)\s*[`"'“”]?([^\n\r`"'“”]*?[A-Za-z0-9_-]+\.[A-Za-z0-9._-]+[^\n\r`"'“”]*)"""),
    Regex(
        """(?<![A-Za-z])(?:create|generate|update|modify)(?![A-Za-z])(?:\s+(?:the|a|an))?(?:\s+[A-Za-z0-9][A-Za-z0-9._-]*){0,3}\s*[`"'“”]?([^\n\r`"'“”]*?[A-Za-z0-9_-]+\.[A-Za-z0-9._-]+[^\n\r`"'“”]*)""",
        RegexOption.IGNORE_CASE
    )
)

private val optionalPersistedFileContextPatterns = listOf(
    Regex("""演示约束"""),
    Regex("""(?:如适合|如果适合|可选|按需|optionally|if appropriate|if suitable)""", RegexOption.IGNORE_CASE),
    Regex("""(?:may|can)\s+also\s+(?:save|write)""", RegexOption.IGNORE_CASE),
    Regex("""(?:也可|也可以|还可|还可以)(?:同时)?(?:保存|写入)""")
)

private val historicalPersistedFileContextPatterns = listOf(
    Regex("""(?:已|已经)\s*(?:保存到|保存至|保存为|写入到|写入至|写到|输出到|输出至|落盘到)"""),
    Regex(
        """(?:saved|written|generated|created|updated|modified)(?:\s+[A-Za-z0-9._-]+){0,4}\s+(?:to|at|into)""",
        RegexOption.IGNORE_CASE
    )
)

internal fun extractPersistedFilePathsFromText(text: String, baseDir: String? = null): Set<String> {
    if (text.isBlank()) return emptySet()

    return persistedFileClaimPatterns
        .flatMap { pattern ->
            pattern.findAll(text).mapNotNull { extractLeadingPersistedFilePath(it.groupValues[1], baseDir) }.toList()
        }
        .toSortedSet()
}

internal fun extractRequiredPersistedFilePathCandidatesFromPrompt(text: String): Set<String> {
    if (text.isBlank()) return emptySet()

    return promptPersistedFilePathPatterns
        .flatMap { pattern ->
            pattern.findAll(text)
                .filterNot { isOptionalPersistedFileMatch(text, it) }
                .filterNot { isHistoricalPersistedFileMatch(text, it) }
                .mapNotNull { extractLeadingPersistedFilePathCandidate(it.groupValues[1]) }
                .toList()
        }
        .toSortedSet()
}

internal fun resolvePersistedFilePathCandidates(
    candidates: Set<String>,
    baseDir: String? = null,
    resolver: (String) -> String = { it }
): Set<String> {
    if (candidates.isEmpty()) return emptySet()

    return candidates
        .mapNotNull { candidate -> normalizePersistedFilePath(resolver(candidate), baseDir) }
        .toSortedSet()
}

internal fun extractRequiredPersistedFilePathsFromPrompt(text: String, baseDir: String? = null): Set<String> {
    return resolvePersistedFilePathCandidates(
        candidates = extractRequiredPersistedFilePathCandidatesFromPrompt(text),
        baseDir = baseDir
    )
}

internal fun pruneClaimedPersistedFilePaths(
    expectedPersistedFiles: Set<String>,
    claimedPersistedFiles: Set<String>
): Set<String> {
    if (claimedPersistedFiles.isEmpty()) return emptySet()

    return claimedPersistedFiles.filterNot { claimedPath ->
        if (claimedPath in expectedPersistedFiles) {
            return@filterNot false
        }

        expectedPersistedFiles.any { expectedPath ->
            isLikelyTruncatedPersistedPath(claimedPath, expectedPath)
        } || claimedPersistedFiles.any { otherClaimedPath ->
            otherClaimedPath != claimedPath && isLikelyTruncatedPersistedPath(claimedPath, otherClaimedPath)
        }
    }.toSortedSet()
}

internal fun selectPersistedFilePathsForValidation(
    expectedPersistedFiles: Set<String>,
    claimedPersistedFiles: Set<String>
): Set<String> {
    if (claimedPersistedFiles.isNotEmpty() && expectedPersistedFiles.isEmpty()) {
        return emptySet()
    }
    return expectedPersistedFiles.toSortedSet()
}

internal fun autoPersistStepOutputIfPossible(
    output: String,
    expectedPersistedFiles: Set<String>,
    claimedPersistedFiles: Set<String>,
    validation: PersistedFileValidationResult
): String? {
    if (output.isBlank()) return null
    if (claimedPersistedFiles.isNotEmpty()) return null
    if (expectedPersistedFiles.size != 1) return null
    if (validation.stalePaths.isNotEmpty()) return null

    val targetPath = expectedPersistedFiles.single()
    if (validation.missingPaths != listOf(targetPath)) return null
    if (!isTextLikePersistedFilePath(targetPath)) return null

    val targetFile = File(targetPath)
    targetFile.parentFile?.mkdirs()
    targetFile.writeText(output)
    return targetFile.path
}

internal fun externalStructuredOutputPersistedFiles(
    structuredOutput: StructuredOutputConfig?,
    isExternalAgent: Boolean
): Set<String> {
    if (!isExternalAgent) return emptySet()
    val writeTo = structuredOutput?.writeTo?.trim().orEmpty()
    return if (writeTo.isBlank()) emptySet() else setOf(writeTo)
}

internal fun validatePersistedFilePaths(
    paths: Set<String>,
    stepStartedAt: Long,
    freshnessSlackMs: Long = 1000L
): PersistedFileValidationResult {
    if (paths.isEmpty()) {
        return PersistedFileValidationResult(emptyList(), emptyList(), emptyList())
    }

    val checkedPaths = paths.toList().sorted()
    val missingPaths = mutableListOf<String>()
    val stalePaths = mutableListOf<String>()

    for (path in checkedPaths) {
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            missingPaths += path
            continue
        }

        if (file.lastModified() + freshnessSlackMs < stepStartedAt) {
            stalePaths += path
        }
    }

    return PersistedFileValidationResult(
        checkedPaths = checkedPaths,
        missingPaths = missingPaths,
        stalePaths = stalePaths
    )
}

private fun normalizePersistedFilePath(path: String, baseDir: String? = null): String? {
    val trimmedPath = path.trim()
        .trimEnd('.', ',', ':', '：', ';', '；', '。', '！', '？', ')', ']', '}', '>', '`', '"', '\'', '”', '’')

    if (trimmedPath.isBlank()) return null
    if (trimmedPath.contains("://")) return null
    if ("{{" in trimmedPath || "}}" in trimmedPath) return null
    // 2026-04-26 — Reject placeholder syntax. Prompt examples like
    // `write to <OUTPUT_DIR>/<relative>` would otherwise be matched as a
    // literal "expected file" (a real production execution failed because
    // the validator looked for a file named literally `<相对` after stripping
    // the trailing `>`). Real POSIX paths can contain `<` / `>` but it is
    // vanishingly rare; on Windows these chars are forbidden in filenames
    // entirely. Treating them as placeholder markers loses ~zero true
    // positives and prevents the entire class of "agent prompt template
    // text leaks into validator" failures.
    if ('<' in trimmedPath || '>' in trimmedPath) return null
    // Unresolved shell-style template (`${VAR}` / `${VAR:-default}`) is
    // never a real path either — the workflow engine would have resolved
    // workflow-level variables already, so a `${...}` left over means it
    // was inside a code-fenced example or a literal bash snippet.
    if ("\${" in trimmedPath) return null
    if (trimmedPath.endsWith("/") || trimmedPath.endsWith("\\")) return null
    if (!looksLikePersistedFilePath(trimmedPath)) return null

    val resolvedFile = when {
        trimmedPath.startsWith("~/") -> File(System.getProperty("user.home"), trimmedPath.removePrefix("~/"))
        File(trimmedPath).isAbsolute -> File(trimmedPath)
        baseDir != null -> File(baseDir, trimmedPath.removePrefix("./"))
        else -> File(trimmedPath)
    }

    return resolvedFile.absoluteFile.toPath().normalize().toFile().path
}

private fun extractLeadingPersistedFilePath(candidate: String, baseDir: String? = null): String? {
    return extractLeadingPersistedFilePathCandidate(candidate)?.let { normalizePersistedFilePath(it, baseDir) }
}

private fun extractLeadingPersistedFilePathCandidate(candidate: String): String? {
    val tokens = candidate
        .trim()
        .trimStart('`', '"', '\'', '“', '”')
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }

    if (tokens.isEmpty()) return null

    val collected = mutableListOf<String>()

    for ((index, rawToken) in tokens.withIndex()) {
        val (token, hardStop) = sanitizePersistedFilePathToken(rawToken)
        if (token.isBlank()) break

        if (index == 0) {
            collected += token
            if (hardStop) break
            continue
        }

        if (!shouldContinuePersistedFilePath(token)) {
            if (isDirectoryMarker(token)) return null
            break
        }

        collected += token
        if (hardStop) break
    }

    if (collected.isEmpty()) return null
    return collected.joinToString(" ")
}

private fun sanitizePersistedFilePathToken(token: String): Pair<String, Boolean> {
    val trimmed = token.trim().trimStart('`', '"', '\'', '“', '”')
    if (trimmed.isBlank()) return "" to true

    val inlineTerminatorIndex = trimmed.indexOfAny(charArrayOf('。', '！', '？', '，', '；'))
    val cutToken = if (inlineTerminatorIndex >= 0) {
        trimmed.substring(0, inlineTerminatorIndex)
    } else {
        trimmed
    }

    val normalized = cutToken.trimEnd(
        '.', ',', ':', '：', ';', '；', '。', '！', '？',
        ')', ']', '}', '>', '`', '"', '\'', '”', '’'
    )

    val hardStop = inlineTerminatorIndex >= 0 || trimmed.lastOrNull() in setOf(
        '.', ',', ':', '：', ';', '；', '。', '！', '？',
        ')', ']', '}', '>', '`', '"', '\'', '”', '’'
    )

    return normalized to hardStop
}

private fun shouldContinuePersistedFilePath(token: String): Boolean {
    if (token.contains('/') || token.contains('\\')) return true

    val fileName = token.substringAfterLast('/').substringAfterLast('\\')
    val dotIndex = fileName.indexOf('.')
    return dotIndex > 0 && dotIndex < fileName.length - 1
}

private fun isDirectoryMarker(token: String): Boolean {
    val normalized = token.trim().trimEnd('.', ',', ':', ';', '。').lowercase()
    return normalized in setOf("目录", "文件夹", "directory", "directories", "folder", "folders")
}

private fun isLikelyTruncatedPersistedPath(prefix: String, fullPath: String): Boolean {
    if (prefix == fullPath || !fullPath.startsWith(prefix)) return false
    val nextChar = fullPath.getOrNull(prefix.length) ?: return false
    return nextChar == ' ' || nextChar == '/' || nextChar == '\\'
}

private fun isTextLikePersistedFilePath(path: String): Boolean {
    val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (extension.isBlank()) return true

    return extension in setOf(
        "txt", "md", "markdown", "json", "yaml", "yml", "csv", "tsv",
        "html", "htm", "xml", "sql", "log",
        "js", "mjs", "cjs", "ts", "jsx", "tsx", "css", "scss", "less",
        "java", "kt", "kts", "py", "rb", "php", "go", "rs", "swift", "dart",
        "sh", "bash", "zsh", "ps1",
        "c", "cc", "cpp", "cxx", "h", "hpp",
        "vue", "svelte"
    )
}

private fun isOptionalPersistedFileMatch(text: String, match: MatchResult): Boolean {
    val line = extractPersistedFileMatchLine(text, match)
    val localMatchStart = (match.range.first - line.startIndex).coerceAtLeast(0)
    val localMatchEnd = ((match.range.last + 1) - line.startIndex).coerceIn(localMatchStart, line.value.length)
    val prefix = line.value.substring(maxOf(0, localMatchStart - 120), localMatchStart)
    val suffix = line.value.substring(localMatchEnd, minOf(line.value.length, localMatchEnd + 80))

    return optionalPersistedFileContextPatterns.any { pattern ->
        pattern.containsMatchIn(prefix) || pattern.containsMatchIn(suffix)
    }
}

private fun isHistoricalPersistedFileMatch(text: String, match: MatchResult): Boolean {
    val line = extractPersistedFileMatchLine(text, match).value
    return historicalPersistedFileContextPatterns.any { pattern ->
        pattern.containsMatchIn(line)
    }
}

private data class PersistedFileMatchLine(
    val value: String,
    val startIndex: Int
)

private fun extractPersistedFileMatchLine(text: String, match: MatchResult): PersistedFileMatchLine {
    val matchStart = match.range.first
    val matchEnd = match.range.last + 1
    val lineStart = text.lastIndexOf('\n', startIndex = (matchStart - 1).coerceAtLeast(0)).let { index ->
        if (index < 0) 0 else index + 1
    }
    val lineEnd = text.indexOf('\n', startIndex = matchEnd).let { index ->
        if (index < 0) text.length else index
    }
    return PersistedFileMatchLine(
        value = text.substring(lineStart, lineEnd),
        startIndex = lineStart
    )
}

private fun looksLikePersistedFilePath(path: String): Boolean {
    if (path.startsWith("/") || path.startsWith("./") || path.startsWith("../") || path.startsWith("~/")) {
        return true
    }

    if ("/" in path) {
        return true
    }

    val fileName = path.substringAfterLast('/')
    val dotIndex = fileName.indexOf('.')
    return dotIndex > 0 && dotIndex < fileName.length - 1
}

/**
 * 工作流执行引擎
 *
 * 核心功能:
 * - 解析工作流定义
 * - 管理步骤执行顺序（DAG 拓扑排序）
 * - 处理条件分支
 * - 协调并行任务
 * - 错误处理和重试
 * - 超时控制
 */

/**
 * Coroutine-safe replacement for ThreadLocal: carries the sub-workflow call stack
 * through coroutine dispatching to prevent cycle detection from being bypassed
 * when a coroutine resumes on a different thread.
 */
internal class SubWorkflowStackElement(
    val stack: MutableList<String> = mutableListOf()
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SubWorkflowStackElement>
}

class WorkflowExecutor(
    private val httpAccess: HttpAccess,
    private val baseParameters: List<ConfigurationParameter>,
    private val enableMonitoring: Boolean = true,
    private val approvalHandler: ApprovalHandler? = null,
    private val debugController: WorkflowDebugController? = null,
    /**
     * 跨工作流引用解析器(子工作流场景)。
     * - null 表示不支持 sub_workflow 步骤,遇到时抛运行时错误
     * - 通常 CLI 场景由 [FileSystemWorkflowResolver] 注入,Web 场景由 WebWorkflowResolver 注入
     */
    private val workflowResolver: WorkflowResolver? = null,
    /**
     * Code step 执行器。当非 null 时,`executeCodeStep` 通过此 executor 运行脚本
     * (在 Docker 或 Native 沙箱中);当 null 时回退到直接 ProcessBuilder 执行,
     * 保持向后兼容(CLI 模式和现有测试)。
     *
     * Web 场景中由 [ExecutionService] 注入:
     * - PRODUCTION/STAGING → [DockerSubprocessExecutor]
     * - DEVELOPMENT → [NativeSubprocessExecutor]
     */
    private val codeStepExecutor: SubprocessExecutor? = null,
    /**
     * 额外注入到 `code:` 步骤子进程的环境变量。设计用于透明代理（`HTTP_PROXY` /
     * `HTTPS_PROXY` / `ALL_PROXY` / `NO_PROXY` 以及小写变体），但本类不做语义
     * 假设——调用方放什么进来就透传什么。
     *
     * 工作流级 key 与用户工作流变量（`WF_VAR_*`）/ 步骤输出（`STEP_OUTPUT_*`）
     * 相互独立，发生冲突时以本映射优先（代理配置由平台控制，不应被工作流作者
     * 随意覆盖）。
     *
     * 默认空映射，向后兼容 CLI 与现有 web 调用。
     */
    private val extraCodeStepEnv: Map<String, String> = emptyMap()
) {

    /**
     * Non-coroutine accessor for the current execution context.
     * Used only by [modifyDebugVariables] which is called from non-suspend code.
     * For suspend functions, use `coroutineContext[ExecutionContextElement]?.context`.
     */
    @Volatile
    private var currentExecutionContext: WorkflowExecutionContext? = null

    // ── Cached constants ──────────────────────────────────────────────────
    private val isWindows by lazy { System.getProperty("os.name").lowercase().contains("win") }

    companion object {
        /** Pre-compiled regex for rewriting ./output paths to absolute paths. */
        private val OUTPUT_PATH_REGEX = Regex("""(?<![\w/.-])\./output(?=(/|\\|$))""")
        private const val HOST_OUTPUT_DIR_RUNTIME_KEY = "__braidrun_host_output_dir"
        private const val CONTAINER_OUTPUT_DIR = "/output"

        /** Pre-compiled regex for sanitizing env var keys. */
        private val ENV_KEY_SANITIZE_REGEX = Regex("[^A-Z0-9_]")

        /** Max events stored per step to prevent unbounded memory growth. */
        private const val MAX_EVENTS_PER_STEP = 500
        private const val CODE_STEP_ENV_FILE_THRESHOLD_BYTES = 16 * 1024

        /** 子工作流最大嵌套深度的默认值,可被 [ErrorHandlingConfig.maxSubWorkflowDepth] 或 [SubWorkflowConfig.maxDepth] 覆盖 */
        const val DEFAULT_MAX_SUB_WORKFLOW_DEPTH = 8

        /** Single-pass JSON string escaper — avoids 4× intermediate string allocations. */
        private fun escapeJsonValue(s: String): String = buildString(s.length + 16) {
            for (ch in s) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }
    }

    private data class PendingApproval(
        val request: ApprovalRequest,
        val decision: CompletableDeferred<ApprovalDecision> = CompletableDeferred()
    )

    private data class CodeInterpreterResolution(
        val command: List<String>,
        val suffix: String
    )

    private data class ManagedAgent(
        val agent: AIAgent<String, String>,
        val sessionId: String,
        val skillManager: SkillManager?,
        val parameters: List<ConfigurationParameter>
    )

    private data class PreparedAgentRuntime(
        val sessionId: String,
        val parameters: List<ConfigurationParameter>,
        val systemPrompt: String,
        val toolRegistry: ToolRegistry,
        val skillManager: SkillManager?,
        val installFeatures: GraphAIAgent.FeatureContext.() -> Unit,
        val reasoningCallback: ReasoningMonitorCallback?
    )

    private enum class DependencyStatus {
        SATISFIED,
        PENDING,
        FAILED
    }

    private enum class TransitionOutcome {
        SUCCESS,
        FAILURE
    }

    private data class IncomingTransitionTrigger(
        val sourceStep: String,
        val outcome: TransitionOutcome
    )

    private data class RunnableStepScan(
        val runnableSteps: List<WorkflowStep>,
        val progressMade: Boolean
    )

    private data class ConcurrentStepCompletion(
        val stepName: String,
        val shouldStop: Boolean = false,
        val error: Throwable? = null
    )

    private data class TransitionHandlingResult(
        val shouldStop: Boolean = false,
        val activatedTargets: Set<String> = emptySet(),
        val rollbackExecuted: Boolean = false
    ) {
        val hasRecoveryPath: Boolean
            get() = shouldStop || activatedTargets.isNotEmpty() || rollbackExecuted

        fun merge(other: TransitionHandlingResult): TransitionHandlingResult {
            return TransitionHandlingResult(
                shouldStop = shouldStop || other.shouldStop,
                activatedTargets = activatedTargets + other.activatedTargets,
                rollbackExecuted = rollbackExecuted || other.rollbackExecuted
            )
        }
    }

    private val activeApprovals = ConcurrentHashMap<String, PendingApproval>()
    private val notifierScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Per-execution deadline tracker for the workflow-level total timeout. Replaces
     * the previous `withTimeout(workflow.timeout.total) { runWorkflowBody() }` wrap
     * that would cancel any suspending child — including manual_approval waits —
     * after the configured wall-clock budget. Manual approvals can legitimately
     * suspend for hours (their own `manualApproval.timeout` defaults to 24 h)
     * and shouldn't count against the workflow's *active execution* budget.
     *
     * Mechanics:
     *  - Recorded once per [execute] call when `workflow.timeout.total` is set.
     *  - [checkWorkflowDeadline] fires at every step iteration boundary in both
     *    sequential and concurrent runners and throws [WorkflowExecutionException]
     *    when `now - startTime - approvalWait > totalTimeoutMs`.
     *  - [handleManualApproval] credits the approval's wall-clock wait to
     *    `approvalWait` so a 24 h human review doesn't blow the 2 h total budget.
     *  - Per-step timeouts (`withTimeout(stepTimeoutMs)` inside [executeStepOnce])
     *    still bound a single misbehaving step.
     */
    private class ExecutionDeadline(
        val startTime: Long,
        val totalTimeoutMs: Long
    ) {
        val approvalWaitMs: AtomicLong = AtomicLong(0L)
    }
    private val executionDeadlines = ConcurrentHashMap<String, ExecutionDeadline>()

    private interface UserProgressNotifier {
        fun onToolStarting(toolName: String, toolArgs: Any?)
        fun onToolFailed(toolName: String, error: String?)
    }

    private fun describeFailure(error: Throwable): String {
        return error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName ?: error::class.java.name
    }

    private fun logWorkflowFailure(workflow: WorkflowDefinition, durationMs: Long, error: Throwable) {
        val prefix = "[Workflow:${workflow.name}] Failed after ${durationMs / 1000.0}s"
        if (error is WorkflowExecutionException) {
            logger.warn { "$prefix: ${describeFailure(error)}" }
        } else {
            logger.error(error) { prefix }
        }
    }

    private fun logStepFailure(workflow: WorkflowDefinition, step: WorkflowStep, durationMs: Long, error: Throwable) {
        val prefix = "[Workflow:${workflow.name}] Step '${step.step}' failed after ${durationMs / 1000.0}s"
        if (error is WorkflowExecutionException) {
            logger.warn { "$prefix: ${describeFailure(error)}" }
        } else {
            logger.error(error) { prefix }
        }
    }

    /**
     * 执行工作流
     * @param workflow 工作流定义
     * @param initialInput 初始输入参数
     * @param externalExecutionId 外部传入的执行 ID，如果为 null 则自动生成
     */
    suspend fun execute(
        workflow: WorkflowDefinition,
        initialInput: Map<String, Any> = emptyMap(),
        externalExecutionId: String? = null,
        resumeState: WorkflowResumeState? = null
    ): WorkflowExecutionResult = withContext(SubWorkflowStackElement()) {
        coroutineScope scope@{
        val executionId = externalExecutionId ?: UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()
        debugController?.attachExecution(executionId, workflow.name)

        logger.info { "[Workflow:${workflow.name}] Starting execution (ID: $executionId)" }

        // 日志记录目录隔离配置
        val dirIsolation = workflow.directoryIsolation
        if (dirIsolation.enabled) {
            logger.info { "[Workflow:${workflow.name}] Directory isolation ENABLED: base_dir='${dirIsolation.baseDir}'" }
        } else {
            logger.info { "[Workflow:${workflow.name}] Directory isolation DISABLED (all agents share default directories)" }
        }

        // 开始监控
        if (enableMonitoring) {
            WorkflowMonitor.startExecution(executionId, workflow.name, workflow.workflow.size)
        }

        val context = WorkflowExecutionContext(
            workflowName = workflow.name,
            executionId = executionId
        )
        currentExecutionContext = context

        // 初始化上下文变量（YAML 默认值先加载，CLI/外部传入的变量后加载以覆盖默认值）
        context.variables.putAll(workflow.variables)
        context.variables.putAll(initialInput)

        // 将相对路径变量解析为绝对路径，避免 LLM 在工具调用中使用相对路径导致失败
        // （koog 框架的文件工具要求绝对路径）
        resolveRelativePathVariables(context.variables)

        // 初始化工作流级共享知识库
        initializeKnowledgeBase(workflow, context, executionId)
        hydrateResumeState(workflow, context, resumeState)

        try {
            val continueOnError = workflow.errorHandling?.continueOnError == true
            val debugEnabled = debugController?.getState()?.enabled == true
            val concurrencyEnabled = workflow.concurrency.enabled && !debugEnabled
            val transitionTriggers = buildIncomingTransitionTriggers(workflow)
            val workflowTotalTimeoutMs = parseConfiguredTimeoutMillis(
                workflow.timeout?.total,
                "workflow '${workflow.name}' total timeout"
            )

            if (workflow.concurrency.enabled && debugEnabled) {
                logger.warn {
                    "[Workflow:${workflow.name}] Debug mode enabled; workflow-level DAG concurrency is forced to sequential execution"
                }
            }

            suspend fun runWorkflowBody() {
                if (concurrencyEnabled) {
                    executeWorkflowConcurrently(
                        workflow = workflow,
                        context = context,
                        continueOnError = continueOnError,
                        executionId = executionId,
                        transitionTriggers = transitionTriggers
                    )
                } else {
                    executeWorkflowSequentially(
                        workflow = workflow,
                        context = context,
                        continueOnError = continueOnError,
                        executionId = executionId,
                        transitionTriggers = transitionTriggers
                    )
                }
            }

            if (workflowTotalTimeoutMs != null) {
                executionDeadlines[executionId] = ExecutionDeadline(startTime, workflowTotalTimeoutMs)
            }
            try {
                runWorkflowBody()
            } finally {
                executionDeadlines.remove(executionId)
            }

            val endTime = System.currentTimeMillis()

            // 即使 runWorkflowBody() 没有抛异常,也要从实际的 step 结果中推导整体是否成功。
            // 关键区分:
            //  - on_failure / errorHandling.onError 显式恢复 → 失败已被处理 → 整体仍可成功
            //  - 仅靠 continueOnError 救回的失败 → 用户没有为它声明恢复 → 整体应被标为失败
            // 这样 CLI exit code、monitoring overall flag、以及任何依赖 result.success 的下游
            // 消费者都不会误报(例如 web 层早期 bug:3/4 步骤 FAILED 却显示 100% COMPLETED)。
            val unrecoveredFailures = context.stepResults.values
                .filter { !it.success && it.stepName !in context.recoveredFailures }
            val derivedSuccess = unrecoveredFailures.isEmpty()
            val derivedError = if (!derivedSuccess) {
                val firstError = unrecoveredFailures
                    .mapNotNull { it.error?.takeIf { msg -> msg.isNotBlank() } }
                    .firstOrNull()
                    ?: "${unrecoveredFailures.size} step(s) failed"
                if (unrecoveredFailures.size > 1) {
                    "${unrecoveredFailures.size} steps failed; first error: $firstError"
                } else {
                    firstError
                }
            } else {
                null
            }

            if (derivedSuccess) {
                if (context.recoveredFailures.isNotEmpty()) {
                    logger.info {
                        "[Workflow:${workflow.name}] Completed in ${(endTime - startTime) / 1000.0}s " +
                            "(${context.recoveredFailures.size} step(s) failed but were recovered via on_failure)"
                    }
                } else {
                    logger.info { "[Workflow:${workflow.name}] Completed successfully in ${(endTime - startTime) / 1000.0}s" }
                }
            } else {
                logger.warn {
                    "[Workflow:${workflow.name}] Completed with ${unrecoveredFailures.size} unrecovered failed step(s) in " +
                        "${(endTime - startTime) / 1000.0}s (continueOnError was active)"
                }
            }

            // 完成监控 —— success 标志同样反映真实的整体结果
            if (enableMonitoring) {
                WorkflowMonitor.completeExecution(executionId, success = derivedSuccess)
            }

            WorkflowExecutionResult(
                workflowName = workflow.name,
                success = derivedSuccess,
                startTime = startTime,
                endTime = endTime,
                duration = endTime - startTime,
                error = derivedError,
                stepResults = context.stepResults,
                variables = context.variables,
                skippedSteps = context.skippedSteps.toSet(),
                recoveredFailures = context.recoveredFailures.toSet()
            )

        } catch (e: TimeoutCancellationException) {
            val endTime = System.currentTimeMillis()
            val configuredTimeout = workflow.timeout?.total ?: "configured timeout"
            logger.warn {
                "[Workflow:${workflow.name}] Timed out after ${(endTime - startTime) / 1000.0}s (limit=$configuredTimeout)"
            }

            if (enableMonitoring) {
                WorkflowMonitor.completeExecution(executionId, success = false)
            }

            WorkflowExecutionResult(
                workflowName = workflow.name,
                success = false,
                startTime = startTime,
                endTime = endTime,
                duration = endTime - startTime,
                error = "Workflow timed out after $configuredTimeout",
                stepResults = context.stepResults,
                variables = context.variables,
                skippedSteps = context.skippedSteps.toSet(),
                recoveredFailures = context.recoveredFailures.toSet()
            )

        } catch (e: CancellationException) {
            val endTime = System.currentTimeMillis()
            logger.info { "[Workflow:${workflow.name}] Cancelled after ${(endTime - startTime) / 1000.0}s" }

            if (enableMonitoring) {
                WorkflowMonitor.cancelExecution(executionId)
            }

            throw e
        } catch (e: Exception) {
            val endTime = System.currentTimeMillis()
            logWorkflowFailure(workflow, endTime - startTime, e)

            // 完成监控（失败）
            if (enableMonitoring) {
                WorkflowMonitor.completeExecution(executionId, success = false)
            }

            WorkflowExecutionResult(
                workflowName = workflow.name,
                success = false,
                startTime = startTime,
                endTime = endTime,
                duration = endTime - startTime,
                error = e.message ?: "Unknown error",
                stepResults = context.stepResults,
                variables = context.variables,
                skippedSteps = context.skippedSteps.toSet(),
                recoveredFailures = context.recoveredFailures.toSet()
            )
        }.also { result ->
            // 执行完成后的目录清理
            if (dirIsolation.enabled && dirIsolation.cleanupOnCompletion && result.success) {
                try {
                    val executionDir = File(dirIsolation.baseDir, executionId)
                    if (executionDir.exists()) {
                        executionDir.deleteRecursively()
                        logger.info { "[Workflow:${workflow.name}] Cleaned up isolation directory: ${executionDir.path}" }
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "[Workflow:${workflow.name}] Failed to cleanup isolation directory" }
                }
            }

            // 清理过期的旧执行目录
            if (dirIsolation.enabled && dirIsolation.cleanupAfterHours > 0) {
                try {
                    cleanupExpiredDirectories(dirIsolation)
                } catch (e: Exception) {
                    logger.warn(e) { "[Workflow:${workflow.name}] Failed to cleanup expired directories" }
                }
            }

            // 释放执行上下文引用，防止内存泄漏
            currentExecutionContext = null
        }
    } // coroutineScope
    } // withContext

    private suspend fun hydrateResumeState(
        workflow: WorkflowDefinition,
        context: WorkflowExecutionContext,
        resumeState: WorkflowResumeState?
    ) {
        if (resumeState == null) return
        if (
            resumeState.preservedStepResults.isEmpty() &&
            resumeState.skippedSteps.isEmpty() &&
            resumeState.activatedTransitionSteps.isEmpty() &&
            resumeState.transitionActivationSources.isEmpty()
        ) {
            return
        }

        context.skippedSteps.addAll(resumeState.skippedSteps)
        context.activatedTransitionSteps.addAll(resumeState.activatedTransitionSteps)
        resumeState.transitionActivationSources.forEach { (targetStep, sources) ->
            val activationSources = context.transitionActivationSources.computeIfAbsent(targetStep) {
                Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
            }
            activationSources.addAll(sources)
        }

        workflow.workflow.forEach { step ->
            val preservedResult = resumeState.preservedStepResults[step.step] ?: return@forEach
            context.stepResults[step.step] = preservedResult
            preservedResult.output?.let { output ->
                context.setStepOutput(step.step, output)
                restoreResumedStepVariables(step, output, context)
                autoIndexStepOutput(workflow, context, step.step, output)
                processExtract(step, output, context)
            }
        }

        // Apply the LATEST preserved step's variable snapshot as a savepoint.
        // Pre-fix, only variables that could be re-derived from output text
        // (`processExtract` / `restoreResumedStepVariables`) survived hydrate;
        // variables set directly via `context.setVariable(...)` — most
        // commonly sub_workflow `outputs:` mapping, but also manual_approval
        // `reviewable_actions` output paths, classifier `output_variable`,
        // state_machine internals — were lost, breaking downstream steps that
        // referenced them after a "restart from step N" click. The snapshot
        // is taken post-`processExtract`, so it includes everything those
        // recovery paths would have set anyway plus the previously-lost
        // direct-setVariable entries. Walking from the latest preserved step
        // backwards lets us pick up the first non-empty snapshot — earlier
        // steps' deltas are subsumed because each snapshot is the FULL
        // context.variables state at that moment, not a per-step delta.
        val latestPreservedSnapshot = workflow.workflow
            .asReversed()
            .asSequence()
            .mapNotNull { step -> resumeState.preservedStepResults[step.step] }
            .map { it.producedVariables }
            .firstOrNull { it.isNotEmpty() }
        latestPreservedSnapshot?.forEach { (key, value) ->
            context.setVariable(key, value)
        }

        // 重建 recoveredFailures:在 resume 之前的某次执行里,如果 step S 失败并触发了 on_failure
        // 转换,那么 S 会出现在 transitionActivationSources 的某个 value 集合中。
        // 此时 S 是 "被恢复的失败",不应该让整体 result.success 退化为 false。
        // 没有这一行,resume 之后 derivedSuccess 会把恢复过的失败再次算成失败。
        val recoveryActivationSources = resumeState.transitionActivationSources.values.flatten().toSet()
        recoveryActivationSources.forEach { sourceStepName ->
            val preserved = resumeState.preservedStepResults[sourceStepName]
            if (preserved != null && !preserved.success) {
                context.recoveredFailures.add(sourceStepName)
            }
        }

        logger.info {
            "[Workflow:${workflow.name}] Hydrated resume state: " +
                "${resumeState.preservedStepResults.size} preserved step(s), " +
                "${resumeState.skippedSteps.size} skipped step(s), " +
                "${resumeState.activatedTransitionSteps.size} activated transition target(s), " +
                "${context.recoveredFailures.size} recovered failure(s)"
        }
    }

    private fun restoreResumedStepVariables(
        step: WorkflowStep,
        output: String,
        context: WorkflowExecutionContext
    ) {
        step.aggregate?.let {
            processAggregate(step, context)
        }

        step.iterateOver?.resultsVariable?.takeIf { it.isNotBlank() }?.let { variableName ->
            context.setVariable(variableName, output)
        }

        step.classifier?.let { config ->
            val normalizedOutput = output.lineSequence()
                .firstOrNull { it.contains("classification:", ignoreCase = true) }
                ?.substringAfter(":")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: output.trim()

            val resolvedCategory = config.categories.firstOrNull { it.name.equals(normalizedOutput, ignoreCase = true) }?.name
                ?: config.categories.firstOrNull { normalizedOutput.contains(it.name, ignoreCase = true) }?.name
                ?: normalizedOutput

            if (resolvedCategory.isNotBlank()) {
                context.setVariable(config.outputVariable, resolvedCategory)
            }
        }

        if (step.isStateMachine) {
            context.setVariable("${step.step}_last_output", output)
            Regex("""final state '([^']+)'""")
                .find(output)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?.let { finalState ->
                    context.setVariable("${step.step}_current_state", finalState)
                    context.setVariable("${step.step}_final_state", finalState)
                }
        }
    }

    /**
     * 清理过期的隔离目录
     */
    private fun cleanupExpiredDirectories(config: DirectoryIsolationConfig) {
        val baseDir = File(config.baseDir)
        if (!baseDir.exists()) return

        val cutoffTime = System.currentTimeMillis() - config.cleanupAfterHours * 3600_000L
        var cleanedCount = 0

        baseDir.listFiles()?.filter { it.isDirectory && it.lastModified() < cutoffTime }?.forEach { dir ->
            try {
                dir.deleteRecursively()
                cleanedCount++
            } catch (e: Exception) {
                logger.warn { "[DirIsolation] Failed to cleanup expired directory: ${dir.path}" }
            }
        }

        if (cleanedCount > 0) {
            logger.info { "[DirIsolation] Cleaned up $cleanedCount expired execution directories" }
        }
    }

    private suspend fun executeWorkflowSequentially(
        workflow: WorkflowDefinition,
        context: WorkflowExecutionContext,
        continueOnError: Boolean,
        executionId: String,
        transitionTriggers: Map<String, List<IncomingTransitionTrigger>>
    ) {
        val pendingSteps = LinkedHashMap(workflow.workflow.associateBy { it.step })
        val stepOrder = workflow.workflow.withIndex().associate { (index, step) -> step.step to index }

        logger.info {
            "[Workflow:${workflow.name}] Sequential execution ENABLED (transition-aware): ${
                workflow.workflow.map { it.step }.joinToString(" → ")
            }"
        }

        while (pendingSteps.isNotEmpty()) {
            checkWorkflowDeadline(executionId, workflow)
            val scan = collectRunnableSteps(
                pendingSteps = pendingSteps,
                workflow = workflow,
                context = context,
                continueOnError = continueOnError,
                executionId = executionId,
                transitionTriggers = transitionTriggers
            )

            if (scan.runnableSteps.isEmpty()) {
                if (scan.progressMade) {
                    continue
                }
                throw WorkflowExecutionException(
                    "No runnable steps remaining. Pending steps: ${pendingSteps.keys.joinToString(", ")}"
                )
            }

            val nextStep = scan.runnableSteps
                .sortedWith(
                    compareByDescending<WorkflowStep> { it.priority }
                        .thenBy { stepOrder[it.step] ?: Int.MAX_VALUE }
                )
                .first()

            pendingSteps.remove(nextStep.step)
            val shouldStop = executeStepWithErrorHandling(nextStep, workflow, context, continueOnError, executionId)
            if (shouldStop) {
                return
            }
        }
    }

    private suspend fun executeWorkflowConcurrently(
        workflow: WorkflowDefinition,
        context: WorkflowExecutionContext,
        continueOnError: Boolean,
        executionId: String,
        transitionTriggers: Map<String, List<IncomingTransitionTrigger>>
    ) = coroutineScope {
        val pendingSteps = LinkedHashMap(workflow.workflow.associateBy { it.step })
        val stepOrder = workflow.workflow.withIndex().associate { (index, step) -> step.step to index }
        val maxConcurrency = workflow.concurrency.maxConcurrency.takeIf { it > 0 }
        val completionChannel = Channel<ConcurrentStepCompletion>(Channel.UNLIMITED)
        val runningSteps = linkedMapOf<String, Job>()

        logger.info {
            val maxConc = maxConcurrency?.toString() ?: "unlimited"
            "[Workflow:${workflow.name}] Concurrent execution ENABLED (transition-aware, max_concurrency=$maxConc)"
        }

        fun readyStepComparator(): Comparator<WorkflowStep> =
            compareByDescending<WorkflowStep> { it.priority }
                .thenBy { stepOrder[it.step] ?: Int.MAX_VALUE }

        fun availableSlots(): Int {
            return maxConcurrency?.let { maxOf(0, it - runningSteps.size) } ?: Int.MAX_VALUE
        }

        fun markRunningStepsCancelled(reason: String) {
            if (!enableMonitoring) return
            runningSteps.keys.forEach { stepName ->
                WorkflowMonitor.cancelStep(executionId, stepName, reason)
            }
        }

        fun launchStep(step: WorkflowStep) {
            pendingSteps.remove(step.step)

            // Each concurrent branch gets its own copy of the sub-workflow call stack
            // so that sibling concurrent steps don't interfere with each other's cycle detection.
            val parentStack = coroutineContext[SubWorkflowStackElement]?.stack?.toMutableList() ?: mutableListOf()
            val job = launch(SubWorkflowStackElement(parentStack.toMutableList())) {
                try {
                    val shouldStop = executeStepWithErrorHandling(step, workflow, context, continueOnError, executionId)
                    completionChannel.send(
                        ConcurrentStepCompletion(
                            stepName = step.step,
                            shouldStop = shouldStop
                        )
                    )
                } catch (error: Throwable) {
                    completionChannel.trySend(
                        ConcurrentStepCompletion(
                            stepName = step.step,
                            error = error
                        )
                    )
                    if (error is CancellationException) {
                        throw error
                    }
                }
            }
            runningSteps[step.step] = job
        }

        suspend fun handleCompletion(): Boolean {
            val completion = completionChannel.receive()
            runningSteps.remove(completion.stepName)
            completion.error?.let { error ->
                if (runningSteps.isNotEmpty()) {
                    markRunningStepsCancelled("Cancelled because concurrent step '${completion.stepName}' failed")
                    runningSteps.values.forEach { it.cancel(CancellationException("Concurrent step '${completion.stepName}' failed", error)) }
                }
                throw error
            }
            if (completion.shouldStop) {
                markRunningStepsCancelled("Cancelled because concurrent step '${completion.stepName}' stopped workflow")
                runningSteps.values.forEach { it.cancel() }
                runningSteps.values.joinAll()
                return true
            }
            return false
        }

        try {
            while (pendingSteps.isNotEmpty()) {
                checkWorkflowDeadline(executionId, workflow)
                val scan = collectRunnableSteps(
                    pendingSteps = pendingSteps,
                    workflow = workflow,
                    context = context,
                    continueOnError = continueOnError,
                    executionId = executionId,
                    transitionTriggers = transitionTriggers
                )

                val selectedSteps = if (availableSlots() > 0) {
                    scan.runnableSteps
                        .sortedWith(readyStepComparator())
                        .take(availableSlots())
                } else {
                    emptyList()
                }

                if (selectedSteps.isNotEmpty()) {
                    logger.info {
                        "[Workflow:${workflow.name}] Launching ${selectedSteps.size} ready step(s): ${
                            selectedSteps.map { it.step }
                        }"
                    }
                    selectedSteps.forEach(::launchStep)
                    continue
                }

                if (scan.runnableSteps.isEmpty() && scan.progressMade) {
                    continue
                }

                if (runningSteps.isEmpty()) {
                    throw WorkflowExecutionException(
                        "No runnable steps remaining. Pending steps: ${pendingSteps.keys.joinToString(", ")}"
                    )
                }

                if (handleCompletion()) {
                    return@coroutineScope
                }
            }

            while (runningSteps.isNotEmpty()) {
                checkWorkflowDeadline(executionId, workflow)
                if (handleCompletion()) {
                    return@coroutineScope
                }
            }
        } finally {
            completionChannel.close()
            markRunningStepsCancelled("Cancelled because concurrent workflow branch is closing")
            runningSteps.values.forEach { job ->
                if (job.isActive) {
                    job.cancel()
                }
            }
        }
    }

    private fun collectRunnableSteps(
        pendingSteps: MutableMap<String, WorkflowStep>,
        workflow: WorkflowDefinition,
        context: WorkflowExecutionContext,
        continueOnError: Boolean,
        executionId: String,
        transitionTriggers: Map<String, List<IncomingTransitionTrigger>>
    ): RunnableStepScan {
        var progressMade = false
        val runnableSteps = mutableListOf<WorkflowStep>()
        val stepsToRemove = mutableListOf<String>()

        pendingSteps.values.toList().forEach { step ->
            if (isStepResolved(step.step, context)) {
                stepsToRemove += step.step
                progressMade = true
                return@forEach
            }

            val transitionControlled = transitionTriggers.containsKey(step.step)
            if (transitionControlled && !isTransitionStepActivated(step.step, context)) {
                if (shouldSkipInactiveTransitionStep(step.step, transitionTriggers, context)) {
                    markStepSkipped(workflow, context, executionId, step.step, "transition not activated")
                    stepsToRemove += step.step
                    progressMade = true
                }
                return@forEach
            }

            when (evaluateDependencies(step, context)) {
                DependencyStatus.FAILED -> {
                    if (continueOnError) {
                        markStepSkipped(workflow, context, executionId, step.step, "dependencies failed")
                        stepsToRemove += step.step
                        progressMade = true
                    } else {
                        throw WorkflowExecutionException("Dependencies failed for step '${step.step}'")
                    }
                }

                DependencyStatus.PENDING -> return@forEach
                DependencyStatus.SATISFIED -> Unit
            }

            if (!evaluateCondition(step.condition, context)) {
                markStepSkipped(workflow, context, executionId, step.step, "condition not met")
                stepsToRemove += step.step
                progressMade = true
                return@forEach
            }

            runnableSteps += step
        }

        stepsToRemove.forEach { pendingSteps.remove(it) }
        return RunnableStepScan(
            runnableSteps = runnableSteps,
            progressMade = progressMade
        )
    }

    private fun parseConfiguredTimeoutMillis(raw: String?, label: String): Long? {
        val normalized = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            (normalized.toLongOrNull()?.seconds ?: Duration.parse(normalized)).inWholeMilliseconds
        } catch (e: IllegalArgumentException) {
            throw WorkflowExecutionException(
                "Invalid $label: '$raw'. Use values like '30s', '5m', '1h', or a plain seconds number.",
                e
            )
        }
    }

    /**
     * Throws [WorkflowExecutionException] when the workflow has been running longer
     * than `workflow.timeout.total` (excluding wall-clock time already accumulated in
     * manual_approval waits). Called at every step iteration boundary by the
     * sequential and concurrent runners. No-op when no total timeout was configured
     * for this execution.
     *
     * The "approval wait excluded" carve-out is intentional: a workflow whose only
     * pending operation is a human review should not be killed by a budget meant to
     * catch runaway active execution. The approval has its own
     * [ManualApprovalConfig.timeout] (default 24 h) which is the appropriate bound
     * for that wait.
     */
    private fun checkWorkflowDeadline(executionId: String, workflow: WorkflowDefinition) {
        val deadline = executionDeadlines[executionId] ?: return
        val approvalWait = deadline.approvalWaitMs.get()
        val effectiveElapsed = System.currentTimeMillis() - deadline.startTime - approvalWait
        if (effectiveElapsed > deadline.totalTimeoutMs) {
            val configured = workflow.timeout?.total ?: "${deadline.totalTimeoutMs}ms"
            val approvalNote = if (approvalWait > 0L) {
                " (excluding ${approvalWait / 1000}s spent in manual approvals)"
            } else ""
            throw WorkflowExecutionException(
                "Workflow timed out after $configured$approvalNote"
            )
        }
    }

    private fun resolveStepTimeoutMillis(step: WorkflowStep, workflow: WorkflowDefinition): Long? {
        step.timeoutSeconds?.let { seconds ->
            return seconds.toLong() * 1000L
        }
        step.timeout?.let { timeout ->
            return parseConfiguredTimeoutMillis(timeout, "step '${step.step}' timeout")
        }
        return parseConfiguredTimeoutMillis(
            workflow.timeout?.perStep,
            "workflow '${workflow.name}' per_step timeout"
        )
    }

    private fun buildStepTimeoutMessage(step: WorkflowStep, workflow: WorkflowDefinition): String {
        return when {
            step.timeoutSeconds != null ->
                "Step '${step.step}' timed out after ${step.timeoutSeconds}s"
            step.timeout != null ->
                "Step '${step.step}' timed out after ${step.timeout}"
            workflow.timeout?.perStep != null ->
                "Step '${step.step}' timed out after workflow default ${workflow.timeout.perStep}"
            else ->
                "Step '${step.step}' timed out"
        }
    }

    private fun buildIncomingTransitionTriggers(
        workflow: WorkflowDefinition
    ): Map<String, List<IncomingTransitionTrigger>> {
        val incoming = linkedMapOf<String, MutableList<IncomingTransitionTrigger>>()

        workflow.workflow.forEach { step ->
            step.onSuccess.forEach { action ->
                action.next?.let { target ->
                    incoming.getOrPut(target) { mutableListOf() }
                        .add(IncomingTransitionTrigger(step.step, TransitionOutcome.SUCCESS))
                }
                action.parallel.orEmpty().forEach { target ->
                    incoming.getOrPut(target) { mutableListOf() }
                        .add(IncomingTransitionTrigger(step.step, TransitionOutcome.SUCCESS))
                }
            }

            step.onFailure.forEach { action ->
                action.next?.let { target ->
                    incoming.getOrPut(target) { mutableListOf() }
                        .add(IncomingTransitionTrigger(step.step, TransitionOutcome.FAILURE))
                }
                action.parallel.orEmpty().forEach { target ->
                    incoming.getOrPut(target) { mutableListOf() }
                        .add(IncomingTransitionTrigger(step.step, TransitionOutcome.FAILURE))
                }
            }
        }

        val workflowOnErrorTargets = workflow.errorHandling?.onError.orEmpty()
            .flatMap { action ->
                listOfNotNull(action.next) + action.parallel.orEmpty()
            }
            .toSet()
        val workflowOnErrorSources = workflow.workflow
            .map { it.step }
            .filterNot { it in workflowOnErrorTargets }

        workflow.errorHandling?.onError.orEmpty().forEach { action ->
            action.next?.let { target ->
                workflowOnErrorSources.forEach { source ->
                    incoming.getOrPut(target) { mutableListOf() }
                        .add(IncomingTransitionTrigger(source, TransitionOutcome.FAILURE))
                }
            }
            action.parallel.orEmpty().forEach { target ->
                workflowOnErrorSources.forEach { source ->
                    incoming.getOrPut(target) { mutableListOf() }
                        .add(IncomingTransitionTrigger(source, TransitionOutcome.FAILURE))
                }
            }
        }

        return incoming
    }

    private fun isStepResolved(stepName: String, context: WorkflowExecutionContext): Boolean {
        return context.stepResults.containsKey(stepName) || context.skippedSteps.contains(stepName)
    }

    private fun isTransitionStepActivated(stepName: String, context: WorkflowExecutionContext): Boolean {
        return context.activatedTransitionSteps.contains(stepName)
    }

    private fun shouldSkipInactiveTransitionStep(
        stepName: String,
        transitionTriggers: Map<String, List<IncomingTransitionTrigger>>,
        context: WorkflowExecutionContext
    ): Boolean {
        val triggers = transitionTriggers[stepName].orEmpty()
        if (triggers.isEmpty() || isTransitionStepActivated(stepName, context)) {
            return false
        }
        return triggers.all { trigger -> isStepResolved(trigger.sourceStep, context) }
    }

    private fun evaluateDependencies(
        step: WorkflowStep,
        context: WorkflowExecutionContext
    ): DependencyStatus {
        var hasPending = false
        val activationSources = context.transitionActivationSources[step.step].orEmpty()

        step.dependsOn.forEach { dependency ->
            val result = context.stepResults[dependency]
            when {
                result?.success == true -> Unit
                dependency in activationSources -> Unit
                dependency in context.skippedSteps -> Unit
                result != null -> return DependencyStatus.FAILED
                else -> hasPending = true
            }
        }

        return if (hasPending) DependencyStatus.PENDING else DependencyStatus.SATISFIED
    }

    private fun markStepSkipped(
        workflow: WorkflowDefinition,
        context: WorkflowExecutionContext,
        executionId: String,
        stepName: String,
        reason: String
    ) {
        logger.info { "[Workflow:${workflow.name}] Step '$stepName' skipped ($reason)" }
        context.skippedSteps.add(stepName)
        if (enableMonitoring) {
            WorkflowMonitor.skipStep(executionId, stepName)
        }
    }

    /**
     * 执行单个步骤
     */
    private suspend fun executeStep(
        step: WorkflowStep,
        workflow: WorkflowDefinition,
        context: WorkflowExecutionContext
    ): StepExecutionResult {
        // 如果有 aggregate 配置，先执行聚合
        if (step.aggregate != null) {
            processAggregate(step, context)
        }

        // 如果有 iterate_over 配置，委托给 executeWithIteration
        if (step.iterateOver != null) {
            return executeWithIteration(step, workflow, context)
        }

        // 如果有 repeat_until 配置，委托给 executeWithRepeatUntil
        if (step.repeatUntil != null) {
            return executeWithRepeatUntil(step, workflow, context)
        }
        // 如果有重试配置，委托给 executeWithRetry
        if (step.retry != null) {
            return executeWithRetry(step, workflow, context)
        }
        return executeStepOnce(step, workflow, context)
    }

    /**
     * 循环执行步骤直到条件满足（repeat_until）
     *
     * 每次迭代：
     * 1. 执行步骤本身（通过 executeStepOnce 或 executeWithRetry）
     * 2. 如果配置了 evaluate_agent，运行评估 Agent
     * 3. 如果配置了 extract_pattern，从评估结果中提取变量
     * 4. 检查终止条件
     * 5. 如条件未满足且未达到 max_iterations，重复
     */
    private suspend fun executeWithRepeatUntil(
        step: WorkflowStep,
        workflow: WorkflowDefinition,
        context: WorkflowExecutionContext,
        manageMonitoring: Boolean = true
    ): StepExecutionResult {
        val config = step.repeatUntil
            ?: throw WorkflowExecutionException("repeatUntil configuration missing for step '${step.step}'")
        if (config.maxIterations < 1) {
            throw WorkflowExecutionException(
                "repeat_until for step '${step.step}' requires max_iterations >= 1 (got ${config.maxIterations})"
            )
        }
        var lastResult: StepExecutionResult? = null
        val monitoringEnabled = enableMonitoring && manageMonitoring
        var monitoringCompleted = false

        logger.info { "[Workflow:${workflow.name}] Starting repeat_until loop for step '${step.step}' (max_iterations=${config.maxIterations})" }

        if (monitoringEnabled) {
            WorkflowMonitor.startStep(context.executionId, step.step, agentName = step.displayAgentName)
            WorkflowMonitor.addEvent(
                context.executionId, step.step, AgentEvent(
                    type = "repeat_until_started",
                    category = "agent",
                    summary = "🔁 repeat_until 开始: 最多 ${config.maxIterations} 轮"
                )
            )
        }

        for (iteration in 1..config.maxIterations) {
            // 每轮迭代边界检查 workflow 总超时预算(与 step 边界的检查一致)。
            checkWorkflowDeadline(context.executionId, workflow)
            logger.info { "[Workflow:${workflow.name}] repeat_until iteration $iteration/${config.maxIterations} for step '${step.step}'" }

            // 记录监控事件
            if (monitoringEnabled) {
                WorkflowMonitor.addEvent(
                    context.executionId, step.step, AgentEvent(
                        type = "repeat_until_iteration",
                        category = "agent",
                        summary = "Iteration $iteration/${config.maxIterations}"
                    )
                )
            }

            // 执行步骤（支持 retry 嵌套）
            try {
                lastResult = if (step.retry != null) {
                    executeWithRetry(step, workflow, context, manageMonitoring = false)
                } else {
                    executeStepOnce(step, workflow, context, manageMonitoring = false)
                }
            } catch (e: TimeoutCancellationException) {
                if (monitoringEnabled) {
                    WorkflowMonitor.completeStep(
                        context.executionId,
                        step.step,
                        success = false,
                        error = buildStepTimeoutMessage(step, workflow)
                    )
                    monitoringCompleted = true
                }
                throw e
            } catch (e: Exception) {
                if (monitoringEnabled) {
                    WorkflowMonitor.completeStep(
                        context.executionId,
                        step.step,
                        success = false,
                        error = e.message
                    )
                    monitoringCompleted = true
                }
                throw e
            }

            // 如果步骤失败，终止循环
            if (!lastResult.success) {
                logger.warn { "[Workflow:${workflow.name}] repeat_until step '${step.step}' failed at iteration $iteration, stopping loop" }
                if (monitoringEnabled) {
                    WorkflowMonitor.completeStep(
                        context.executionId,
                        step.step,
                        success = false,
                        error = lastResult.error,
                        output = lastResult.output
                    )
                    monitoringCompleted = true
                }
                break
            }

            // 运行评估 Agent（如果配置了）
            if (config.evaluateAgent != null) {
                val evalResult = runEvaluateAgent(step, config, workflow, context, iteration)
                // 从评估结果中提取变量
                if (config.extractPattern != null && config.extractVariable != null && evalResult != null) {
                    extractAndSetVariable(config.extractPattern, config.extractVariable, evalResult, context)
                }
            }

            // 检查终止条件
            if (evaluateCondition(config.condition, context)) {
                logger.info { "[Workflow:${workflow.name}] repeat_until condition met for step '${step.step}' at iteration $iteration" }
                break
            }

            if (iteration == config.maxIterations) {
                logger.warn { "[Workflow:${workflow.name}] repeat_until reached max_iterations (${config.maxIterations}) for step '${step.step}'" }
            }
        }

        val finalResult = lastResult ?: throw WorkflowExecutionException(
            "repeat_until for step '${step.step}' produced no result"
        )
        if (monitoringEnabled && !monitoringCompleted) {
            WorkflowMonitor.completeStep(
                context.executionId,
                step.step,
                success = finalResult.success,
                error = finalResult.error,
                output = finalResult.output
            )
        }
        return finalResult
    }

    /**
     * 运行评估 Agent
     */
    private suspend fun runEvaluateAgent(
        step: WorkflowStep,
        config: RepeatUntilConfig,
        workflow: WorkflowDefinition,
        context: WorkflowExecutionContext,
        iteration: Int
    ): String? {
        val evalAgentName = config.evaluateAgent ?: return null
        val evalAgentDef = workflow.agents[evalAgentName]
            ?: throw WorkflowExecutionException("Evaluate agent '$evalAgentName' not found for step '${step.step}'")

        val promptTemplate = config.evaluatePrompt
            ?: "Evaluate the output of step '${step.step}' (iteration $iteration). Output: {{steps.${step.step}.output}}"
        val resolvedPrompt = resolveTemplate(promptTemplate, context)

        logger.info { "[Workflow:${workflow.name}] Running evaluate agent '$evalAgentName' for step '${step.step}' iteration $iteration" }

        return try {
            val evalOutput = runStepAgent(
                agentName = evalAgentName,
                agentDef = evalAgentDef,
                context = context,
                stepName = "${step.step}:evaluate:$iteration",
                input = resolvedPrompt,
                directoryIsolation = workflow.directoryIsolation
            )

            // 存储评估输出到上下文
            context.setStepOutput("${step.step}:evaluate", evalOutput)

            if (enableMonitoring) {
                WorkflowMonitor.addEvent(
                    context.executionId, step.step, AgentEvent(
                        type = "repeat_until_evaluate",
                        category = "agent",
                        summary = "Evaluate agent '$evalAgentName' completed (iteration $iteration)",
                        detail = evalOutput.take(500)
                    )
                )
            }
            evalOutput
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "[Workflow:${workflow.name}] Evaluate agent '$evalAgentName' failed for step '${step.step}': ${e.message}" }
            null
        }
    }

    /**
     * 从文本中提取变量并存入上下文
     */
    private fun extractAndSetVariable(
        pattern: String,
        variableName: String,
        text: String,
        context: WorkflowExecutionContext
    ) {
        try {
            val regex = Regex(pattern)
            val match = regex.find(text)
            if (match != null) {
                val value = if (match.groupValues.size > 1) match.groupValues[1] else match.value
                context.setVariable(variableName, value)
                logger.info { "Extracted variable '$variableName' = '$value' from evaluation output" }
            } else {
                logger.warn { "Pattern '$pattern' did not match evaluation output for variable '$variableName'" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to extract variable '$variableName' with pattern '$pattern': ${e.message}" }
        }
    }

    /**
     * 执行单个步骤一次（不含重试逻辑）
     */
    private suspend fun executeStepOnce(
        step: WorkflowStep,
        workflow: WorkflowDefinition,
        context: WorkflowExecutionContext,
        manageMonitoring: Boolean = true
    ): StepExecutionResult {
        logger.info { "[Workflow:${workflow.name}] Executing step '${step.step}' with agent '${step.displayAgentName}'" }
        val monitoringEnabled = enableMonitoring && manageMonitoring

        // 开始监控步骤
        if (monitoringEnabled) {
            WorkflowMonitor.startStep(context.executionId, step.step, agentName = step.displayAgentName)
        }

        awaitDebugCheckpoint(
            point = WorkflowDebugPoint.BEFORE_STEP,
            stepName = step.step,
            context = context,
            label = step.displayAgentName
        )

        val startTime = System.currentTimeMillis()
        val dirIsolation = workflow.directoryIsolation
        val stepTimeoutMs = resolveStepTimeoutMillis(step, workflow)

        return try {
            // 检查手动审批。必须放在统一 try/catch 内，确保拒绝/超时也会
            // 完成步骤监控并写入可被 on_failure 恢复的 StepExecutionResult。
            if (step.manualApproval != null) {
                handleManualApproval(step, context)
            }

            // 支持步骤超时
            val result = if (stepTimeoutMs != null) {
                withTimeout(stepTimeoutMs.milliseconds) {
                    executeStepByType(step, workflow, context, dirIsolation)
                }
            } else {
                executeStepByType(step, workflow, context, dirIsolation)
            }

            val endTime = System.currentTimeMillis()
            val duration = endTime - startTime

            val stepResult = StepExecutionResult(
                stepName = step.step,
                agentName = step.displayAgentName,
                success = result.success,
                startTime = startTime,
                endTime = endTime,
                duration = duration,
                output = result.output,
                error = result.error
            )

            context.stepResults[step.step] = stepResult
            if (result.output != null) {
                context.setStepOutput(step.step, result.output)
                // 自动索引步骤输出到共享知识库
                autoIndexStepOutput(workflow, context, step.step, result.output)
                // 通用结构化输出提取
                processExtract(step, result.output, context)
            }

            // Snapshot `context.variables` AFTER processExtract so the persisted
            // step result acts as a savepoint for "restart from step N". Without
            // this, variables set via `setVariable(...)` rather than via output-
            // text `extract:` (sub_workflow `outputs:`, manual_approval
            // `reviewable_actions`, classifier `output_variable`, etc.) are lost
            // on restart and downstream steps that reference them break.
            val resultWithSnapshot = stepResult.copy(
                producedVariables = context.snapshotVariablesForPersistence()
            )
            context.stepResults[step.step] = resultWithSnapshot

            logger.info { "[Workflow:${workflow.name}] Step '${step.step}' completed in ${duration / 1000.0}s" }

            // 完成步骤监控
            if (monitoringEnabled) {
                WorkflowMonitor.completeStep(
                    context.executionId,
                    step.step,
                    success = result.success,
                    output = result.output
                )
            }

            awaitDebugCheckpoint(
                point = WorkflowDebugPoint.AFTER_STEP,
                stepName = step.step,
                context = context,
                label = step.displayAgentName
            )

            resultWithSnapshot

        } catch (e: TimeoutCancellationException) {
            val endTime = System.currentTimeMillis()
            val duration = endTime - startTime
            val timeoutMessage = buildStepTimeoutMessage(step, workflow)

            logger.warn {
                "[Workflow:${workflow.name}] Step '${step.step}' timed out after ${duration / 1000.0}s: $timeoutMessage"
            }

            if (monitoringEnabled) {
                WorkflowMonitor.completeStep(
                    context.executionId,
                    step.step,
                    success = false,
                    error = timeoutMessage,
                    output = null
                )
            }

            val stepResult = StepExecutionResult(
                stepName = step.step,
                agentName = step.displayAgentName,
                success = false,
                startTime = startTime,
                endTime = endTime,
                duration = duration,
                error = timeoutMessage
            )

            context.stepResults[step.step] = stepResult
            awaitDebugCheckpoint(
                point = WorkflowDebugPoint.STEP_ERROR,
                stepName = step.step,
                context = context,
                label = timeoutMessage
            )
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val endTime = System.currentTimeMillis()
            val duration = endTime - startTime

            logStepFailure(workflow, step, duration, e)

            // 记录监控失败
            if (monitoringEnabled) {
                WorkflowMonitor.completeStep(
                    context.executionId,
                    step.step,
                    success = false,
                    error = e.message,
                    output = null
                )
            }

            val stepResult = StepExecutionResult(
                stepName = step.step,
                agentName = step.displayAgentName,
                success = false,
                startTime = startTime,
                endTime = endTime,
                duration = duration,
                error = e.message,
                producedVariables = context.snapshotVariablesForPersistence()
            )

            context.stepResults[step.step] = stepResult
            awaitDebugCheckpoint(
                point = WorkflowDebugPoint.STEP_ERROR,
                stepName = step.step,
                context = context,
                label = e.message
            )
            throw WorkflowExecutionException("Step '${step.step}' failed: ${e.message}", e)
        }
    }

    /**
     * 根据步骤类型路由执行
     */
    private suspend fun executeStepByType(
        step: WorkflowStep,
        workflow: WorkflowDefinition,
        context: WorkflowExecutionContext,
        dirIsolation: DirectoryIsolationConfig
    ): StepResult {
        return when {
            step.isSubWorkflow -> executeSubWorkflowStep(step, workflow, context, dirIsolation)
            step.isStateMachine -> executeStateMachineStep(step, workflow, context, dirIsolation)
            step.isCode -> executeCodeStep(step, workflow, context, dirIsolation)
            step.isClassifier -> executeClassifierStep(step, workflow, context, dirIsolation)
            step.isAgentBased -> executeAgentBasedStep(step, workflow, context, dirIsolation)
            step.isGroupChat -> executeGroupChatStep(step, workflow, context, dirIsolation)
            step.parallel != null -> {
                val agentName = step.agent
                    ?: throw WorkflowExecutionException("Agent name is required for parallel step '${step.step}'")
                val agentDef = workflow.agents[agentName]
                    ?: throw WorkflowExecutionException("Agent '$agentName' not found")
                executeParallelStep(step, agentDef, context, dirIsolation)
            }

            else -> {
                val agentName = step.agent
                    ?: throw WorkflowExecutionException("Agent name is required for step '${step.step}'")
                val agentDef = workflow.agents[agentName]
                    ?: throw WorkflowExecutionException("Agent '$agentName' not found")
                executeSingleStep(step, agentDef, context, dirIsolation)
            }
        }
    }

    /**
     * 执行单个任务
     */
    private suspend fun executeSingleStep(
        step: WorkflowStep,
        agentDef: AgentDefinition,
        context: WorkflowExecutionContext,
        directoryIsolation: DirectoryIsolationConfig = DirectoryIsolationConfig()
    ): StepResult {
        val stepStartedAt = System.currentTimeMillis()
        val templateContext = createStepTemplateContext(context, step.step, step.agent, directoryIsolation)
        val workingDir = templateContext.variables["working_dir"] as? String

        // 解析输入模板
        val resolvedInput = resolveTemplate(
            step.input ?: throw WorkflowExecutionException("Input is required for step '${step.step}'"),
            templateContext
        )
        val resolvedStructuredOutput = step.structuredOutput?.copy(
            writeTo = step.structuredOutput.writeTo?.let { resolveTemplate(it, templateContext) }
        )
        // 只从原始模板提取显式落盘目标，避免把上游输出里嵌入的 YAML/评审文本误当成当前步骤义务。
        // structured_output.write_to 由 executor 负责；不要把 schema 文件名、禁止写文件提示中的路径误当成 prompt 落盘义务。
        val promptPersistedFiles = if (resolvedStructuredOutput == null) {
            resolvePersistedFilePathCandidates(
                candidates = extractRequiredPersistedFilePathCandidatesFromPrompt(step.input),
                baseDir = workingDir
            ) { candidate ->
                resolveTemplate(candidate, templateContext)
            }
        } else {
            emptySet()
        }
        val structuredOutputPersistedFiles = externalStructuredOutputPersistedFiles(
            structuredOutput = resolvedStructuredOutput,
            isExternalAgent = agentDef.isClaudeCodeAgent() || agentDef.isCodexAgent()
        )
        val expectedPersistedFiles = (promptPersistedFiles + structuredOutputPersistedFiles).toSortedSet()
        val effectiveInput = augmentPromptWithPersistenceRequirements(resolvedInput, promptPersistedFiles)

        val stepAgentName = step.agent
            ?: throw WorkflowExecutionException("Agent name is required for step '${step.step}'")
        val output = if (resolvedStructuredOutput != null) {
            runStepAgentWithStructuredOutput(
                agentName = stepAgentName,
                agentDef = agentDef,
                context = context,
                stepName = step.step,
                input = effectiveInput,
                structuredOutput = resolvedStructuredOutput,
                directoryIsolation = directoryIsolation
            )
        } else {
            runStepAgent(
                agentName = stepAgentName,
                agentDef = agentDef,
                context = context,
                stepName = step.step,
                input = effectiveInput,
                directoryIsolation = directoryIsolation
            )
        }

        validatePersistedOutputs(
            step = step,
            executionId = context.executionId,
            output = output,
            stepStartedAt = stepStartedAt,
            expectedPersistedFiles = expectedPersistedFiles,
            workingDir = workingDir
        )

        return StepResult(success = true, output = output)
    }

    /**
     * 并行执行多个任务
     */
    private suspend fun executeParallelStep(
        step: WorkflowStep,
        agentDef: AgentDefinition,
        context: WorkflowExecutionContext,
        directoryIsolation: DirectoryIsolationConfig = DirectoryIsolationConfig()
    ): StepResult = coroutineScope {
        val parallel = step.parallel
            ?: throw WorkflowExecutionException("Parallel configuration missing for step '${step.step}'")

        // 限制并行度
        val maxParallel = parallel.maxParallel ?: parallel.tasks.size
        val semaphore = Semaphore(maxParallel)

        // 每个并行任务都需要独立的 Agent 实例（StatefulSingleUseAIAgent 只能 run 一次）
        val results = parallel.tasks.mapIndexed { index, taskInput ->
            async {
                semaphore.withPermit {
                    val resolvedInput = resolveTemplate(
                        taskInput,
                        createStepTemplateContext(
                            context,
                            "${step.step}:parallel:$index",
                            step.agent,
                            directoryIsolation
                        )
                    )
                    runStepAgent(
                        agentName = step.agent
                            ?: throw WorkflowExecutionException("Agent name is required for parallel step '${step.step}'"),
                        agentDef = agentDef,
                        context = context,
                        stepName = "${step.step}:parallel:$index",
                        input = resolvedInput,
                        directoryIsolation = directoryIsolation
                    )
                }
            }
        }.awaitAll()

        val aggregatedOutput = if (parallel.aggregateResults) {
            results.joinToString("\n---\n")
        } else {
            results.lastOrNull() ?: ""
        }

        StepResult(success = true, output = aggregatedOutput)
    }

    /**
     * 执行状态机复合节点。
     *
     * 外层仍然是 DAG 的一个步骤；内部通过状态机在多个子状态之间迁移。
     * 每个状态可以选择执行一个受限版步骤，执行成功后触发 success_event，
     * 执行失败后触发 failure_event；没有步骤的控制状态会自动触发 auto_event。
     */
    private suspend fun executeStateMachineStep(
        step: WorkflowStep,
        workflow: WorkflowDefinition,
        context: WorkflowExecutionContext,
        directoryIsolation: DirectoryIsolationConfig = DirectoryIsolationConfig()
    ): StepResult {
        val config = step.stateMachine
            ?: throw WorkflowExecutionException("stateMachine configuration missing for step '${step.step}'")
        val engine = StateMachineEngine(config, context)
        engine.initialize()

        if (enableMonitoring) {
            WorkflowMonitor.addEvent(
                context.executionId, step.step, AgentEvent(
                    type = "state_machine_started",
                    category = "agent",
                    subCategory = "state_machine",
                    summary = "🔀 状态机开始: ${config.initialState}",
                    detail = "states=${config.states.size}, final_states=${config.finalStates.joinToString(",")}"
                )
            )
        }

        var lastOutput: String? = null
        var transitionAttempts = 0

        while (true) {
            val currentState = engine.getCurrentState()
            val stateDef = config.states[currentState]
                ?: throw WorkflowExecutionException("State machine step '${step.step}' references missing state '$currentState'")
            val isFinalState = engine.isFinalState()

            context.setVariable("${step.step}_current_state", currentState)
            context.setVariable("${step.step}_history_size", engine.getHistory().size)

            if (!isFinalState && transitionAttempts >= config.maxTransitions) {
                throw WorkflowExecutionException(
                    "State machine step '${step.step}' exceeded max_transitions=${config.maxTransitions}"
                )
            }

            awaitDebugCheckpoint(
                point = WorkflowDebugPoint.STATE_MACHINE_STATE,
                stepName = step.step,
                context = context,
                label = currentState
            )

            val stateExecution = executeStateMachineState(
                outerStep = step,
                stateName = currentState,
                stateDef = stateDef,
                workflow = workflow,
                context = context,
                directoryIsolation = directoryIsolation
            )

            if (stateExecution.output != null) {
                lastOutput = stateExecution.output
                context.setVariable("${step.step}_last_output", stateExecution.output)
                context.setVariable(
                    "${step.step}_${toSafeStateKey(currentState)}_output",
                    stateExecution.output
                )
            }
            stateExecution.error?.let { context.setVariable("${step.step}_last_error", it) }

            if (isFinalState) {
                if (!stateExecution.success) {
                    val suffix = stateExecution.error?.let { ": $it" } ?: ""
                    throw WorkflowExecutionException(
                        "State machine step '${step.step}' failed in final state '$currentState'$suffix"
                    )
                }
                break
            }

            val transitionResult = engine.triggerEvent(stateExecution.event)
            transitionAttempts++

            if (!transitionResult.success) {
                val suffix = stateExecution.error?.let { " (state error: $it)" } ?: ""
                throw WorkflowExecutionException(
                    "State machine step '${step.step}' failed in state '$currentState': ${transitionResult.message}$suffix"
                )
            }

            if (enableMonitoring) {
                WorkflowMonitor.addEvent(
                    context.executionId, step.step, AgentEvent(
                        type = "state_machine_transition",
                        category = "agent",
                        subCategory = "state_machine",
                        summary = "🔀 ${transitionResult.fromState} -> ${transitionResult.toState}",
                        detail = "event=${transitionResult.event}"
                    )
                )
            }
        }

        val finalState = engine.getCurrentState()
        context.setVariable("${step.step}_final_state", finalState)
        context.setVariable("${step.step}_transition_count", engine.getHistory().size)
        context.setVariable(
            "${step.step}_history",
            engine.getHistory().joinToString(" | ") { "${it.fromState} --${it.event}--> ${it.toState}" }
        )

        if (enableMonitoring) {
            WorkflowMonitor.addEvent(
                context.executionId, step.step, AgentEvent(
                    type = "state_machine_completed",
                    category = "agent",
                    subCategory = "state_machine",
                    summary = "✅ 状态机完成: $finalState",
                    detail = "transitions=${engine.getHistory().size}"
                )
            )
        }

        val finalOutput = lastOutput ?: "State machine '${step.step}' completed in final state '$finalState'"
        return StepResult(success = true, output = finalOutput)
    }

    private suspend fun executeStateMachineState(
        outerStep: WorkflowStep,
        stateName: String,
        stateDef: StateDefinition,
        workflow: WorkflowDefinition,
        context: WorkflowExecutionContext,
        directoryIsolation: DirectoryIsolationConfig
    ): StateExecutionResult {
        val stateStepConfig = stateDef.stepConfig ?: run {
            if (enableMonitoring) {
                WorkflowMonitor.addEvent(
                    context.executionId, outerStep.step, AgentEvent(
                        type = "state_machine_state_entered",
                        category = "agent",
                        subCategory = "state_machine",
                        summary = "📍 控制状态: $stateName",
                        detail = "event=${stateDef.autoEvent}"
                    )
                )
            }
            return StateExecutionResult(success = true, event = stateDef.autoEvent)
        }

        val stateStep = createStateMachineStateStep(outerStep, stateName, stateStepConfig)
        val subStepName = stateStep.step  // e.g. "conversation_session.handle_turn"

        if (enableMonitoring) {
            // Register sub-step in WorkflowMonitor so agent events (LLM calls, tool calls) have a valid target
            WorkflowMonitor.startStep(context.executionId, subStepName, stateStep.agent)
            WorkflowMonitor.addEvent(
                context.executionId, outerStep.step, AgentEvent(
                    type = "state_machine_state_entered",
                    category = "agent",
                    subCategory = "state_machine",
                    summary = "📍 执行状态: $stateName",
                    detail = "mode=${stateStep.displayAgentName}"
                )
            )
        }

        return try {
            val result = executeStepByType(stateStep, workflow, context, directoryIsolation)
            result.output?.let { output ->
                context.setStepOutput("${outerStep.step}.$stateName", output)
                processExtract(stateStep, output, context)
            }

            if (enableMonitoring) {
                WorkflowMonitor.completeStep(context.executionId, subStepName, true, result.output)
                WorkflowMonitor.addEvent(
                    context.executionId, outerStep.step, AgentEvent(
                        type = "state_machine_state_completed",
                        category = "agent",
                        subCategory = "state_machine",
                        summary = "✅ 状态完成: $stateName",
                        detail = "event=${stateDef.successEvent}"
                    )
                )
            }

            StateExecutionResult(
                success = result.success,
                event = if (result.success) stateDef.successEvent else stateDef.failureEvent,
                output = result.output,
                error = result.error
            )
        } catch (e: CancellationException) {
            if (enableMonitoring) {
                WorkflowMonitor.completeStep(context.executionId, subStepName, false, error = "Cancelled")
            }
            throw e
        } catch (e: Exception) {
            if (enableMonitoring) {
                WorkflowMonitor.completeStep(context.executionId, subStepName, false, error = e.message)
                WorkflowMonitor.addEvent(
                    context.executionId, outerStep.step, AgentEvent(
                        type = "state_machine_state_failed",
                        category = "agent",
                        subCategory = "state_machine",
                        summary = "❌ 状态失败: $stateName",
                        detail = e.message
                    )
                )
            }

            StateExecutionResult(
                success = false,
                event = stateDef.failureEvent,
                error = e.message ?: "Unknown error"
            )
        }
    }

    private fun createStateMachineStateStep(
        outerStep: WorkflowStep,
        stateName: String,
        stateStep: StateStepConfig
    ): WorkflowStep {
        return WorkflowStep(
            step = "${outerStep.step}.$stateName",
            agent = stateStep.agent,
            input = stateStep.input,
            groupChat = stateStep.groupChat,
            agentBased = stateStep.agentBased,
            code = stateStep.code,
            classifier = stateStep.classifier,
            // Propagate sub_workflow so the state's step dispatches through
            // executeSubWorkflowStep — identical handling to a top-level
            // sub_workflow step, just scoped under the state-machine's
            // "$outerStep.$stateName" id for monitoring & output.
            subWorkflow = stateStep.subWorkflow,
            extract = stateStep.extract
        )
    }

    private fun toSafeStateKey(value: String): String {
        return value.replace(Regex("[^A-Za-z0-9_]+"), "_").trim('_').ifBlank { "state" }
    }

    // ==================== 确定性代码执行 ====================

    /**
     * 执行确定性代码步骤（不经过 LLM）
     *
     * 通过 ProcessBuilder 在子进程中执行人类预写的脚本代码。
     * 支持 Python、JavaScript、TypeScript、Bash、Ruby、Lua 和 CLI。
     * 步骤输出和变量通过环境变量注入到脚本中。
     */
    private suspend fun executeCodeStep(
        step: WorkflowStep,
        workflow: WorkflowDefinition,
        context: WorkflowExecutionContext,
        directoryIsolation: DirectoryIsolationConfig = DirectoryIsolationConfig()
    ): StepResult = withContext(Dispatchers.IO) {
        val config = step.code
            ?: throw WorkflowExecutionException("Code configuration missing for step '${step.step}'")

        logger.info { "[Workflow] Executing code step '${step.step}' (language=${config.language})" }

        if (enableMonitoring) {
            WorkflowMonitor.addEvent(
                context.executionId, step.step, AgentEvent(
                    type = "code_step_started",
                    category = "agent",
                    subCategory = "code",
                    summary = "⚙️ 代码步骤开始: ${config.language}",
                    detail = "language=${config.language}, timeout=${config.timeout}s" +
                        if (codeStepExecutor != null) ", sandboxed=true" else ""
                )
            )
        }

        // 构建最终脚本 (preamble + user script)
        val finalScript = buildFinalCodeScript(step, workflow, config)

        // 解释器解析
        val interpreterResolution = resolveCodeInterpreter(config.language)

        // 构建环境变量。code step 也需要拿到 directory_isolation 注入的
        // working_dir / output_dir / skills_dir 等变量，避免与 agent step 语义漂移。
        val templateContext = createStepTemplateContext(context, step.step, step.agent, directoryIsolation)
        val env = buildCodeStepEnvironment(templateContext)

        // 执行 (沙箱路径 或 旧路径)
        val rawOutput: String
        val exitCode: Int
        if (codeStepExecutor != null) {
            // ── 沙箱路径: 通过 SubprocessExecutor (Docker 或 Native) ──
            val result = executeCodeStepSandboxed(
                step, config, finalScript, interpreterResolution, env, templateContext, context, directoryIsolation
            )
            rawOutput = result.combinedOutput
            exitCode = result.exitCode
        } else {
            // ── 旧路径: ProcessBuilder 直接执行 (向后兼容 CLI/测试) ──
            val result = executeCodeStepLegacy(
                step, config, finalScript, interpreterResolution, env
            )
            rawOutput = result.first
            exitCode = result.second
        }

        // 截断输出
        val maxOutputLength = 100_000
        val truncatedOutput = if (rawOutput.length > maxOutputLength) {
            val half = maxOutputLength / 2
            rawOutput.take(half) + "\n... [TRUNCATED: ${rawOutput.length} chars] ...\n" + rawOutput.takeLast(half)
        } else {
            rawOutput
        }

        if (enableMonitoring) {
            WorkflowMonitor.addEvent(
                context.executionId, step.step, AgentEvent(
                    type = "code_step_completed",
                    category = "agent",
                    subCategory = "code",
                    summary = "⚙️ 代码步骤完成: exit_code=$exitCode",
                    detail = truncatedOutput.take(500)
                )
            )
        }

        if (exitCode != 0) {
            throw WorkflowExecutionException(
                "Code step '${step.step}' failed with exit code $exitCode:\n$truncatedOutput"
            )
        }

        StepResult(success = true, output = truncatedOutput.trim())
    }

    // ── Code step: 沙箱执行路径 ─────────────────────────────────────

    private suspend fun executeCodeStepSandboxed(
        step: WorkflowStep,
        config: CodeStepConfig,
        finalScript: String,
        interpreterResolution: CodeInterpreterResolution,
        env: Map<String, String>,
        templateContext: WorkflowExecutionContext,
        context: WorkflowExecutionContext,
        directoryIsolation: DirectoryIsolationConfig
    ): SubprocessExecutor.ExecResult {
        // Caller (executeCodeStep) guarantees codeStepExecutor != null before dispatching here,
        // but encode that contract explicitly to avoid silent NPE if the dispatch path changes.
        val executor = codeStepExecutor
            ?: throw WorkflowExecutionException(
                "Code step '${step.step}' was routed to the sandboxed executor but no SubprocessExecutor is configured"
            )

        // 1. 解析工作目录 (directory_isolation > config > cwd)
        val workDir = templateContext.variables["working_dir"]?.toString()?.takeIf { it.isNotBlank() }?.let(::File)
            ?: resolveCodeStepWorkDir(config, context, directoryIsolation, stepName = step.step)
        ensureWorkflowRuntimeDirectory(
            workDir,
            relaxDockerPermissions = directoryIsolation.enabled
        )

        // 2. 准备脚本文件路径；脚本内容会在 env spill 后写入，因为 bash
        //    需要根据 spill 结果决定是否注入兼容头。
        val scriptFileName = "wf_code_${sanitizeStepNameForId(step.step)}${interpreterResolution.suffix}"
        val scriptFile = File(workDir, scriptFileName)

        try {
            // 3. 构建挂载列表
            val mounts = mutableListOf<SubprocessExecutor.Mount>()
            val skillsDir = templateContext.variables["skills_dir"]?.toString()?.takeIf { it.isNotBlank() }?.let(::File)
                ?: templateContext.variables["shared_skills_dir"]?.toString()?.takeIf { it.isNotBlank() }?.let(::File)
                ?: File(directoryIsolation.sharedSkillsDir)
            val outputDir = templateContext.variables["output_dir"]?.toString()?.takeIf { it.isNotBlank() }?.let(::File)
                ?: resolveCodeStepOutputDir(context, directoryIsolation)
            val persistenceDir = templateContext.variables["persistence_storage_root"]?.toString()
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
            ensureWorkflowRuntimeDirectory(
                outputDir,
                relaxDockerPermissions = directoryIsolation.enabled
            )
            persistenceDir?.let {
                ensureWorkflowRuntimeDirectory(
                    it,
                    relaxDockerPermissions = directoryIsolation.enabled
                )
            }

            val isDocker = isDockerSubprocessMode()
            if (isDocker) {
                fun addMount(hostPath: File, readOnly: Boolean) {
                    val canonical = hostPath.canonicalFile
                    if (canonical.exists()) {
                        mounts += SubprocessExecutor.Mount(
                            hostPath = canonical,
                            containerPath = canonical.absolutePath,
                            readOnly = readOnly
                        )
                    }
                }

                if (skillsDir.exists() && skillsDir.isDirectory) {
                    addMount(skillsDir, readOnly = true)
                }
                addMount(outputDir, readOnly = false)
                persistenceDir?.let { addMount(it, readOnly = false) }
                val cacheDir = templateContext.variables["file_cache_storage"]?.toString()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::File)
                    ?.also {
                        ensureWorkflowRuntimeDirectory(it, relaxDockerPermissions = true)
                    }
                cacheDir?.let { addMount(it, readOnly = false) }
                val historyDir = templateContext.variables["history_storage_root"]?.toString()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::File)
                    ?.also {
                        ensureWorkflowRuntimeDirectory(it, relaxDockerPermissions = true)
                    }
                historyDir?.let { addMount(it, readOnly = false) }
            }

            // 4. 注入沙箱感知的环境变量
            val sandboxEnv = env.toMutableMap()
            val canonicalSkillsDir = skillsDir.canonicalFile
            val canonicalOutputDir = outputDir.canonicalFile
            val canonicalPersistenceDir = persistenceDir?.canonicalFile
            sandboxEnv["WF_VAR_WORKING_DIR"] = if (isDocker) "/workspace" else workDir.absolutePath
            sandboxEnv["WF_VAR_OUTPUT_DIR"] = canonicalOutputDir.absolutePath
            sandboxEnv["WF_VAR_OUTPUT_DIR_ABS"] = canonicalOutputDir.absolutePath
            canonicalPersistenceDir?.let {
                sandboxEnv["WF_VAR_PERSISTENCE_STORAGE_ROOT"] = it.absolutePath
            }
            if (canonicalSkillsDir.exists() && canonicalSkillsDir.isDirectory) {
                sandboxEnv["WF_VAR_SHARED_SKILLS_DIR"] = canonicalSkillsDir.absolutePath
                sandboxEnv["WF_VAR_SKILLS_DIR"] = canonicalSkillsDir.absolutePath
            }
            rewriteSandboxSkillPathVariables(sandboxEnv, canonicalSkillsDir)

            sandboxEnv["BRAIDRUN_WORKSPACE"] = if (isDocker) "/workspace" else workDir.absolutePath
            sandboxEnv["BRAIDRUN_OUTPUT_DIR"] = canonicalOutputDir.absolutePath
            sandboxEnv["BRAIDRUN_SKILLS_DIR"] = canonicalSkillsDir.absolutePath
            sandboxEnv["BRAIDRUN_EXECUTION_ID"] = context.executionId
            sandboxEnv["BRAIDRUN_STEP_NAME"] = step.step

            val spillResult = spillLargeCodeStepEnvValues(
                env = sandboxEnv,
                hostWorkingDir = workDir,
                containerWorkingDir = if (isDocker) "/workspace" else null,
                bridgeLanguage = config.language
            )
            scriptFile.writeText(scriptWithSpilledEnvBridge(finalScript, config.language, spillResult))

            // 5. 执行
            val command = interpreterResolution.command + scriptFileName
            return executor.execute(
                SubprocessExecutor.ExecRequest(
                    command = command,
                    workingDir = workDir,
                    timeoutSeconds = config.timeout.toLong(),
                    env = sandboxEnv,
                    mounts = mounts,
                    imageHint = resolveCodeStepImageHint(config.language),
                    userId = baseParameters.parameter("user_id", "local-user")
                )
            )
        } finally {
            scriptFile.delete()
        }
    }

    // ── Code step: 旧路径 (ProcessBuilder 直接执行) ─────────────────

    private fun executeCodeStepLegacy(
        step: WorkflowStep,
        config: CodeStepConfig,
        finalScript: String,
        interpreterResolution: CodeInterpreterResolution,
        env: Map<String, String>
    ): Pair<String, Int> {
        val tempFile = File.createTempFile("wf_code_${step.step}_", interpreterResolution.suffix)
        val spillWorkDir = Files.createTempDirectory("wf_code_env_${sanitizeStepNameForId(step.step)}_").toFile()
        tempFile.deleteOnExit()
        spillWorkDir.deleteOnExit()
        try {
            val processBuilder = ProcessBuilder(interpreterResolution.command + tempFile.absolutePath)
                .redirectErrorStream(true)

            val workDir = config.workingDirectory?.let { File(it) }
            if (workDir != null && workDir.isDirectory) {
                processBuilder.directory(workDir)
            }

            val legacyEnv = env.toMutableMap()
            val spillResult = spillLargeCodeStepEnvValues(
                env = legacyEnv,
                hostWorkingDir = spillWorkDir,
                containerWorkingDir = null,
                bridgeLanguage = config.language
            )
            tempFile.writeText(scriptWithSpilledEnvBridge(finalScript, config.language, spillResult))
            applyLegacyCodeStepEnvironment(processBuilder, legacyEnv)

            val process = processBuilder.start()
            // Consume stdout on a separate thread: a blocking readText() before
            // waitFor(timeout) would make the timeout unreachable while a script
            // hangs (the read only returns at process exit). Bounded capture
            // mirrors SubprocessExecutor.MAX_STREAM_BYTES so a runaway script
            // can't OOM the JVM; past the cap we keep draining so the child
            // never wedges on a full pipe.
            val outputRef = java.util.concurrent.atomic.AtomicReference("")
            val reader = Thread {
                outputRef.set(readLegacyCodeStepOutput(process.inputStream))
            }.apply {
                isDaemon = true
                name = "wf-code-legacy-${step.step}"
                start()
            }
            val completed = process.waitFor(config.timeout.toLong(), java.util.concurrent.TimeUnit.SECONDS)

            if (!completed) {
                // Kill first so the reader sees EOF and exits promptly.
                process.destroyForcibly()
                reader.join(5_000)
                throw WorkflowExecutionException(
                    "Code step '${step.step}' timed out after ${config.timeout}s"
                )
            }
            reader.join(10_000)
            return outputRef.get() to process.exitValue()
        } finally {
            tempFile.delete()
            spillWorkDir.deleteRecursively()
        }
    }

    /**
     * Bounded stdout capture for the legacy code-step path. Reads up to
     * [com.fartech.agents.tools.exec.SubprocessExecutor.MAX_STREAM_BYTES],
     * then drains (and discards) the rest so the child never blocks on a
     * full pipe. Returns what was captured when the pipe closes (EOF or
     * destroyForcibly).
     */
    private fun readLegacyCodeStepOutput(input: java.io.InputStream): String {
        val cap = com.fartech.agents.tools.exec.SubprocessExecutor.MAX_STREAM_BYTES
        val buffer = java.io.ByteArrayOutputStream(64 * 1024)
        val chunk = ByteArray(8 * 1024)
        var truncated = false
        try {
            input.use { stream ->
                while (true) {
                    val n = stream.read(chunk)
                    if (n < 0) break
                    val remaining = cap - buffer.size()
                    if (remaining > 0) {
                        buffer.write(chunk, 0, minOf(n, remaining))
                        if (n >= remaining) truncated = true
                    } else {
                        truncated = true
                    }
                }
            }
        } catch (_: Exception) {
            // Pipe closed (process killed) — return what we have.
        }
        val text = buffer.toString(Charsets.UTF_8)
        return if (truncated) {
            text + com.fartech.agents.tools.exec.SubprocessExecutor.STREAM_TRUNCATION_MARKER
        } else {
            text
        }
    }

    private fun spillLargeCodeStepEnvValues(
        env: MutableMap<String, String>,
        hostWorkingDir: File,
        containerWorkingDir: String?,
        bridgeLanguage: String
    ): CodeStepEnvSpillResult {
        val oversized = env.entries
            .filter { (key, value) -> shouldSpillCodeStepEnvValue(key, value) }
            .map { it.key to it.value }
        if (oversized.isEmpty()) return CodeStepEnvSpillResult()

        val hostEnvDir = File(hostWorkingDir, ".wf_env").apply { mkdirs() }
        if (containerWorkingDir != null) {
            relaxCodeStepEnvDirectoryPermissionsForDocker(hostEnvDir)
        }
        oversized.forEach { (key, value) ->
            val fileName = "${sanitizeStepNameForId(key)}.txt"
            val hostFile = File(hostEnvDir, fileName)
            hostFile.writeText(value, Charsets.UTF_8)
            if (containerWorkingDir != null) {
                relaxCodeStepEnvFilePermissionsForDocker(hostFile)
            }
            env.remove(key)
            env["${key}__FILE"] = if (containerWorkingDir != null) {
                "$containerWorkingDir/.wf_env/$fileName"
            } else {
                hostFile.absolutePath
            }
        }
        when (bridgeLanguage.lowercase()) {
            "python" -> installPythonSpilledEnvBridge(env, hostEnvDir, containerWorkingDir)
            "javascript", "typescript" -> installNodeSpilledEnvBridge(env, hostEnvDir, containerWorkingDir)
        }
        return CodeStepEnvSpillResult(spilled = true)
    }

    private data class CodeStepEnvSpillResult(
        val spilled: Boolean = false
    )

    private fun installPythonSpilledEnvBridge(
        env: MutableMap<String, String>,
        hostEnvDir: File,
        containerWorkingDir: String?
    ) {
        val bridgeFile = File(hostEnvDir, "sitecustomize.py")
        bridgeFile.writeText(PYTHON_SPILLED_ENV_BRIDGE, Charsets.UTF_8)
        if (containerWorkingDir != null) {
            relaxCodeStepEnvFilePermissionsForDocker(bridgeFile)
        }
        val bridgePath = if (containerWorkingDir != null) {
            "$containerWorkingDir/.wf_env"
        } else {
            hostEnvDir.absolutePath
        }
        val existing = env["PYTHONPATH"].orEmpty()
        env["PYTHONPATH"] = if (existing.isBlank()) {
            bridgePath
        } else {
            "$bridgePath${pathListSeparator(containerWorkingDir)}$existing"
        }
    }

    private fun installNodeSpilledEnvBridge(
        env: MutableMap<String, String>,
        hostEnvDir: File,
        containerWorkingDir: String?
    ) {
        val hostNodeModulesDir = File(hostEnvDir, "node_modules").apply { mkdirs() }
        val hostModuleDir = File(hostNodeModulesDir, NODE_SPILLED_ENV_BRIDGE_MODULE).apply { mkdirs() }
        val bridgeFile = File(hostModuleDir, "index.js")
        bridgeFile.writeText(NODE_SPILLED_ENV_BRIDGE, Charsets.UTF_8)
        if (containerWorkingDir != null) {
            relaxCodeStepEnvDirectoryPermissionsForDocker(hostNodeModulesDir)
            relaxCodeStepEnvDirectoryPermissionsForDocker(hostModuleDir)
            relaxCodeStepEnvFilePermissionsForDocker(bridgeFile)
        }
        val nodePath = if (containerWorkingDir != null) {
            "$containerWorkingDir/.wf_env/node_modules"
        } else {
            hostNodeModulesDir.absolutePath
        }
        val existingNodePath = env["NODE_PATH"].orEmpty()
        env["NODE_PATH"] = if (existingNodePath.isBlank()) {
            nodePath
        } else {
            "$nodePath${pathListSeparator(containerWorkingDir)}$existingNodePath"
        }
        val existing = env["NODE_OPTIONS"].orEmpty()
        env["NODE_OPTIONS"] = if (existing.isBlank()) {
            "--require $NODE_SPILLED_ENV_BRIDGE_MODULE"
        } else {
            "--require $NODE_SPILLED_ENV_BRIDGE_MODULE $existing"
        }
    }

    private fun pathListSeparator(containerWorkingDir: String?): String =
        if (containerWorkingDir != null) ":" else File.pathSeparator

    private fun relaxCodeStepEnvDirectoryPermissionsForDocker(directory: File) {
        val target = runCatching { directory.canonicalFile }.getOrDefault(directory.absoluteFile)
        target.setReadable(true, false)
        target.setWritable(true, true)
        target.setExecutable(true, false)
        runCatching {
            Files.setPosixFilePermissions(
                target.toPath(),
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE
                )
            )
        }
    }

    private fun relaxCodeStepEnvFilePermissionsForDocker(file: File) {
        val target = runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile)
        target.setReadable(true, false)
        target.setWritable(true, true)
        runCatching {
            Files.setPosixFilePermissions(
                target.toPath(),
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.OTHERS_READ
                )
            )
        }
    }

    private fun scriptWithSpilledEnvBridge(
        script: String,
        language: String,
        spillResult: CodeStepEnvSpillResult
    ): String {
        if (!spillResult.spilled) return script
        return when (language.lowercase()) {
            "bash" -> "$SHELL_SPILLED_ENV_BRIDGE\n\n$script"
            else -> script
        }
    }

    private fun shouldSpillCodeStepEnvValue(key: String, value: String): Boolean {
        if (value.toByteArray(Charsets.UTF_8).size <= CODE_STEP_ENV_FILE_THRESHOLD_BYTES) return false
        return key.startsWith("WF_VAR_") ||
            key.startsWith("STEP_OUTPUT_") ||
            key == "STEP_INPUTS"
    }

    /**
     * Legacy CLI/test code steps still use ProcessBuilder directly when no
     * SubprocessExecutor is injected. Keep their environment policy aligned with
     * NativeSubprocessExecutor: clear inherited secrets, preserve only a minimal
     * runtime allowlist, then apply workflow-controlled variables.
     */
    private fun applyLegacyCodeStepEnvironment(
        processBuilder: ProcessBuilder,
        requestEnv: Map<String, String>
    ) {
        val processEnv = processBuilder.environment()
        val parentSnapshot = HashMap(processEnv)
        processEnv.clear()

        val deployAllowlist = (System.getenv("BRAIDRUN_NATIVE_EXEC_ENV_ALLOWLIST") ?: "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        (CODE_STEP_ENV_ALLOWLIST + deployAllowlist).forEach { name ->
            parentSnapshot[name]?.let { processEnv[name] = it }
        }
        requestEnv.forEach { (key, value) -> processEnv[key] = value }
    }

    // ── Code step: 共享辅助方法 ─────────────────────────────────────

    /** 构建最终脚本: code_preamble (inline 或 ref 文件内容) + 用户脚本 */
    private fun buildFinalCodeScript(
        step: WorkflowStep,
        workflow: WorkflowDefinition,
        config: CodeStepConfig
    ): String {
        val script = if (config.script != null) {
            config.script
        } else {
            val scriptFilePath = config.scriptFile
                ?: throw WorkflowExecutionException("Code step '${step.step}': either script or script_file is required")
            val scriptFile = File(scriptFilePath)
            if (!scriptFile.exists()) {
                throw WorkflowExecutionException("Code step '${step.step}': script_file not found: ${config.scriptFile}")
            }
            scriptFile.readText()
        }

        val preambleConfig = workflow.codePreamble[config.language]
        val preamble: String? = when {
            preambleConfig?.inline != null -> preambleConfig.inline
            preambleConfig?.ref != null -> {
                val refFile = File(preambleConfig.ref)
                if (!refFile.exists()) {
                    throw WorkflowExecutionException(
                        "Code step '${step.step}': code_preamble ref file not found: ${preambleConfig.ref}"
                    )
                }
                refFile.readText()
            }
            else -> null
        }
        return if (preamble != null) "$preamble\n\n$script" else script
    }

    /** 构建 code step 环境变量 (WF_VAR_*, STEP_OUTPUT_*, STEP_INPUTS, WF_EXECUTION_ID) */
    private fun buildCodeStepEnvironment(context: WorkflowExecutionContext): Map<String, String> {
        val env = mutableMapOf<String, String>()
        fun sanitizeEnvKey(key: String): String = key.uppercase().replace(ENV_KEY_SANITIZE_REGEX, "_")
        context.variables.forEach { (key, value) ->
            env["WF_VAR_${sanitizeEnvKey(key)}"] = value.toString()
        }
        context.stepOutputs.forEach { (stepName, output) ->
            env["STEP_OUTPUT_${sanitizeEnvKey(stepName)}"] = output
        }
        val inputsJson = buildString {
            append("{")
            context.stepOutputs.entries.forEachIndexed { index, (key, value) ->
                if (index > 0) append(",")
                append("\"$key\":")
                append('"')
                append(escapeJsonValue(value))
                append('"')
            }
            append("}")
        }
        env["STEP_INPUTS"] = inputsJson
        // A.P1#18 — expose execution-scoped callback credentials so
        // in-workflow helpers (e.g. `braidrun-module-artifact-publish`) can
        // talk back to the web runtime without forcing the workflow author
        // to hand-manage API tokens.
        //
        // - `WF_EXECUTION_ID`: always populated. The CURRENT execution's id —
        //   for top-level steps this equals the user-visible run id; for
        //   sub-workflow steps this is the derived child id
        //   (`<parent>__<step>`). Modules that look up the user's artifacts
        //   should prefer `WF_ROOT_EXECUTION_ID` instead — see below.
        // - `WF_PARENT_EXECUTION_ID`: only populated for sub-workflow steps.
        //   The id of the IMMEDIATE parent context that spawned this child.
        //   For one-level nesting this equals the root.
        // - `WF_ROOT_EXECUTION_ID`: only populated for sub-workflow steps.
        //   The id of the TOP-MOST ancestor — i.e. the user-visible run id
        //   you see in the web UI. Modules like `braidrun-module-artifact-
        //   publish` and `braidrun-module-artifact-directory-publish` need
        //   THIS id (not their own derived child id) to find artifacts that
        //   the parent's other steps produced. Resolution order in those
        //   modules is `WF_ROOT_EXECUTION_ID > WF_EXECUTION_ID`. Added
        //   2026-04-26 — pre-fix, `braidrun-module-artifact-directory-
        //   publish` reliably failed when called as a sub-workflow because
        //   it used `WF_EXECUTION_ID = <parent>__<step>` and got "no
        //   artifacts" 400s. See execution-process-79621f70-...-failed.yaml.
        // - `WF_API_TOKEN`: short-lived (2 h) JWT minted server-side for the
        //   execution's owner. Only present when the web runtime has wired
        //   `jwtService` + `userAccountLookup` into ExecutionService AND the
        //   user is identifiable. Absent in CLI / dev-mode / test runs — the
        //   module's error message ("api_token is required") still guides
        //   users to supply one explicitly.
        // - `WF_BACKEND_URL`: the backend's https base URL so modules can
        //   construct the full endpoint (e.g. `${WF_BACKEND_URL}/api/...`).
        //   Absent when running via CLI without a backend context.
        env["WF_EXECUTION_ID"] = context.executionId
        context.parentExecutionId?.let { env["WF_PARENT_EXECUTION_ID"] = it }
        context.rootExecutionId?.let { env["WF_ROOT_EXECUTION_ID"] = it }
        val apiToken = baseParameters.firstOrNull { it.key == "execution_api_token" }
            ?.value?.let { v -> if (v is kotlinx.serialization.json.JsonPrimitive) v.content else v.toString() }
        if (!apiToken.isNullOrBlank()) {
            env["WF_API_TOKEN"] = apiToken
        }
        val backendUrl = baseParameters.firstOrNull { it.key == "execution_backend_url" }
            ?.value?.let { v -> if (v is kotlinx.serialization.json.JsonPrimitive) v.content else v.toString() }
        if (!backendUrl.isNullOrBlank()) {
            env["WF_BACKEND_URL"] = backendUrl
        }
        // Platform-level overlay (proxy vars, etc.) wins over user-defined
        // entries — if a workflow author happened to use an env key that
        // collides with e.g. HTTP_PROXY we still enforce the platform
        // decision for egress routing.
        if (extraCodeStepEnv.isNotEmpty()) {
            env.putAll(extraCodeStepEnv)
        }
        return env
    }

    /** 解析 code step 工作目录 */
    private fun resolveCodeStepWorkDir(
        config: CodeStepConfig,
        context: WorkflowExecutionContext,
        directoryIsolation: DirectoryIsolationConfig,
        stepName: String = "code"
    ): File {
        // 优先使用 directory_isolation 模式
        if (directoryIsolation.enabled) {
            val pattern = directoryIsolation.workingDirPattern
            val resolved = directoryIsolation.resolvePattern(
                pattern,
                executionId = context.executionId,
                stepName = stepName,
                userId = baseParameters.parameter("user_id", "")
            )
            return File(resolved)
        }
        // 回退到 config.workingDirectory 或当前目录
        return config.workingDirectory?.let { File(it) }?.takeIf { it.isDirectory }
            ?: File(".")
    }

    /** 解析 code step 输出目录 */
    private fun resolveCodeStepOutputDir(
        context: WorkflowExecutionContext,
        directoryIsolation: DirectoryIsolationConfig
    ): File {
        if (directoryIsolation.enabled) {
            val pattern = directoryIsolation.outputDirPattern
            val resolved = directoryIsolation.resolvePattern(
                pattern,
                executionId = context.executionId,
                userId = baseParameters.parameter("user_id", "")
            )
            return File(resolved)
        }
        return File(".")
    }

    private fun rewriteSandboxSkillPathVariables(
        env: MutableMap<String, String>,
        skillsDir: File
    ) {
        val canonicalSkillsDir = skillsDir.canonicalFile
        val skillsRoot = canonicalSkillsDir.absolutePath
        env.entries
            .filter { (key, value) ->
                key.startsWith("WF_VAR_") && value.isNotBlank()
            }
            .forEach { entry ->
                val value = entry.value.trim()
                val normalized = value.removePrefix("./")
                val rewrittenAbsolutePath = when {
                    "SKILL" !in entry.key -> null
                    else -> runCatching {
                        val candidate = File(value)
                        if (!candidate.isAbsolute) {
                            null
                        } else {
                            val candidatePath = candidate.canonicalPath.replace('\\', '/')
                            val canonicalRoot = skillsRoot.replace('\\', '/')
                            if (candidatePath == canonicalRoot || candidatePath.startsWith("$canonicalRoot/")) {
                                null
                            } else {
                                val marker = "/skills/"
                                val markerIndex = candidatePath.indexOf(marker)
                                if (markerIndex >= 0) {
                                    val suffix = candidatePath.substring(markerIndex + marker.length).trimStart('/')
                                    File(canonicalSkillsDir, suffix).absolutePath
                                } else {
                                    null
                                }
                            }
                        }
                    }.getOrNull()
                }
                entry.setValue(
                    rewrittenAbsolutePath ?: when {
                        normalized == "skills" -> skillsRoot
                        normalized.startsWith("skills/") ->
                            File(canonicalSkillsDir, normalized.removePrefix("skills/")).absolutePath
                        normalized == "braidrun-agent/skills" -> skillsRoot
                        normalized.startsWith("braidrun-agent/skills/") ->
                            File(canonicalSkillsDir, normalized.removePrefix("braidrun-agent/skills/")).absolutePath
                        normalized == "braidrun-web/skills" -> skillsRoot
                        normalized.startsWith("braidrun-web/skills/") ->
                            File(canonicalSkillsDir, normalized.removePrefix("braidrun-web/skills/")).absolutePath
                        normalized == "braidrun-agent/skills" -> skillsRoot
                        normalized.startsWith("braidrun-agent/skills/") ->
                            File(canonicalSkillsDir, normalized.removePrefix("braidrun-agent/skills/")).absolutePath
                        normalized == "braidrun-web/skills" -> skillsRoot
                        normalized.startsWith("braidrun-web/skills/") ->
                            File(canonicalSkillsDir, normalized.removePrefix("braidrun-web/skills/")).absolutePath
                        else -> entry.value
                    }
                )
            }
    }

    /** 根据代码语言选择 Docker 镜像 hint */
    private fun resolveCodeStepImageHint(language: String): String = when {
        language.contains("python") -> "python"
        language == "javascript" || language == "typescript" -> "node"
        else -> "shell"
    }

    private fun isDockerSubprocessMode(): Boolean =
        baseParameters.parameter("subprocess_mode", "native").equals("docker", ignoreCase = true)

    private fun ensureWorkflowRuntimeDirectory(
        directory: File,
        relaxDockerPermissions: Boolean = false
    ) {
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
            throw WorkflowExecutionException("Failed to create workflow runtime directory: ${directory.absolutePath}")
        }
        if (relaxDockerPermissions && isDockerSubprocessMode()) {
            relaxDirectoryPermissionsForDocker(directory)
        }
    }

    private fun relaxDirectoryPermissionsForDocker(directory: File) {
        val target = runCatching { directory.canonicalFile }.getOrDefault(directory.absoluteFile)
        val readable = target.setReadable(true, false)
        val writable = target.setWritable(true, false)
        val executable = if (target.isDirectory) target.setExecutable(true, false) else true
        if (readable && writable && executable) {
            return
        }

        runCatching {
            val permissions = if (target.isDirectory) {
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_WRITE,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_WRITE,
                    PosixFilePermission.OTHERS_EXECUTE
                )
            } else {
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_WRITE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_WRITE
                )
            }
            Files.setPosixFilePermissions(target.toPath(), permissions)
        }.onFailure { error ->
            logger.warn(error) {
                "[DockerSandbox] Failed to relax permissions for workflow runtime path: ${target.absolutePath}"
            }
        }
    }

    private fun resolveCodeInterpreter(language: String): CodeInterpreterResolution {
        val defaultCommandLine = defaultCodeInterpreterCommand(language, isWindows)
        val configuredCommandLine = baseParameters
            .parameter(WorkflowRuntimeParameterKeys.codeInterpreter(language), defaultCommandLine)
            .trim()
            .ifBlank { defaultCommandLine }
        val command = parseConfiguredCommandLine(configuredCommandLine)

        if (command.isEmpty()) {
            throw WorkflowExecutionException("Interpreter command for language '$language' is empty")
        }

        return CodeInterpreterResolution(
            command = command,
            suffix = codeScriptSuffix(language, isWindows)
        )
    }

    private fun defaultCodeInterpreterCommand(language: String, isWindows: Boolean): String =
        when (language) {
            "python" -> if (isWindows) "python" else "python3"
            "javascript" -> "node"
            "typescript" -> "npx tsx"
            "bash" -> if (isWindows) "bash" else "/bin/bash"
            "ruby" -> "ruby"
            "lua" -> "lua"
            "cli" -> if (isWindows) "cmd.exe /c" else "/bin/sh"
            else -> throw WorkflowExecutionException("Unsupported language: $language")
        }

    private fun codeScriptSuffix(language: String, isWindows: Boolean): String =
        when (language) {
            "python" -> ".py"
            "javascript" -> ".js"
            "typescript" -> ".ts"
            "bash" -> ".sh"
            "ruby" -> ".rb"
            "lua" -> ".lua"
            "cli" -> if (isWindows) ".cmd" else ".sh"
            else -> throw WorkflowExecutionException("Unsupported language: $language")
        }

    private fun parseConfiguredCommandLine(commandLine: String): List<String> {
        val trimmed = commandLine.trim()
        if (trimmed.isEmpty()) return emptyList()

        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quoteChar: Char? = null

        fun flushCurrent() {
            if (current.isNotEmpty()) {
                tokens += current.toString()
                current.clear()
            }
        }

        var index = 0
        while (index < trimmed.length) {
            val ch = trimmed[index]
            if (quoteChar != null) {
                if (ch == quoteChar) {
                    quoteChar = null
                } else if (quoteChar == '"' && ch == '\\' && index + 1 < trimmed.length) {
                    val next = trimmed[index + 1]
                    if (next == '"' || next == '\\') {
                        current.append(next)
                        index++
                    } else {
                        current.append(ch)
                    }
                } else {
                    current.append(ch)
                }
            } else {
                when {
                    ch.isWhitespace() -> flushCurrent()
                    ch == '"' || ch == '\'' -> quoteChar = ch
                    else -> current.append(ch)
                }
            }
            index++
        }

        if (quoteChar != null) {
            throw WorkflowExecutionException("Invalid interpreter command: unmatched quote in '$commandLine'")
        }

        flushCurrent()
        return tokens
    }

    // ==================== 智能分类路由 ====================

    /**
     * 执行分类路由步骤
     *
     * 使用 LLM Agent 对输入进行分类，根据分类结果设置变量。
     * 下游步骤可通过 condition 字段判断走不同分支。
     */
    private suspend fun executeClassifierStep(
        step: WorkflowStep,
        workflow: WorkflowDefinition,
        context: WorkflowExecutionContext,
        directoryIsolation: DirectoryIsolationConfig = DirectoryIsolationConfig()
    ): StepResult {
        val config = step.classifier
            ?: throw WorkflowExecutionException("Classifier configuration missing for step '${step.step}'")
        val agentDef = workflow.agents[config.agent]
            ?: throw WorkflowExecutionException("Classifier agent '${config.agent}' not found for step '${step.step}'")

        logger.info { "[Workflow:${workflow.name}] Executing classifier step '${step.step}' with agent '${config.agent}'" }

        if (enableMonitoring) {
            WorkflowMonitor.addEvent(
                context.executionId, step.step, AgentEvent(
                    type = "classifier_started",
                    category = "agent",
                    subCategory = "classifier",
                    summary = "🏷️ 分类路由开始: ${config.categories.size} 类别",
                    detail = "categories=${config.categories.map { it.name }}, output_variable=${config.outputVariable}"
                )
            )
        }

        // 构建分类 prompt
        val resolvedInput = resolveTemplate(config.input, context)
        val categoriesDescription = config.categories.joinToString("\n") { cat ->
            "- ${cat.name}: ${cat.description}"
        }
        val classificationPrompt = """You are a classifier. Analyze the following input and classify it into exactly one of the given categories.

INPUT:
$resolvedInput

CATEGORIES:
$categoriesDescription

IMPORTANT: You MUST respond with ONLY the category name (one of: ${config.categories.joinToString(", ") { it.name }}). Do not include any explanation, punctuation, or extra text. Just the category name."""

        // 创建 agent 并执行
        val rawResult = try {
            runStepAgent(
                agentName = config.agent,
                agentDef = agentDef,
                context = context,
                stepName = "${step.step}:classifier",
                input = classificationPrompt,
                directoryIsolation = directoryIsolation
            )
        } catch (e: CancellationException) {
            // Never convert a user cancel into a fabricated default-category result.
            throw e
        } catch (e: Exception) {
            logger.error(e) { "[Workflow:${workflow.name}] Classifier step '${step.step}' failed" }
            null
        }

        // 解析分类结果
        val categoryNames = config.categories.map { it.name }
        val matchedCategory = if (rawResult != null) {
            val trimmed = rawResult.trim().lowercase()
            categoryNames.firstOrNull { it.lowercase() == trimmed }
                ?: categoryNames.firstOrNull { trimmed.contains(it.lowercase()) }
        } else null

        val finalCategory = matchedCategory ?: config.defaultCategory
        ?: throw WorkflowExecutionException(
            "Classifier step '${step.step}' could not determine category from LLM output: '$rawResult'. " +
                    "Expected one of: ${categoryNames.joinToString(", ")}. Consider setting 'default_category'."
        )

        // 设置变量
        context.setVariable(config.outputVariable, finalCategory)
        logger.info { "[Workflow:${workflow.name}] Classifier step '${step.step}' result: ${config.outputVariable}='$finalCategory'" }

        if (enableMonitoring) {
            WorkflowMonitor.addEvent(
                context.executionId, step.step, AgentEvent(
                    type = "classifier_completed",
                    category = "agent",
                    subCategory = "classifier",
                    summary = "🏷️ 分类结果: $finalCategory",
                    detail = "output_variable=${config.outputVariable}, raw_result=$rawResult"
                )
            )
        }

        val output = "classification: $finalCategory"
        return StepResult(success = true, output = output)
    }

    // ==================== 通用结构化输出提取 ====================

    /**
     * 处理步骤的 extract 配置，从输出中提取变量
     */
    private fun processExtract(
        step: WorkflowStep,
        output: String,
        context: WorkflowExecutionContext
    ) {
        val extracts = step.extract ?: return

        for (config in extracts) {
            try {
                if (config.pattern != null) {
                    // 正则提取
                    extractAndSetVariable(config.pattern, config.variable, output, context)
                } else if (config.jsonPath != null) {
                    // JSON path 提取
                    extractJsonPath(config.jsonPath, config.variable, output, context)
                }
            } catch (e: Exception) {
                logger.error(e) { "[Extract] Step '${step.step}': failed to extract variable '${config.variable}': ${e.message}" }
            }
        }
    }

    /**
     * 从 JSON 输出中根据 JSON path 提取值
     * 支持简单的点分路径，如 $.result.status, $.items[0].name
     */
    private fun extractJsonPath(
        jsonPath: String,
        variableName: String,
        text: String,
        context: WorkflowExecutionContext
    ) {
        try {
            val candidates = extractBalancedJsonCandidates(text)
            if (candidates.isEmpty()) {
                logger.warn { "[Extract] No JSON found in output for json_path '$jsonPath'" }
                return
            }

            // 解析路径：$.field1.field2[0].field3
            val pathParts = jsonPath.removePrefix("$").removePrefix(".")
                .split(Regex("\\.|(?=\\[)"))
                .filter { it.isNotBlank() }

            val parsedCandidates = candidates
                .mapNotNull { candidate -> parseJsonCandidate(candidate) }
                .flatMap { candidate -> expandJsonCandidate(candidate) }

            if (parsedCandidates.isEmpty()) {
                logger.warn { "[Extract] No parseable JSON found in output for json_path '$jsonPath'" }
                return
            }

            val current = if (pathParts.isEmpty()) {
                parsedCandidates.maxByOrNull { scoreJsonCandidate(it) }?.element
                    ?: throw IllegalArgumentException("No parseable JSON candidate found")
            } else {
                parsedCandidates.firstNotNullOfOrNull { candidate ->
                    runCatching { selectJsonPath(candidate.element, pathParts) }.getOrNull()
                } ?: throw IllegalArgumentException("No JSON candidate matched path '$jsonPath'")
            }

            val value = when (current) {
                is JsonPrimitive -> current.content
                else -> current.toString()
            }

            context.setVariable(variableName, value)
            logger.info { "[Extract] Extracted variable '$variableName' = '$value' via json_path '$jsonPath'" }
        } catch (e: Exception) {
            logger.warn { "[Extract] Failed to extract json_path '$jsonPath' for variable '$variableName': ${e.message}" }
        }
    }

    private data class ParsedJsonCandidate(
        val text: String,
        val element: JsonElement
    )

    private fun parseJsonCandidate(text: String): ParsedJsonCandidate? {
        return try {
            ParsedJsonCandidate(text, kotlinx.serialization.json.Json.parseToJsonElement(text))
        } catch (e: Exception) {
            null
        }
    }

    private fun expandJsonCandidate(candidate: ParsedJsonCandidate): List<ParsedJsonCandidate> {
        val expanded = mutableListOf(candidate)
        extractNestedJsonTexts(candidate.element).forEach { nestedText ->
            extractBalancedJsonCandidates(nestedText)
                .mapNotNull { parseJsonCandidate(it) }
                .forEach { nestedCandidate -> expanded.add(nestedCandidate) }
        }
        return expanded
    }

    private fun extractNestedJsonTexts(element: JsonElement): List<String> {
        val root = element as? JsonObject ?: return emptyList()
        val containers = listOfNotNull(
            root,
            root["arguments"] as? JsonObject,
            root["tool_args"] as? JsonObject
        )
        val keys = listOf("content", "message", "text", "result", "output")
        return containers
            .flatMap { obj -> keys.mapNotNull { key -> obj[key]?.jsonStringOrNull() } }
            .map { it.trim() }
            .filter { it.startsWith("{") || it.startsWith("[") || it.contains("```") }
            .distinct()
    }

    private fun JsonElement.jsonStringOrNull(): String? {
        return (this as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
    }

    private fun extractBestBusinessJsonText(text: String): String? {
        // 快速预检:此函数在每次 runStepAgent 输出上都会被调用
        // (enrichAgentOutputWithExitToolPayload),而过滤条件要求候选对象含
        // "sheets" 键 —— 文本里压根没有 "sheets" 字样时,解析所有平衡 JSON
        // 候选纯属浪费(对多百 KB 输出是可观的 CPU/分配)。
        if (!text.contains("sheets")) return null
        return extractBalancedJsonCandidates(text)
            .mapNotNull { candidate -> parseJsonCandidate(candidate) }
            .flatMap { candidate -> expandJsonCandidate(candidate) }
            .filter { candidate ->
                val obj = candidate.element as? JsonObject
                obj != null && "sheets" in obj
            }
            .maxByOrNull { candidate -> scoreJsonCandidate(candidate) }
            ?.text
    }

    private fun selectJsonPath(element: JsonElement, pathParts: List<String>): JsonElement {
        var current = element
        for (part in pathParts) {
            current = if (part.startsWith("[") && part.endsWith("]")) {
                val index = part.removePrefix("[").removeSuffix("]").toInt()
                current.jsonArray[index]
            } else {
                current.jsonObject[part]
                    ?: throw IllegalArgumentException("Field '$part' not found in JSON")
            }
        }
        return current
    }

    private fun scoreJsonCandidate(candidate: ParsedJsonCandidate): Int {
        var score = candidate.text.length.coerceAtMost(1_000_000)
        val obj = candidate.element as? JsonObject
        if (obj != null) {
            if ("sheets" in obj) score += 2_000_000
            if ("result" in obj || "data" in obj || "items" in obj) score += 50_000
            if ("tool_call_id" in obj || "tool_name" in obj || "tool_args" in obj) score -= 1_000_000
            if ("tool" in obj && "arguments" in obj) score -= 1_000_000
        }
        return score
    }

    private fun extractBalancedJsonCandidates(text: String): List<String> {
        val raw = text.trim()
        if (raw.isEmpty()) return emptyList()

        val candidates = linkedSetOf<String>()

        Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```", RegexOption.IGNORE_CASE)
            .findAll(raw)
            .forEach { match ->
                val fenced = match.groupValues.getOrNull(1)?.trim().orEmpty()
                if (fenced.isNotBlank()) {
                    candidates.add(fenced)
                    findBalancedJsonSlices(fenced).forEach { candidates.add(it) }
                }
            }

        findBalancedJsonSlices(raw).forEach { candidates.add(it) }

        if (raw.firstOrNull() in setOf('{', '[')) {
            candidates.add(raw)
        }

        return candidates.filter { it.isNotBlank() }
    }

    // NOTE: deliberately scans from EVERY opening brace with fresh string-state
    // (O(n×openers) worst case) instead of a single linear pass. The per-opener
    // rescan is what lets json_path extraction recover an inner {"sheets": …}
    // object embedded inside a MALFORMED outer JSON string (unescaped quotes) —
    // a single-pass scanner inherits the outer string state and never sees the
    // inner opener. Pinned by WorkflowExecutorBehaviorTest.`extractJsonPath
    // recovers json from exit tool detail text`. The hot path is protected by
    // the cheap `contains("sheets")` pre-check in extractBestBusinessJsonText.
    private fun findBalancedJsonSlices(text: String): List<String> {
        val result = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char == '{' || char == '[') {
                val end = findBalancedJsonEnd(text, index)
                if (end > index) {
                    result.add(text.substring(index, end + 1).trim())
                    index += 1
                    continue
                }
            }
            index += 1
        }
        return result
    }

    private fun findBalancedJsonEnd(text: String, start: Int): Int {
        if (start !in text.indices || (text[start] != '{' && text[start] != '[')) return -1
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> stack.addLast('}')
                '[' -> stack.addLast(']')
                '}', ']' -> {
                    if (stack.isEmpty() || stack.removeLast() != char) return -1
                    if (stack.isEmpty()) return index
                }
            }
        }
        return -1
    }

    // ==================== 动态列表遍历 ====================

    /**
     * 遍历列表并对每项执行步骤
     */
    private suspend fun executeWithIteration(
        step: WorkflowStep,
        workflow: WorkflowDefinition,
        context: WorkflowExecutionContext
    ): StepExecutionResult {
        val config = step.iterateOver
            ?: throw WorkflowExecutionException("iterateOver configuration missing for step '${step.step}'")

        // 解析数据源
        val resolvedSource = resolveTemplate(config.source, context)
        val items = if (config.delimiter == "json_array") {
            // JSON 数组解析
            try {
                val jsonElement = kotlinx.serialization.json.Json.parseToJsonElement(resolvedSource.trim())
                jsonElement.jsonArray.map { elem ->
                    when (elem) {
                        is JsonPrimitive -> elem.content
                        else -> elem.toString()
                    }
                }
            } catch (e: Exception) {
                throw WorkflowExecutionException(
                    "Step '${step.step}' iterate_over: failed to parse source as JSON array: ${e.message}"
                )
            }
        } else {
            // 分隔符分割
            resolvedSource.split(config.delimiter).map { it.trim() }.filter { it.isNotBlank() }
        }

        // 应用 max_items 限制
        val effectiveItems = if (config.maxItems > 0) items.take(config.maxItems) else items

        logger.info { "[Workflow:${workflow.name}] Starting iteration for step '${step.step}': ${effectiveItems.size} items (parallel=${config.parallel})" }

        if (enableMonitoring) {
            WorkflowMonitor.startStep(context.executionId, step.step, agentName = step.displayAgentName)
            WorkflowMonitor.addEvent(
                context.executionId, step.step, AgentEvent(
                    type = "iteration_started",
                    category = "agent",
                    subCategory = "iteration",
                    summary = "🔄 遍历开始: ${effectiveItems.size} 项 (${if (config.parallel) "并行" else "串行"})",
                    detail = "items=${effectiveItems.size}, parallel=${config.parallel}, item_variable=${config.itemVariable}"
                )
            )
        }

        val startTime = System.currentTimeMillis()
        val results = mutableListOf<String>()

        if (config.parallel) {
            // 并行遍历
            val maxParallel = config.maxParallel ?: effectiveItems.size
            val semaphore = Semaphore(maxParallel)

            val parallelResults = coroutineScope {
                effectiveItems.mapIndexed { index, item ->
                    async {
                        semaphore.withPermit {
                            executeSingleIteration(step, workflow, context, item, index, effectiveItems.size)
                        }
                    }
                }.awaitAll()
            }
            results.addAll(parallelResults)
        } else {
            // 串行遍历
            for ((index, item) in effectiveItems.withIndex()) {
                // iterate_over 不经过 executeStepOnce 的 per-step timeout,长列表的
                // 慢迭代会无限超出 timeout.total —— 在每个 item 边界检查总预算。
                checkWorkflowDeadline(context.executionId, workflow)
                val result = executeSingleIteration(step, workflow, context, item, index, effectiveItems.size)
                results.add(result)
            }
        }

        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime

        // 聚合结果
        val aggregatedOutput = results.joinToString("\n---\n")

        // 如果配置了 results_variable，存入变量
        config.resultsVariable?.let { varName ->
            context.setVariable(varName, aggregatedOutput)
        }

        if (enableMonitoring) {
            WorkflowMonitor.addEvent(
                context.executionId, step.step, AgentEvent(
                    type = "iteration_completed",
                    category = "agent",
                    subCategory = "iteration",
                    summary = "🔄 遍历完成: ${effectiveItems.size} 项, ${duration / 1000.0}s"
                )
            )
        }

        val stepResult = StepExecutionResult(
            stepName = step.step,
            agentName = step.displayAgentName,
            success = true,
            startTime = startTime,
            endTime = endTime,
            duration = duration,
            output = aggregatedOutput
        )

        context.stepResults[step.step] = stepResult
        context.setStepOutput(step.step, aggregatedOutput)

        // 通用输出提取
        processExtract(step, aggregatedOutput, context)

        if (enableMonitoring) {
            WorkflowMonitor.completeStep(context.executionId, step.step, success = true, output = aggregatedOutput)
        }

        return stepResult
    }

    /**
     * 执行单次迭代
     */
    private suspend fun executeSingleIteration(
        step: WorkflowStep,
        workflow: WorkflowDefinition,
        context: WorkflowExecutionContext,
        item: String,
        index: Int,
        totalItems: Int
    ): String {
        val config = step.iterateOver
            ?: throw WorkflowExecutionException("iterateOver configuration missing for step '${step.step}'")
        val dirIsolation = workflow.directoryIsolation
        val iterationContext = context.snapshot(
            extraVariables = mapOf(
                config.itemVariable to item,
                config.indexVariable to index.toString(),
                "${config.itemVariable}_total" to totalItems.toString()
            )
        )
        val iterationStepName = "${step.step}:iterate:$index"
        val iterationTemplateContext = createStepTemplateContext(
            iterationContext,
            iterationStepName,
            step.agent,
            dirIsolation
        )

        logger.info { "[Workflow:${workflow.name}] Iteration ${index + 1}/$totalItems for step '${step.step}'" }

        if (enableMonitoring) {
            WorkflowMonitor.addEvent(
                context.executionId, step.step, AgentEvent(
                    type = "iteration_item",
                    category = "agent",
                    subCategory = "iteration",
                    summary = "🔄 遍历项 ${index + 1}/$totalItems",
                    detail = "item=${item.take(200)}"
                )
            )
        }

        awaitDebugCheckpoint(
            point = WorkflowDebugPoint.ITERATION_ITEM,
            stepName = step.step,
            context = iterationTemplateContext,
            label = "${index + 1}/$totalItems"
        )

        // 根据步骤类型执行
        val result = when {
            step.isCode -> executeCodeStep(step, workflow, iterationTemplateContext, dirIsolation)
            else -> {
                val iterAgentName = step.agent
                    ?: throw WorkflowExecutionException("Agent name is required for iteration step '${step.step}'")
                val agentDef = workflow.agents[iterAgentName]
                    ?: throw WorkflowExecutionException("Agent '$iterAgentName' not found")
                val resolvedInput = resolveTemplate(
                    step.input ?: throw WorkflowExecutionException("Input is required for iteration step '${step.step}'"),
                    iterationTemplateContext
                )
                val output = runStepAgent(
                    agentName = iterAgentName,
                    agentDef = agentDef,
                    context = iterationTemplateContext,
                    stepName = iterationStepName,
                    input = resolvedInput,
                    directoryIsolation = dirIsolation
                )
                StepResult(success = true, output = output)
            }
        }

        return result.output ?: ""
    }

    // ==================== 多路径变量聚合 ====================

    /**
     * 处理步骤的 aggregate 配置，合并多个数据源为单一变量
     */
    private fun processAggregate(
        step: WorkflowStep,
        context: WorkflowExecutionContext
    ) {
        val config = step.aggregate ?: return

        // 解析所有数据源
        val resolvedSources = config.sources.map { source ->
            resolveTemplate(source, context)
        }

        // 根据策略聚合
        val aggregated = when (config.strategy) {
            "concat" -> resolvedSources.joinToString(config.separator)

            "json_array" -> {
                val jsonElements = resolvedSources.map { src ->
                    JsonPrimitive(src)
                }
                JsonArray(jsonElements).toString()
            }

            "numbered_list" -> resolvedSources.mapIndexed { index, src ->
                "${index + 1}. $src"
            }.joinToString("\n\n")

            "pick_longest" -> resolvedSources.maxByOrNull { it.length } ?: ""

            "pick_shortest" -> resolvedSources.minByOrNull { it.length } ?: ""

            else -> resolvedSources.joinToString(config.separator)
        }

        context.setVariable(config.outputVariable, aggregated)
        logger.info { "[Aggregate] Step '${step.step}': aggregated ${resolvedSources.size} sources into variable '${config.outputVariable}' (strategy=${config.strategy}, length=${aggregated.length})" }

        if (enableMonitoring) {
            WorkflowMonitor.addEvent(
                context.executionId, step.step, AgentEvent(
                    type = "aggregate_completed",
                    category = "agent",
                    subCategory = "aggregate",
                    summary = "📊 聚合完成: ${config.sources.size} 源 → ${config.outputVariable}",
                    detail = "strategy=${config.strategy}, output_length=${aggregated.length}"
                )
            )
        }
    }

    /**
     * 执行 Group Chat 步骤
     *
     * 多个 agent 在共享对话空间中轮流发言，通过 round_robin 或 random 策略选择发言者。
     * 对话在达到最大轮次或检测到终止关键词时结束。
     * 对话结束后由 summary_agent（或最后一个发言者）生成摘要作为步骤输出。
     */
    private suspend fun executeGroupChatStep(
        step: WorkflowStep,
        workflow: WorkflowDefinition,
        context: WorkflowExecutionContext,
        directoryIsolation: DirectoryIsolationConfig = DirectoryIsolationConfig()
    ): StepResult {
        val config = step.groupChat
            ?: throw WorkflowExecutionException("groupChat configuration missing for step '${step.step}'")
        val chatHistory = mutableListOf<GroupChatMessage>()

        logger.info { "[Workflow:${workflow.name}] Starting group chat '${step.step}' with participants: ${config.participants}" }

        // 发送 group chat 开始监控事件
        if (enableMonitoring) {
            WorkflowMonitor.addEvent(
                context.executionId, step.step, AgentEvent(
                    type = "group_chat_started",
                    category = "agent",
                    subCategory = "group_chat",
                    summary = "🗣️ Group Chat 开始: ${config.participants.joinToString(", ")}",
                    detail = "participants=${config.participants}, max_rounds=${config.maxRounds}, speaker_selection=${config.speakerSelection}"
                )
            )
        }

        // 解析初始消息（支持模板变量）
        val initialMessage = if (config.initialMessage != null) {
            resolveTemplate(config.initialMessage, context)
        } else if (step.input != null) {
            resolveTemplate(step.input, context)
        } else {
            "Please begin the discussion."
        }

        // 构建发言者顺序
        val speakerOrder = when (config.speakerSelection) {
            "random" -> config.participants.shuffled()
            else -> config.participants // round_robin
        }

        var terminated = false

        // 执行对话轮次：每个 participant 发言一次算一轮
        for (round in 1..config.maxRounds) {
            if (terminated) break

            for (speakerName in speakerOrder) {
                if (terminated) break
                val agentDef = workflow.agents[speakerName]
                    ?: throw WorkflowExecutionException("Group chat participant agent '$speakerName' not found")

                logger.info { "[Workflow:${workflow.name}] Group chat round $round/${config.maxRounds}: $speakerName speaking" }

                awaitDebugCheckpoint(
                    point = WorkflowDebugPoint.GROUP_CHAT_ROUND,
                    stepName = step.step,
                    context = context,
                    label = "round=$round speaker=$speakerName"
                )

                // 构建本轮的 prompt（包含完整对话历史）
                val prompt = buildGroupChatPrompt(
                    speakerName = speakerName,
                    config = config,
                    chatHistory = chatHistory,
                    initialMessage = initialMessage,
                    round = round,
                    context = context
                )

                val response = try {
                    runStepAgent(
                        agentName = speakerName,
                        agentDef = agentDef,
                        context = context,
                        stepName = "${step.step}:group_chat:round${round}:${speakerName}",
                        input = prompt,
                        directoryIsolation = directoryIsolation
                    )
                } catch (e: CancellationException) {
                    // Don't record a fabricated "[Error: ...]" turn and keep looping
                    // through remaining speakers/rounds on user cancel.
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "[Workflow:${workflow.name}] Group chat: $speakerName failed in round $round" }
                    "[Error: ${e.message}]"
                }

                // 记录消息
                val message = GroupChatMessage(
                    speaker = speakerName,
                    content = response,
                    round = round
                )
                chatHistory.add(message)

                // 发送对话消息监控事件
                if (enableMonitoring) {
                    WorkflowMonitor.addEvent(
                        context.executionId, step.step, AgentEvent(
                            type = "group_chat_message",
                            category = "agent",
                            subCategory = "group_chat",
                            summary = "💬 [$speakerName] (Round $round/${config.maxRounds})",
                            detail = response
                        )
                    )
                }

                // 检查终止关键词
                if (config.terminationKeyword != null && containsGroupChatTerminationSignal(response, config.terminationKeyword)) {
                    val readiness = assessGroupChatTerminationReadiness(config, speakerName, chatHistory)
                    if (readiness.canTerminate) {
                        logger.info { "[Workflow:${workflow.name}] Group chat terminated by keyword '${config.terminationKeyword}' from $speakerName" }
                        terminated = true

                        if (enableMonitoring) {
                            WorkflowMonitor.addEvent(
                                context.executionId, step.step, AgentEvent(
                                    type = "group_chat_terminated",
                                    category = "agent",
                                    subCategory = "group_chat",
                                    summary = "🏁 Group Chat 达成共识 (by $speakerName, Round $round)"
                                )
                            )
                        }
                    } else if (enableMonitoring) {
                        WorkflowMonitor.addEvent(
                            context.executionId, step.step, AgentEvent(
                                type = "group_chat_termination_ignored",
                                category = "agent",
                                subCategory = "group_chat",
                                summary = "⏸️ 忽略终止信号 (by $speakerName, Round $round)",
                                detail = readiness.reason
                            )
                        )
                    }
                }
            }
        }

        // 生成最终输出（摘要）
        val output = generateGroupChatSummary(
            step = step,
            config = config,
            chatHistory = chatHistory,
            workflow = workflow,
            context = context,
            directoryIsolation = directoryIsolation,
            terminated = terminated
        )

        // 发送完成事件
        if (enableMonitoring) {
            WorkflowMonitor.addEvent(
                context.executionId, step.step, AgentEvent(
                    type = "group_chat_completed",
                    category = "agent",
                    subCategory = "group_chat",
                    summary = "✅ Group Chat 完成: ${chatHistory.size} 条消息, ${chatHistory.lastOrNull()?.round ?: 0} 轮",
                    detail = output
                )
            )
        }

        logger.info { "[Workflow:${workflow.name}] Group chat '${step.step}' completed with ${chatHistory.size} messages" }
        return StepResult(success = true, output = output)
    }

    /**
     * 构建 Group Chat 某一轮的 prompt
     */
    private fun buildGroupChatPrompt(
        speakerName: String,
        config: GroupChatConfig,
        chatHistory: List<GroupChatMessage>,
        initialMessage: String,
        round: Int,
        context: WorkflowExecutionContext
    ): String {
        val sb = StringBuilder()

        // 角色说明
        val isModerator = speakerName == config.moderator
        sb.appendLine("You are '$speakerName' participating in a group discussion.")
        if (isModerator) {
            sb.appendLine("You are the MODERATOR of this discussion. Guide the conversation, synthesize viewpoints, and help reach consensus.")
        }
        sb.appendLine()

        // 参与者信息
        sb.appendLine("Participants: ${config.participants.joinToString(", ")}")
        if (config.moderator != null) {
            sb.appendLine("Moderator: ${config.moderator}")
        }
        sb.appendLine("Current round: $round/${config.maxRounds}")
        sb.appendLine()

        // 初始话题
        sb.appendLine("=== Discussion Topic ===")
        sb.appendLine(initialMessage)
        sb.appendLine()

        // 对话历史 — sliding window to control token growth
        if (chatHistory.isNotEmpty()) {
            // Keep recent messages to avoid unbounded prompt growth in long discussions
            val maxHistoryMessages = config.maxRounds * 2
            val recentHistory = if (chatHistory.size > maxHistoryMessages) {
                sb.appendLine("=== Conversation History (most recent ${maxHistoryMessages} of ${chatHistory.size} messages) ===")
                chatHistory.takeLast(maxHistoryMessages)
            } else {
                sb.appendLine("=== Conversation History ===")
                chatHistory
            }
            recentHistory.forEach { msg ->
                sb.appendLine("[${msg.speaker}] (Round ${msg.round}):")
                sb.appendLine(msg.content)
                sb.appendLine()
            }
        }

        // 终止说明
        if (config.terminationKeyword != null) {
            val terminationReadiness = assessGroupChatTerminationReadiness(
                config = config,
                speakerName = speakerName,
                chatHistory = chatHistory,
                includeCurrentSpeaker = true
            )
            sb.appendLine("=== Instructions ===")
            if (config.moderator != null && !isModerator) {
                sb.appendLine("Only the moderator '${config.moderator}' may emit the termination keyword. Do NOT output '${config.terminationKeyword}'.")
            } else {
                sb.appendLine("When you believe the discussion has reached a satisfactory conclusion or consensus, end your response with the exact text '${config.terminationKeyword}' on its own final line.")
                sb.appendLine("Do NOT mention, explain, or quote '${config.terminationKeyword}' unless you intend to terminate the discussion immediately.")
                if (!terminationReadiness.canTerminate) {
                    sb.appendLine("You may NOT terminate the discussion yet: ${terminationReadiness.reason}")
                }
            }
            if (round >= config.maxRounds) {
                sb.appendLine("NOTE: This is the FINAL round. Please provide your concluding remarks.")
            }
            sb.appendLine()
        }

        sb.appendLine("Please provide your response as '$speakerName':")
        return sb.toString()
    }

    /**
     * 生成 Group Chat 的摘要输出
     */
    private suspend fun generateGroupChatSummary(
        step: WorkflowStep,
        config: GroupChatConfig,
        chatHistory: List<GroupChatMessage>,
        workflow: WorkflowDefinition,
        context: WorkflowExecutionContext,
        directoryIsolation: DirectoryIsolationConfig,
        terminated: Boolean
    ): String {
        // 确定摘要 agent
        val summaryAgentName = config.summaryAgent
            ?: chatHistory.lastOrNull()?.speaker
            ?: config.participants.first()

        val summaryAgentDef = workflow.agents[summaryAgentName]
            ?: throw WorkflowExecutionException("Summary agent '$summaryAgentName' not found")

        // 构建摘要 prompt
        val summaryPrompt = buildString {
            appendLine("You participated in a group discussion as part of a team. Please provide a comprehensive summary of the discussion and its conclusions.")
            appendLine()
            appendLine("=== Full Discussion Transcript ===")
            chatHistory.forEach { msg ->
                appendLine("[${msg.speaker}] (Round ${msg.round}):")
                appendLine(msg.content)
                appendLine()
            }
            appendLine("=== End of Transcript ===")
            appendLine()
            if (terminated) {
                appendLine("The discussion reached a consensus. Please summarize the key conclusions and action items.")
            } else {
                appendLine("The discussion ended after reaching the maximum number of rounds (${config.maxRounds}). Please summarize the key points discussed, areas of agreement, and any remaining open questions.")
            }
            appendLine()
            appendLine("Provide a clear, structured summary that captures the essential outcomes of this discussion.")
        }

        // 使用摘要 agent 生成总结
        if (enableMonitoring) {
            WorkflowMonitor.addEvent(
                context.executionId, step.step, AgentEvent(
                    type = "group_chat_summarizing",
                    category = "agent",
                    subCategory = "group_chat",
                    summary = "📝 正在生成对话摘要 (by $summaryAgentName)..."
                )
            )
        }

        return try {
            runStepAgent(
                agentName = summaryAgentName,
                agentDef = summaryAgentDef,
                context = context,
                stepName = "${step.step}:group_chat:summary",
                input = summaryPrompt,
                directoryIsolation = directoryIsolation
            )
        } catch (e: Exception) {
            logger.warn(e) { "[Workflow:${workflow.name}] Failed to generate group chat summary, falling back to raw transcript" }
            // 回退：直接输出对话记录
            buildString {
                appendLine("=== Group Chat Transcript (${chatHistory.size} messages) ===")
                chatHistory.forEach { msg ->
                    appendLine("[${msg.speaker}] (Round ${msg.round}):")
                    appendLine(msg.content)
                    appendLine()
                }
            }
        }
    }

    /**
     * 执行 Agent-based 动态编排步骤
     *
     * 创建一个 Orchestrator Agent，为其装配 OrchestratorTools，
     * 由 Orchestrator 通过 LLM 推理动态委派任务给 Worker Agent。
     *
     * 执行流程：
     * 1. 构建 Orchestrator 的 system prompt（注入可用 Agent 信息和目标描述）
     * 2. 创建 OrchestratorTools（绑定 Worker Agent 工厂函数）
     * 3. 创建 Orchestrator Agent（使用 OrchestratorTools 作为工具集）
     * 4. 运行 Orchestrator Agent，让其自主决策任务分配
     * 5. 收集最终输出
     */
    private suspend fun executeAgentBasedStep(
        step: WorkflowStep,
        workflow: WorkflowDefinition,
        context: WorkflowExecutionContext,
        directoryIsolation: DirectoryIsolationConfig = DirectoryIsolationConfig()
    ): StepResult {
        val config = step.agentBased
            ?: throw WorkflowExecutionException("agentBased configuration missing for step '${step.step}'")

        logger.info { "[Workflow:${workflow.name}] Starting agent_based step '${step.step}' with participants: ${config.participants}" }

        if (enableMonitoring) {
            WorkflowMonitor.addEvent(
                context.executionId, step.step, AgentEvent(
                    type = "agent_based_started",
                    category = "agent",
                    subCategory = "orchestrator",
                    summary = "🤖 Agent-based 编排开始: ${config.participants.joinToString(", ")}",
                    detail = "goal=${config.goal.take(200)}, max_steps=${config.maxSteps}"
                )
            )
        }

        // 构建 participant 描述信息（从 AgentDefinition 的 system_prompt 或 preset 提取）
        val participantDescriptions = config.participants.associateWith { agentName ->
            val agentDef = workflow.agents[agentName]
                ?: throw WorkflowExecutionException("Agent '$agentName' not found for agent_based step '${step.step}'")
            val params = agentDef.resolveParameters()
            val prompt = (params["system_prompt"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            val presetInfo = agentDef.preset?.let { "preset: $it" } ?: "custom"
            "$presetInfo — ${prompt.take(150)}"
        }

        // 监控事件回调
        val orchestratorEventCallback: com.fartech.agents.commons.MonitoringEventCallback? =
            if (enableMonitoring) {
                { type, summary, detail ->
                    WorkflowMonitor.addEvent(
                        context.executionId,
                        step.step,
                        AgentEvent(
                            type = type,
                            category = "agent",
                            subCategory = "orchestrator",
                            summary = summary,
                            detail = detail
                        )
                    )
                }
            } else null

        // Worker Agent 工厂函数
        val workerAgentRunner: suspend (String, String, String) -> String =
            { agentName, stepLabel, task ->
                val agentDef = workflow.agents[agentName]
                    ?: throw WorkflowExecutionException("Worker agent '$agentName' not found")
                awaitDebugCheckpoint(
                    point = WorkflowDebugPoint.AGENT_BASED_DELEGATION,
                    stepName = step.step,
                    context = context,
                    label = "$agentName:$stepLabel"
                )
                runStepAgent(
                    agentName = agentName,
                    agentDef = agentDef,
                    context = context,
                    stepName = "${step.step}:$stepLabel",
                    input = task,
                    directoryIsolation = directoryIsolation
                )
            }

        // 创建 OrchestratorTools
        val orchestratorTools = OrchestratorTools(
            workerAgentRunner = workerAgentRunner,
            participantDescriptions = participantDescriptions,
            context = context,
            maxSteps = config.maxSteps,
            onMonitorEvent = orchestratorEventCallback
        )

        // 解析目标模板
        val resolvedGoal = resolveTemplate(config.goal, context)

        // 构建 Orchestrator 的 system prompt
        val orchestratorDef = config.orchestrator
        val orchestratorParams = orchestratorDef.resolveParameters(workflow.agents).toMutableMap()
        val existingSystemPrompt = (orchestratorParams["system_prompt"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""

        val agentBasedSystemPrompt = buildString {
            if (existingSystemPrompt.isNotBlank()) {
                appendLine(existingSystemPrompt)
                appendLine()
            }
            appendLine("=== Orchestrator Instructions ===")
            appendLine("You are an orchestrator agent responsible for achieving the following goal by delegating tasks to worker agents.")
            appendLine()
            appendLine("Available worker agents:")
            participantDescriptions.forEach { (name, desc) ->
                appendLine("  - $name: $desc")
            }
            appendLine()
            appendLine("Rules:")
            appendLine("1. Use 'delegateTask' to assign work to a specific agent")
            appendLine("2. Use 'delegateParallel' to run tasks on multiple agents simultaneously")
            appendLine("3. Use 'getParticipantInfo' to check available agents and remaining step budget")
            appendLine("4. Use 'setVariable'/'getVariable' to manage shared state")
            appendLine("5. You MUST call 'complete' with a summary when the goal is achieved")
            appendLine("6. You have a maximum of ${config.maxSteps} delegation steps")
            appendLine("7. Be efficient — delegate only necessary tasks, avoid redundant work")
        }

        // 将增强的 system prompt 写入参数
        orchestratorParams["system_prompt"] = kotlinx.serialization.json.JsonPrimitive(agentBasedSystemPrompt)

        val orchestratorSessionId = (orchestratorParams["session_id"] as? JsonPrimitive)
            ?.content
            ?.takeIf { it.isNotBlank() }
            ?: "${context.executionId}:${step.step}:orchestrator"
        injectWorkflowRuntimeParameters(
            target = orchestratorParams,
            sessionId = orchestratorSessionId,
            executionId = context.executionId,
            stepName = step.step,
            directoryIsolation = directoryIsolation,
            agentName = "orchestrator",
            workflowName = workflow.name
        )
        val orchestratorTemplateContext = createStepTemplateContext(
            context = context,
            stepName = step.step,
            agentName = "orchestrator",
            directoryIsolation = directoryIsolation
        )
        orchestratorParams.replaceAll { _, value -> resolveJsonTemplates(value, orchestratorTemplateContext) }
        enforceProtectedRuntimeParameters(orchestratorParams)

        // 构建 Orchestrator Agent 参数
        val parameters = mergeParameters(baseParameters, orchestratorParams)

        // 提取 tool_set（在 Orchestrator 的基础工具之上追加 OrchestratorTools）
        val rawToolNames = parameters.parameter("tool_set", linkedSetOf("exit")).toList()

        val orchestratorHookCallback: com.fartech.agents.commons.MonitoringEventCallback? =
            if (enableMonitoring) {
                { type, summary, detail ->
                    WorkflowMonitor.addEvent(
                        context.executionId,
                        step.step,
                        AgentEvent(
                            type = type,
                            category = "hook",
                            subCategory = "hook",
                            summary = summary,
                            detail = detail
                        )
                    )
                }
            } else null

        // 构建 Orchestrator 的 customToolSets（OrchestratorTools + 共享知识库）
        val orchestratorCustomToolSets = mutableListOf<ai.koog.agents.core.tools.reflect.ToolSet>(orchestratorTools)
        context.sharedKnowledgeBase?.let { orchestratorCustomToolSets.add(it) }

        // 如果有共享知识库且 Orchestrator 工具集中未包含 rag_tools，自动注入
        val orchestratorToolNames = if (context.sharedKnowledgeBase != null && "rag_tools" !in rawToolNames) {
            rawToolNames + "rag_tools"
        } else {
            rawToolNames
        }

        val orchestratorSkillManager = createSkillManager(parameters)
        val orchestratorToolRegistry = com.fartech.agents.commons.parseToolSet(
            parameters,
            httpAccess,
            tools = orchestratorToolNames,
            customToolSets = orchestratorCustomToolSets,
            skillManager = orchestratorSkillManager,
            onHookEvent = orchestratorHookCallback
        )

        // 构建监控 Features
        val monitoringInstallFeatures: ai.koog.agents.core.agent.GraphAIAgent.FeatureContext.() -> Unit =
            if (enableMonitoring) {
                createMonitoringInstallFeatures(
                    context.executionId,
                    step.step,
                    imService = parameters.parameter("im_service", "telegram").lowercase()
                )
            } else {
                defaultInstallFeatures
            }

        // 创建 Orchestrator Agent
        return try {
            val orchestratorAgent = ManagedAgent(
                agent = com.fartech.agents.commons.buildAgent(
                    httpAccess = httpAccess,
                    parameters = parameters,
                    systemPrompt = agentBasedSystemPrompt,
                    toolRegistry = orchestratorToolRegistry,
                    skillManager = orchestratorSkillManager,
                    installFeatures = monitoringInstallFeatures,
                    strategyBuilder = { params, http, toolReg ->
                        com.fartech.agents.commons.determineDefaultStrategy(
                            http,
                            params,
                            toolRegistry = toolReg,
                            skillManager = orchestratorSkillManager
                        )
                    }
                ),
                sessionId = parameters.parameter("session_id", "${context.executionId}:${step.step}:orchestrator"),
                skillManager = orchestratorSkillManager,
                parameters = parameters
            )

            logger.info { "[Workflow:${workflow.name}] Orchestrator agent created for step '${step.step}', running with goal..." }
            val output = runManagedAgent(orchestratorAgent, resolvedGoal)

            // 优先使用 OrchestratorTools.complete() 提供的 summary
            val finalOutput = orchestratorTools.completionSummary ?: output

            if (enableMonitoring) {
                WorkflowMonitor.addEvent(
                    context.executionId, step.step, AgentEvent(
                        type = "agent_based_completed",
                        category = "agent",
                        subCategory = "orchestrator",
                        summary = "🏁 Agent-based 编排完成 (${orchestratorTools.getDelegationLog().size} delegations)",
                        detail = finalOutput.take(500)
                    )
                )
            }

            StepResult(success = true, output = finalOutput)
        } catch (e: OrchestratorBudgetExceededException) {
            logger.warn { "[Workflow:${workflow.name}] Orchestrator budget exceeded for step '${step.step}': ${e.message}" }
            // 预算耗尽时，尝试使用已有的 completion summary 或 delegation log
            val fallbackOutput = orchestratorTools.completionSummary ?: buildString {
                appendLine("Orchestrator budget exceeded. Partial results:")
                orchestratorTools.getDelegationLog().forEach { record ->
                    appendLine("  - [${if (record.success) "OK" else "FAIL"}] ${record.agentName}: ${record.outputPreview ?: record.error ?: "no output"}")
                }
            }
            StepResult(success = true, output = fallbackOutput)
        } catch (e: Exception) {
            logger.error(e) { "[Workflow:${workflow.name}] Agent-based step '${step.step}' failed: ${e.message}" }
            throw WorkflowExecutionException("Agent-based step '${step.step}' failed: ${e.message}", e)
        }
    }

    /**
     * 执行子工作流步骤
     *
     * 黑盒执行模型:
     * - 创建独立的子 [WorkflowExecutionContext](不与父共享 variables / stepOutputs / agents)
     * - 派生 executionId = `${parentExecutionId}/${parentStepName}`
     * - 按 `kb_scope` 决定是否继承父的 sharedKnowledgeBase
     * - 按 `agent_inheritance` 决定是否 merge 父的 agents 到 child
     * - 子执行通过递归调用同一个 executor 的 `runResolvedWorkflow` 完成
     * - 完成后 outputs 映射从 child context 提取并写回父 context.variables
     *
     * 严格契约模式 vs 宽松模式:
     * - child 有 `module` 契约 → 输入 / 输出按契约严格校验,output_format=json 时会序列化整个 contractOutputs
     * - child 无 `module` 契约 → 父手写的 inputs / outputs 直接生效,output 是 child variables 的 JSON 视图
     */
    private suspend fun executeSubWorkflowStep(
        step: WorkflowStep,
        parentWorkflow: WorkflowDefinition,
        parentContext: WorkflowExecutionContext,
        @Suppress("UNUSED_PARAMETER") parentDirIsolation: DirectoryIsolationConfig
    ): StepResult {
        val config = step.subWorkflow
            ?: throw WorkflowExecutionException("sub_workflow config missing for step '${step.step}'")
        val resolver = workflowResolver
            ?: throw WorkflowExecutionException(
                "Step '${step.step}': WorkflowResolver is not configured; cannot execute sub_workflow. " +
                    "Construct WorkflowExecutor with workflowResolver = FileSystemWorkflowResolver(...) or WebWorkflowResolver(...)"
            )

        // 1. 运行时循环检测 + 深度限制 (uses coroutine context for thread-safety)
        val callStack = coroutineContext[SubWorkflowStackElement]?.stack
            ?: error("SubWorkflowStackElement missing from coroutine context")
        val targetIdentity = resolver.identityKey(config.toReference())
        if (targetIdentity in callStack) {
            throw WorkflowExecutionException(
                "Sub-workflow recursion detected: ${(callStack + targetIdentity).joinToString(" → ")}"
            )
        }
        val maxDepth = config.maxDepth
            ?: parentWorkflow.errorHandling?.maxSubWorkflowDepth
            ?: DEFAULT_MAX_SUB_WORKFLOW_DEPTH
        if (callStack.size + 1 > maxDepth) {
            throw WorkflowExecutionException(
                "Sub-workflow recursion exceeds max_depth=$maxDepth at: ${(callStack + targetIdentity).joinToString(" → ")}"
            )
        }

        // 2. 解析 child definition
        val resolved = try {
            resolver.resolve(config.toReference())
        } catch (e: WorkflowValidationException) {
            return handleSubWorkflowMissingReference(step, config, parentContext, e)
        }
        val childDefBase = resolved.definition
        val childModule = childDefBase.module?.takeIf { it.enabled }

        // 3. Agent 继承 merge(默认 none)
        val effectiveChildDef = applyAgentInheritance(childDefBase, parentWorkflow, config.agentInheritance)

        // 4. 解析 inputs(在父上下文中渲染模板)
        val resolvedInputs = config.inputs.mapValues { (_, template) ->
            resolveTemplate(template, parentContext)
        }

        // 5. 构造独立 child context(派生 executionId 保证 monitor / 目录 / session 全局唯一)
        val derivedExecutionId = "${parentContext.executionId}__${sanitizeStepNameForId(step.step)}"
        val childContext = WorkflowExecutionContext(
            workflowName = effectiveChildDef.name,
            executionId = derivedExecutionId,
            // Sub-workflow lineage: track immediate parent + top-most ancestor.
            // Modules running inside sub-workflows usually need the ROOT id
            // (where the user's artifacts live), not their own derived id —
            // so we expose both via env vars in `buildEnv`. For one-level
            // nesting, root == parent. For deeper nesting, root propagates
            // from the grandparent (rootExecutionId is the FIRST top-level
            // execution id in the chain, never re-derived).
            parentExecutionId = parentContext.executionId,
            rootExecutionId = parentContext.rootExecutionId ?: parentContext.executionId,
        )
        // 5a. 先注入 child 默认 variables,再用解析后的 inputs 覆盖(契约严格模式有额外校验)
        childContext.variables.putAll(effectiveChildDef.variables)

        if (childModule != null) {
            // 严格模式: 校验 inputs 名称、required、enum、number 范围
            applyContractInputsToChildContext(
                step = step,
                config = config,
                resolvedInputs = resolvedInputs,
                contract = childModule,
                childContext = childContext
            )
        } else {
            // 宽松模式: 直接 putAll
            for (req in config.requiredInputs) {
                if (resolvedInputs[req].isNullOrBlank()) {
                    throw WorkflowExecutionException(
                        "Step '${step.step}': sub_workflow required input '$req' is missing or blank"
                    )
                }
            }
            childContext.variables.putAll(resolvedInputs)
        }

        // 与顶层 execute() 一致:把 "./" / "../" 变量归一化为绝对路径,
        // 否则 child 以模块方式调用时 koog 文件工具拿到相对路径而失败,
        // 与 standalone 运行同一 child 的行为不一致。
        resolveRelativePathVariables(childContext.variables)

        // 6. KB scope
        when (config.kbScope) {
            SubWorkflowKbScope.INHERIT -> {
                childContext.sharedKnowledgeBase = parentContext.sharedKnowledgeBase
            }

            SubWorkflowKbScope.ISOLATED -> {
                initializeKnowledgeBase(effectiveChildDef, childContext, derivedExecutionId)
            }

            SubWorkflowKbScope.EXTEND -> {
                childContext.sharedKnowledgeBase = parentContext.sharedKnowledgeBase
                // EXTEND 模式:子有自己的 source_files 时,在父 KB 上额外索引(尽力而为)
                effectiveChildDef.knowledgeBase?.takeIf { it.enabled }?.let { _ ->
                    initializeKnowledgeBase(effectiveChildDef, childContext, derivedExecutionId)
                }
            }
        }

        // 7. Monitor: 在父 step 注册 sub_workflow_started 事件
        if (enableMonitoring) {
            WorkflowMonitor.addEvent(
                parentContext.executionId,
                step.step,
                AgentEvent(
                    type = "sub_workflow_started",
                    category = "agent",
                    subCategory = "sub_workflow",
                    summary = "🧩 Sub-workflow 开始: ${effectiveChildDef.name}",
                    detail = "child_workflow=${effectiveChildDef.name}, version=${effectiveChildDef.version}, " +
                        "module=${childModule != null}, inputs=${resolvedInputs.size}, kb_scope=${config.kbScope.name.lowercase()}"
                )
            )
        }

        // 8. Debug entry checkpoint
        awaitDebugCheckpoint(
            point = WorkflowDebugPoint.SUB_WORKFLOW_ENTRY,
            stepName = step.step,
            context = parentContext,
            label = effectiveChildDef.name
        )

        // 9. 切换 currentExecutionContext 并递归执行 child(保存 / 恢复)
        val previousContext = currentExecutionContext
        currentExecutionContext = childContext
        callStack.add(targetIdentity)
        val childResult: WorkflowExecutionResult = try {
            // 递归调用同一个 executor 的 execute(不写 startExecution / completeExecution,
            // 因为子 stepMetrics 共享父的 executionId,通过 addEvent 来记录而不是新建 metrics)
            executeChildWorkflow(
                workflow = effectiveChildDef,
                childContext = childContext,
                parentExecutionId = parentContext.executionId,
                parentStepName = step.step
            )
        } finally {
            currentExecutionContext = previousContext
            callStack.remove(targetIdentity)
        }

        // 10. 处理结果: 提取 outputs 写回父 context
        val resolvedOutputs: Map<String, String> = if (childModule != null) {
            // 严格模式: 自动按 module.outputs 解析所有契约 outputs
            val contractOutputs = childModule.outputs.associate { spec ->
                spec.name to resolveTemplate(spec.source, childContext)
            }
            // 父的 sub_workflow.outputs 是 "父变量名 → 契约 output 名" 的映射,挑选并重命名
            val mappedToParent = mutableMapOf<String, String>()
            for ((parentVar, childOutputName) in config.outputs) {
                val value = if (childOutputName.contains("{{")) {
                    // 兼容旧写法:仍然作为模板在 child context 解析
                    resolveTemplate(childOutputName, childContext)
                } else {
                    contractOutputs[childOutputName]
                        ?: throw WorkflowExecutionException(
                            "Step '${step.step}': sub_workflow output '$childOutputName' not found in module contract outputs"
                        )
                }
                mappedToParent[parentVar] = value
            }
            // 写回父 context.variables
            mappedToParent.forEach { (key, value) -> parentContext.setVariable(key, value) }
            // 父 step 的 output payload 用整个 contractOutputs(便于父 extract.json_path 取任意字段)
            contractOutputs
        } else {
            // 宽松模式: 父的 outputs 直接当作 child 上下文的模板表达式
            val mapped = config.outputs.mapValues { (_, template) ->
                resolveTemplate(template, childContext)
            }
            mapped.forEach { (key, value) -> parentContext.setVariable(key, value) }
            mapped
        }

        // 11. 构造父 step 的 output payload
        val outputPayload = when (config.outputFormat) {
            SubWorkflowOutputFormat.JSON -> buildString {
                append("{")
                resolvedOutputs.entries.forEachIndexed { index, (key, value) ->
                    if (index > 0) append(",")
                    append('"').append(escapeJsonValue(key)).append("\":\"").append(escapeJsonValue(value)).append('"')
                }
                append("}")
            }

            SubWorkflowOutputFormat.TEXT -> resolvedOutputs.values.firstOrNull()
                ?: childResult.stepResults.values.lastOrNull()?.output.orEmpty()
        }

        // 12. Debug exit checkpoint
        awaitDebugCheckpoint(
            point = WorkflowDebugPoint.SUB_WORKFLOW_EXIT,
            stepName = step.step,
            context = parentContext,
            label = effectiveChildDef.name
        )

        // 13. Monitor completed event(含 token 聚合信息)
        if (enableMonitoring) {
            val childMetrics = WorkflowMonitor.getMetrics(derivedExecutionId)
            val tokenSummary = if (childMetrics != null) {
                val input = childMetrics.getInputTokens()
                val output = childMetrics.getOutputTokens()
                val total = childMetrics.getTotalTokens()
                ", child_execution_id=$derivedExecutionId, child_steps=${childMetrics.totalSteps}, " +
                    "input_tokens=$input, output_tokens=$output, total_tokens=$total"
            } else ""

            WorkflowMonitor.addEvent(
                parentContext.executionId,
                step.step,
                AgentEvent(
                    type = "sub_workflow_completed",
                    category = "agent",
                    subCategory = "sub_workflow",
                    summary = "🧩 Sub-workflow 完成: ${effectiveChildDef.name}",
                    detail = "outputs=${resolvedOutputs.size}, duration=${childResult.duration}ms, " +
                        "success=${childResult.success}$tokenSummary",
                    inputTokens = childMetrics?.getInputTokens()?.toInt(),
                    outputTokens = childMetrics?.getOutputTokens()?.toInt(),
                    totalTokens = childMetrics?.getTotalTokens()?.toInt()
                )
            )
        }

        // 14. 返回结果(子失败 → 父 step 失败)
        return if (childResult.success) {
            StepResult(success = true, output = outputPayload)
        } else {
            StepResult(
                success = false,
                output = outputPayload,
                error = "Sub-workflow '${effectiveChildDef.name}' failed: ${childResult.error ?: "unknown error"}"
            )
        }
    }

    /**
     * 把契约里声明的 inputs 应用到 child context,做严格校验。
     */
    private fun applyContractInputsToChildContext(
        step: WorkflowStep,
        config: SubWorkflowConfig,
        resolvedInputs: Map<String, String>,
        contract: WorkflowModuleContract,
        childContext: WorkflowExecutionContext
    ) {
        val contractInputNames = contract.inputs.map { it.name }.toSet()

        // a. 父 inputs key 必须在契约中
        for (key in resolvedInputs.keys) {
            if (key !in contractInputNames) {
                throw WorkflowExecutionException(
                    "Step '${step.step}': sub_workflow input '$key' is not declared in module contract " +
                        "(allowed: ${contractInputNames.sorted()})"
                )
            }
        }

        // b. 应用每个契约 input
        for (spec in contract.inputs) {
            val externalValue = resolvedInputs[spec.name]
            val effectiveValue = when {
                externalValue != null -> externalValue
                spec.default != null -> spec.default
                spec.required -> throw WorkflowExecutionException(
                    "Step '${step.step}': sub_workflow missing required input '${spec.name}'"
                )

                else -> null
            }

            if (effectiveValue != null) {
                // c. 类型校验
                validateContractInputValue(step, spec, effectiveValue)
                // d. 写到 child context.variables(用 mapsToVariable 或 name)
                childContext.setVariable(spec.variableKey(), effectiveValue)
            }
        }
    }

    /**
     * 校验单个契约 input 的值合法性
     */
    private fun validateContractInputValue(step: WorkflowStep, spec: ModuleInputSpec, value: String) {
        when (spec.type) {
            "enum" -> {
                val allowed = spec.allowedValues.orEmpty()
                if (allowed.isNotEmpty() && value !in allowed) {
                    throw WorkflowExecutionException(
                        "Step '${step.step}': sub_workflow input '${spec.name}'='$value' is not in allowed_values $allowed"
                    )
                }
            }

            "number" -> {
                val num = value.toDoubleOrNull()
                    ?: throw WorkflowExecutionException(
                        "Step '${step.step}': sub_workflow input '${spec.name}' expects type=number but got '$value'"
                    )
                spec.min?.let {
                    if (num < it) throw WorkflowExecutionException(
                        "Step '${step.step}': sub_workflow input '${spec.name}'=$num is below min=$it"
                    )
                }
                spec.max?.let {
                    if (num > it) throw WorkflowExecutionException(
                        "Step '${step.step}': sub_workflow input '${spec.name}'=$num is above max=$it"
                    )
                }
            }

            "boolean" -> {
                if (value.lowercase() !in setOf("true", "false")) {
                    throw WorkflowExecutionException(
                        "Step '${step.step}': sub_workflow input '${spec.name}' expects type=boolean but got '$value'"
                    )
                }
            }
            // path / string / json / list 不做严格类型检查(运行时由 child 自己处理)
        }
    }

    /**
     * 把父 workflow 的 agents 按 agent_inheritance 策略 merge 到 child workflow 的 agents 上。
     * Child 的同名 agent 优先(覆盖)。
     */
    private fun applyAgentInheritance(
        childDef: WorkflowDefinition,
        parentWorkflow: WorkflowDefinition,
        inheritance: SubWorkflowAgentInheritanceConfig?
    ): WorkflowDefinition {
        val cfg = inheritance ?: return childDef
        if (cfg.mode == SubWorkflowAgentInheritanceMode.NONE) return childDef

        val parentAgents: Map<String, AgentDefinition> = when (cfg.mode) {
            SubWorkflowAgentInheritanceMode.INHERIT_ALL -> parentWorkflow.agents
            SubWorkflowAgentInheritanceMode.INHERIT_NAMED -> parentWorkflow.agents.filterKeys { it in cfg.inheritNamed }
            SubWorkflowAgentInheritanceMode.NONE -> emptyMap()
        }
        if (parentAgents.isEmpty()) return childDef

        // child 优先,所以先 putAll(parent),再 putAll(child)
        val merged = LinkedHashMap<String, AgentDefinition>()
        merged.putAll(parentAgents)
        merged.putAll(childDef.agents)
        return childDef.copy(agents = merged)
    }

    /**
     * 处理 child 引用解析失败(missing_reference_policy 决定如何降级)
     */
    private fun handleSubWorkflowMissingReference(
        step: WorkflowStep,
        config: SubWorkflowConfig,
        parentContext: WorkflowExecutionContext,
        e: WorkflowValidationException
    ): StepResult {
        return when (config.missingReferencePolicy) {
            SubWorkflowMissingReferencePolicy.ERROR -> throw WorkflowExecutionException(
                "Step '${step.step}': sub_workflow reference (${config.identityLabel()}) cannot be resolved: ${e.message}",
                e
            )

            SubWorkflowMissingReferencePolicy.WARN -> {
                logger.warn(e) {
                    "[Workflow:${parentContext.workflowName}] Step '${step.step}' sub_workflow reference cannot be resolved (warn policy)"
                }
                if (enableMonitoring) {
                    WorkflowMonitor.addEvent(
                        parentContext.executionId, step.step, AgentEvent(
                            type = "sub_workflow_warn",
                            category = "agent",
                            subCategory = "sub_workflow",
                            summary = "⚠️ Sub-workflow 引用解析失败 (warn): ${config.identityLabel()}",
                            detail = e.message
                        )
                    )
                }
                StepResult(success = true, output = "{\"warn\":\"sub_workflow reference unresolved\"}")
            }

            SubWorkflowMissingReferencePolicy.SKIP_STEP -> {
                logger.warn(e) {
                    "[Workflow:${parentContext.workflowName}] Step '${step.step}' sub_workflow reference cannot be resolved, skipping"
                }
                if (enableMonitoring) {
                    WorkflowMonitor.addEvent(
                        parentContext.executionId, step.step, AgentEvent(
                            type = "sub_workflow_skipped",
                            category = "agent",
                            subCategory = "sub_workflow",
                            summary = "⏭️ Sub-workflow 引用未找到,跳过: ${config.identityLabel()}",
                            detail = e.message
                        )
                    )
                }
                parentContext.skippedSteps.add(step.step)
                StepResult(success = true, output = "{}")
            }
        }
    }

    /**
     * 内部 helper:执行子工作流的核心逻辑(共用同一 executor 但用独立 context 和独立 monitor 实例)。
     *
     * 与 [execute] 的区别:
     * - 接受外部传入的 childContext(已经预填好 inputs / KB)
     * - 不重置 currentExecutionContext(由调用方管理)
     *
     * 子 stepMetrics 写到 child 自己的 WorkflowMetrics(派生 executionId),token 总数会在父 step 的
     * `sub_workflow_completed` 事件的 detail 字段里聚合显示。UI 可以通过派生 executionId 单独查询
     * child 的 step 树。
     */
    private suspend fun executeChildWorkflow(
        workflow: WorkflowDefinition,
        childContext: WorkflowExecutionContext,
        parentExecutionId: String,
        parentStepName: String
    ): WorkflowExecutionResult = coroutineScope {
        val derivedExecutionId = childContext.executionId
        val startTime = System.currentTimeMillis()

        // 启动独立的 child WorkflowMetrics
        if (enableMonitoring) {
            WorkflowMonitor.startExecution(derivedExecutionId, workflow.name, workflow.workflow.size)
        }

        try {
            val continueOnError = workflow.errorHandling?.continueOnError == true
            val transitionTriggers = buildIncomingTransitionTriggers(workflow)
            val workflowTotalTimeoutMs = parseConfiguredTimeoutMillis(
                workflow.timeout?.total,
                "sub-workflow '${workflow.name}' total timeout"
            )

            suspend fun runBody() {
                // 子工作流不开 DAG 并发,避免 ThreadLocal stack 在不同协程间漂移
                executeWorkflowSequentially(
                    workflow = workflow,
                    context = childContext,
                    continueOnError = continueOnError,
                    executionId = derivedExecutionId,
                    transitionTriggers = transitionTriggers
                )
            }

            // Same deadline mechanism as the top-level execute(): a raw withTimeout
            // here would hard-cancel in-flight manual_approval waits inside the
            // child — exactly what [ExecutionDeadline] exists to avoid. The check
            // fires at each step boundary in executeWorkflowSequentially.
            if (workflowTotalTimeoutMs != null) {
                executionDeadlines[derivedExecutionId] =
                    ExecutionDeadline(startTime, workflowTotalTimeoutMs)
            }
            try {
                runBody()
            } finally {
                executionDeadlines.remove(derivedExecutionId)
            }

            val endTime = System.currentTimeMillis()
            // 同样从 child stepResults 推导真实成功状态(对齐顶层 execute() 的语义),
            // 排除已被 on_failure / errorHandling.onError 官方恢复的失败。
            val unrecoveredFailures = childContext.stepResults.values
                .filter { !it.success && it.stepName !in childContext.recoveredFailures }
            val derivedSuccess = unrecoveredFailures.isEmpty()
            val derivedError = if (!derivedSuccess) {
                val firstError = unrecoveredFailures
                    .mapNotNull { it.error?.takeIf { msg -> msg.isNotBlank() } }
                    .firstOrNull()
                    ?: "${unrecoveredFailures.size} child step(s) failed"
                if (unrecoveredFailures.size > 1) {
                    "${unrecoveredFailures.size} child steps failed; first error: $firstError"
                } else {
                    firstError
                }
            } else {
                null
            }
            if (enableMonitoring) {
                WorkflowMonitor.completeExecution(derivedExecutionId, success = derivedSuccess)
            }
            WorkflowExecutionResult(
                workflowName = workflow.name,
                success = derivedSuccess,
                startTime = startTime,
                endTime = endTime,
                duration = endTime - startTime,
                error = derivedError,
                stepResults = childContext.stepResults,
                variables = childContext.variables,
                skippedSteps = childContext.skippedSteps.toSet(),
                recoveredFailures = childContext.recoveredFailures.toSet()
            )
        } catch (e: TimeoutCancellationException) {
            val endTime = System.currentTimeMillis()
            if (enableMonitoring) {
                WorkflowMonitor.completeExecution(derivedExecutionId, success = false)
            }
            WorkflowExecutionResult(
                workflowName = workflow.name,
                success = false,
                startTime = startTime,
                endTime = endTime,
                duration = endTime - startTime,
                error = "Sub-workflow timed out",
                stepResults = childContext.stepResults,
                variables = childContext.variables,
                skippedSteps = childContext.skippedSteps.toSet(),
                recoveredFailures = childContext.recoveredFailures.toSet()
            )
        } catch (e: CancellationException) {
            if (enableMonitoring) {
                WorkflowMonitor.cancelExecution(derivedExecutionId)
            }
            throw e
        } catch (e: Exception) {
            val endTime = System.currentTimeMillis()
            if (enableMonitoring) {
                WorkflowMonitor.completeExecution(derivedExecutionId, success = false)
            }
            WorkflowExecutionResult(
                workflowName = workflow.name,
                success = false,
                startTime = startTime,
                endTime = endTime,
                duration = endTime - startTime,
                error = e.message ?: "Unknown sub-workflow error",
                stepResults = childContext.stepResults,
                variables = childContext.variables,
                skippedSteps = childContext.skippedSteps.toSet(),
                recoveredFailures = childContext.recoveredFailures.toSet()
            )
        }
    }

    /**
     * 把 step 名转换成 executionId 安全的字符串(去掉 / 等可能影响目录路径的字符)。
     */
    private fun sanitizeStepNameForId(stepName: String): String =
        stepName.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    /**
     * 重试执行
     */
    private suspend fun executeWithRetry(
        step: WorkflowStep,
        workflow: WorkflowDefinition,
        context: WorkflowExecutionContext,
        manageMonitoring: Boolean = true
    ): StepExecutionResult {
        val retry = step.retry
            ?: throw WorkflowExecutionException("retry configuration missing for step '${step.step}'")
        var lastError: Throwable? = null
        val monitoringEnabled = enableMonitoring && manageMonitoring

        if (monitoringEnabled) {
            WorkflowMonitor.startStep(context.executionId, step.step, agentName = step.displayAgentName)
        }

        repeat(retry.maxAttempts) { attempt ->
            try {
                logger.info { "[Workflow:${workflow.name}] Retry attempt ${attempt + 1}/${retry.maxAttempts} for step '${step.step}'" }

                if (enableMonitoring) {
                    WorkflowMonitor.addEvent(
                        context.executionId, step.step, AgentEvent(
                            type = "retry_attempt_started",
                            category = "agent",
                            subCategory = "retry",
                            summary = "🔁 重试第 ${attempt + 1}/${retry.maxAttempts} 次尝试"
                        )
                    )
                }

                if (enableMonitoring && attempt > 0) {
                    WorkflowMonitor.recordRetry(context.executionId, step.step)
                }

                val result = executeStepOnce(step, workflow, context, manageMonitoring = false)
                if (result.success) {
                    if (monitoringEnabled) {
                        WorkflowMonitor.completeStep(
                            context.executionId,
                            step.step,
                            success = true,
                            output = result.output
                        )
                    }
                    return result.copy(retryCount = attempt)
                }

            } catch (e: Exception) {
                lastError = e

                if (attempt < retry.maxAttempts - 1) {
                    val backoffDelay = calculateBackoffDelay(retry, attempt)
                    logger.warn { "[Workflow:${workflow.name}] Step '${step.step}' failed, retrying after ${backoffDelay}ms..." }
                    if (enableMonitoring) {
                        WorkflowMonitor.addEvent(
                            context.executionId, step.step, AgentEvent(
                                type = "retry_backoff",
                                category = "agent",
                                subCategory = "retry",
                                summary = "⏳ ${backoffDelay}ms 后重试",
                                detail = e.message
                            )
                        )
                    }
                    delay(backoffDelay.milliseconds)
                }
            }
        }

        if (monitoringEnabled) {
            WorkflowMonitor.completeStep(
                context.executionId,
                step.step,
                success = false,
                error = "Step '${step.step}' failed after ${retry.maxAttempts} attempts"
            )
        }

        throw WorkflowExecutionException(
            "Step '${step.step}' failed after ${retry.maxAttempts} attempts",
            lastError
        )
    }

    /**
     * 计算退避延迟
     */
    private fun calculateBackoffDelay(retry: RetryConfig, attempt: Int): Long {
        return when (retry.backoff) {
            BackoffStrategy.CONSTANT -> retry.initialDelay
            BackoffStrategy.LINEAR -> minOf(retry.initialDelay * (attempt + 1), retry.maxDelay)
            BackoffStrategy.EXPONENTIAL -> minOf(retry.initialDelay * (1 shl attempt), retry.maxDelay)
        }
    }

    /**
     * 处理转换动作
     */
    private suspend fun handleTransitionActions(
        sourceStep: String,
        actions: List<TransitionAction>,
        workflow: WorkflowDefinition,
        context: WorkflowExecutionContext,
        sourceLabel: String
    ): TransitionHandlingResult {
        val activatedTargets = linkedSetOf<String>()
        var rollbackExecuted = false

        actions.forEach { action ->
            // 处理通知
            action.notify?.let { notifyChannel ->
                logger.info { "[Workflow:${workflow.name}] Notify from $sourceLabel: $notifyChannel - ${action.message}" }
                // 这里可以集成 InstantMessagingTools
            }

            // 处理停止
            if (action.stop) {
                return TransitionHandlingResult(
                    shouldStop = true,
                    activatedTargets = activatedTargets,
                    rollbackExecuted = rollbackExecuted
                )
            }

            action.next?.let { targetStep ->
                activateTransitionTarget(targetStep, sourceStep, workflow, context)
                activatedTargets += targetStep
            }

            action.parallel.orEmpty().forEach { targetStep ->
                activateTransitionTarget(targetStep, sourceStep, workflow, context)
                activatedTargets += targetStep
            }

            // 处理回滚：重新执行指定的步骤
            action.rollback?.let { rollbackStep ->
                logger.warn { "[Workflow:${workflow.name}] Rollback from $sourceLabel: re-executing step '$rollbackStep'" }
                val rollbackStepDef = workflow.workflow.find { it.step == rollbackStep }
                if (rollbackStepDef != null) {
                    try {
                        executeStepOnce(rollbackStepDef, workflow, context)
                        rollbackExecuted = true
                    } catch (e: Exception) {
                        logger.error(e) { "[Workflow:${workflow.name}] Rollback step '$rollbackStep' failed: ${e.message}" }
                    }
                } else {
                    logger.error { "[Workflow:${workflow.name}] Rollback step '$rollbackStep' not found in workflow" }
                }
            }
        }

        return TransitionHandlingResult(
            activatedTargets = activatedTargets,
            rollbackExecuted = rollbackExecuted
        )
    }

    private suspend fun handleTransitions(
        step: WorkflowStep,
        result: StepExecutionResult,
        workflow: WorkflowDefinition,
        context: WorkflowExecutionContext
    ): TransitionHandlingResult {
        val actions = if (result.success) step.onSuccess else step.onFailure
        return handleTransitionActions(
            sourceStep = step.step,
            actions = actions,
            workflow = workflow,
            context = context,
            sourceLabel = "step '${step.step}' ${if (result.success) "success" else "failure"}"
        )
    }

    private suspend fun handleWorkflowErrorTransitions(
        failedStep: WorkflowStep,
        workflow: WorkflowDefinition,
        context: WorkflowExecutionContext
    ): TransitionHandlingResult {
        val actions = workflow.errorHandling?.onError.orEmpty()
        return handleTransitionActions(
            sourceStep = failedStep.step,
            actions = actions,
            workflow = workflow,
            context = context,
            sourceLabel = "workflow on_error after step '${failedStep.step}' failure"
        )
    }

    private fun activateTransitionTarget(
        targetStep: String,
        sourceStep: String,
        workflow: WorkflowDefinition,
        context: WorkflowExecutionContext
    ) {
        context.activatedTransitionSteps.add(targetStep)
        val sources = context.transitionActivationSources.computeIfAbsent(targetStep) {
            Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        }
        sources.add(sourceStep)
        logger.info {
            "[Workflow:${workflow.name}] Activated transition target '$targetStep' from step '$sourceStep'"
        }
    }

    /**
     * 检查依赖是否成功
     *
     * 依赖步骤必须满足以下条件之一：
     * 1. 执行成功（stepResults 中 success == true）
     * 2. 因条件不满足被跳过（在 skippedSteps 中且不在 stepResults 中）
     * 跳过的步骤视为"不阻塞"，因为它们是设计上可选的。
     */
    private fun checkDependenciesSucceeded(
        dependencies: List<String>,
        context: WorkflowExecutionContext
    ): Boolean {
        return dependencies.all { dep ->
            val result = context.stepResults[dep]
            when {
                result?.success == true -> true
                result != null -> false  // 执行了但失败
                dep in context.skippedSteps -> true  // 因条件跳过，不阻塞
                else -> false  // 未执行
            }
        }
    }

    /**
     * 评估条件表达式
     */
    private fun evaluateCondition(
        condition: String?,
        context: WorkflowExecutionContext
    ): Boolean {
        val result = evaluateWorkflowCondition(condition, context, fallbackToStepOutput = true)
        if (!result && condition != null) {
            logger.debug { "Condition evaluated to false: $condition" }
        }
        return result
    }

    /**
     * 将变量中的相对路径解析为绝对路径。
     *
     * koog 框架的文件工具（__write_file__、__list_directory__ 等）要求绝对路径，
     * 但工作流 YAML 中的变量可能使用相对路径（如 "./output"）。
     * 当这些变量被替换到 LLM 提示中后，LLM 会原样传给工具导致失败。
     *
     * 此方法检测以 "./" 或 "../" 开头的变量值并将其转为绝对路径。
     */
    private fun resolveRelativePathVariables(variables: MutableMap<String, Any>) {
        for ((key, value) in variables) {
            val str = value.toString()
            if (str.startsWith("./") || str.startsWith("../")) {
                val absolutePath = File(str).absolutePath
                variables[key] = absolutePath
                logger.info { "[PathResolve] Variable '$key' resolved from '$str' to '$absolutePath'" }
            }
        }
    }

    /**
     * Resolve workflow template placeholders in [template].
     *
     * Delegates to the shared [WorkflowTemplateResolver]. Applies the executor-specific
     * `output_dir` rewrite after the generic substitution so `{{var:output_dir}}` and
     * legacy absolute-path literals are both normalized.
     */
    private fun resolveTemplate(
        template: String,
        context: WorkflowExecutionContext
    ): String {
        val resolved = WorkflowTemplateResolver.resolve(template, context)
        return rewriteSharedOutputPathLiterals(resolved, context.variables["output_dir"]?.toString())
    }

    private fun rewriteSharedOutputPathLiterals(
        text: String,
        outputDir: String?
    ): String {
        if (outputDir.isNullOrBlank()) return text

        val absoluteOutputDir = runCatching {
            File(outputDir).takeIf { it.isAbsolute }?.absoluteFile?.toPath()?.normalize()?.toString()
        }.getOrNull() ?: return text

        return text.replace(OUTPUT_PATH_REGEX) {
            absoluteOutputDir
        }
    }

    /**
     * 为步骤模板注入当前真实目录变量。
     *
     * 这样 YAML step input 可以直接使用：
     * - {{var:working_dir}}
     * - {{var:output_dir}}
     * - {{var:persistence_storage_root}}
     *
     * 避免 LLM 只能从系统上下文文字里“理解”工作目录，导致把仓库根目录误当成 workspace。
     */
    private fun createStepTemplateContext(
        context: WorkflowExecutionContext,
        stepName: String,
        agentName: String?,
        directoryIsolation: DirectoryIsolationConfig,
        skillsDirOverride: String? = null
    ): WorkflowExecutionContext {
        val safeStepName = stepName.ifBlank { "default" }
        val safeAgentName = agentName?.ifBlank { "default" } ?: "default"
        val safeWorkflowName = context.workflowName.ifBlank { "default" }
        val userId = baseParameters.parameter("user_id", "default")

        val variables = context.variables.toMutableMap().apply {
            val skillsDir = skillsDirOverride
                ?.takeIf { it.isNotBlank() }
                ?: get("skills_dir")?.toString()?.takeIf { it.isNotBlank() }
                ?: get("shared_skills_dir")?.toString()?.takeIf { it.isNotBlank() }
                ?: directoryIsolation.sharedSkillsDir
                    .takeIf { it.isNotBlank() }
                    ?.let { rawPath ->
                        runCatching { File(rawPath).canonicalPath }.getOrElse { File(rawPath).absolutePath }
                    }
            if (skillsDir != null) {
                put("shared_skills_dir", skillsDir)
                put("skills_dir", skillsDir)
            }
            put(
                "file_cache_storage",
                runCatching { File(directoryIsolation.sharedCacheDir).canonicalPath }
                    .getOrElse { File(directoryIsolation.sharedCacheDir).absolutePath }
            )
            put(
                "history_storage_root",
                runCatching { File(directoryIsolation.sharedHistoryDir).canonicalPath }
                    .getOrElse { File(directoryIsolation.sharedHistoryDir).absolutePath }
            )

            if (!directoryIsolation.enabled) {
                return@apply
            }
            put(
                "working_dir",
                File(
                    directoryIsolation.getWorkingDir(
                        context.executionId,
                        safeStepName,
                        safeAgentName,
                        safeWorkflowName,
                        userId
                    )
                ).absolutePath
            )
            put(
                "output_dir",
                File(
                    directoryIsolation.getOutputDir(
                        context.executionId,
                        safeStepName,
                        safeAgentName,
                        safeWorkflowName,
                        userId
                    )
                ).absolutePath
            )
            put(
                "persistence_storage_root",
                File(
                    directoryIsolation.getPersistenceDir(
                        context.executionId,
                        safeStepName,
                        safeAgentName,
                        safeWorkflowName,
                        userId
                    )
                ).absolutePath
            )
            put("output_dir_abs", getValue("output_dir"))
        }

        return context.copy(
            variables = variables,
            stepOutputs = context.stepOutputs,
            stepResults = context.stepResults,
            skippedSteps = context.skippedSteps
        ).also { it.sharedKnowledgeBase = context.sharedKnowledgeBase }
    }

    /**
     * 获取拓扑排序结果，同层级内按优先级降序排列
     *
     * 在同一拓扑层中（入度相同、依赖都已满足的步骤），按 priority 从高到低排序。
     * 这样既保证依赖顺序，又在允许范围内让高优先级步骤先执行。
     */
    private fun getTopologicalOrderWithPriority(workflow: WorkflowDefinition): List<WorkflowStep> {
        val stepMap = workflow.workflow.associateBy { it.step }
        val inDegree = mutableMapOf<String, Int>()
        workflow.workflow.forEach { step -> inDegree[step.step] = step.dependsOn.size }

        // Pre-build reverse dependency map: stepName → list of steps that depend on it
        val dependents = mutableMapOf<String, MutableList<WorkflowStep>>()
        workflow.workflow.forEach { step ->
            step.dependsOn.forEach { dep ->
                dependents.getOrPut(dep) { mutableListOf() }.add(step)
            }
        }

        // Use PriorityQueue for O(log n) insertion vs O(n log n) full sort on every iteration
        val available = java.util.PriorityQueue<WorkflowStep>(
            maxOf(1, workflow.workflow.size),
            compareByDescending { it.priority }
        )
        inDegree.forEach { (name, degree) ->
            if (degree == 0) available.add(stepMap[name] ?: error("Step '$name' not found in workflow"))
        }

        val sorted = mutableListOf<WorkflowStep>()
        while (available.isNotEmpty()) {
            val current = available.poll()
            sorted.add(current)

            // O(1) lookup for dependents instead of scanning all steps
            dependents[current.step]?.forEach { step ->
                inDegree[step.step] = (inDegree[step.step] ?: 1) - 1
                if (inDegree[step.step] == 0) {
                    available.add(step)
                }
            }
        }

        if (sorted.size != workflow.workflow.size) {
            throw WorkflowExecutionException("Unable to determine execution order - circular dependency detected")
        }

        return sorted
    }

    /**
     * 获取拓扑分层结果，每一层包含所有依赖已满足的步骤（可并发执行）
     *
     * 同层内按 priority 降序排列。
     * 返回 List<List<WorkflowStep>>，外层 List 的每个元素是一个拓扑层。
     */
    internal fun getTopologicalLayers(workflow: WorkflowDefinition): List<List<WorkflowStep>> {
        val stepMap = workflow.workflow.associateBy { it.step }
        val inDegree = mutableMapOf<String, Int>()
        workflow.workflow.forEach { s -> inDegree[s.step] = s.dependsOn.size }

        // Pre-build reverse dependency map
        val dependents = mutableMapOf<String, MutableList<WorkflowStep>>()
        workflow.workflow.forEach { step ->
            step.dependsOn.forEach { dep ->
                dependents.getOrPut(dep) { mutableListOf() }.add(step)
            }
        }

        // 初始可用步骤（入度为 0）
        var currentLayer = mutableListOf<WorkflowStep>()
        inDegree.forEach { (name, degree) ->
            if (degree == 0) currentLayer.add(stepMap[name] ?: error("Step '$name' not found in workflow"))
        }

        val layers = mutableListOf<List<WorkflowStep>>()
        val processed = mutableSetOf<String>()

        while (currentLayer.isNotEmpty()) {
            // 同层内按优先级降序排列
            currentLayer.sortByDescending { it.priority }
            layers.add(currentLayer.toList())
            processed.addAll(currentLayer.map { it.step })

            val nextLayer = mutableListOf<WorkflowStep>()
            val nextLayerNames = mutableSetOf<String>()
            for (completed in currentLayer) {
                dependents[completed.step]?.forEach { s ->
                    inDegree[s.step] = (inDegree[s.step] ?: 1) - 1
                    if (inDegree[s.step] == 0 && s.step !in processed && nextLayerNames.add(s.step)) {
                        nextLayer.add(s)
                    }
                }
            }
            currentLayer = nextLayer
        }

        val totalProcessed = layers.sumOf { it.size }
        if (totalProcessed != workflow.workflow.size) {
            throw WorkflowExecutionException("Unable to determine execution order - circular dependency detected")
        }

        return layers
    }

    private suspend fun handleStepExecutionFailure(
        step: WorkflowStep,
        workflow: WorkflowDefinition,
        context: WorkflowExecutionContext,
        continueOnError: Boolean,
        failure: Exception
    ): Boolean {
        val endTime = System.currentTimeMillis()
        val failedResult = context.stepResults[step.step] ?: StepExecutionResult(
            stepName = step.step,
            agentName = step.displayAgentName,
            success = false,
            startTime = endTime,
            endTime = endTime,
            duration = 0,
            error = failure.message
        ).also { context.stepResults[step.step] = it }

        val stepTransitionResult = handleTransitions(step, failedResult, workflow, context)
        val workflowTransitionResult = handleWorkflowErrorTransitions(step, workflow, context)
        val transitionResult = stepTransitionResult.merge(workflowTransitionResult)
        if (transitionResult.shouldStop) {
            logger.info { "[Workflow:${workflow.name}] Workflow stopped by failed step '${step.step}'" }
            return true
        }

        if (transitionResult.hasRecoveryPath) {
            // 标记为"已被官方恢复路径处理过的失败" —— 这种失败不会导致整体 result.success 变为 false,
            // 因为用户在工作流定义里显式声明了恢复策略(on_failure / errorHandling.onError)。
            context.recoveredFailures.add(step.step)
            logger.info {
                "[Workflow:${workflow.name}] Step '${step.step}' failed but recovery path is configured; continuing execution"
            }
            return false
        }

        if (continueOnError) {
            // 注意:仅靠 continueOnError 救回的失败不算"已恢复",
            // 因为用户没有为这次失败声明明确的处理逻辑。整体 result.success 应该被标为 false,
            // 这样 CLI exit code、监控和上层 UI 才不会把它误报成完全成功。
            logger.warn {
                "[Workflow:${workflow.name}] Step '${step.step}' failed but continuing (continueOnError=true): ${failure.message}"
            }
            return false
        }
        throw failure
    }

    /**
     * 执行单个步骤并处理错误和转换动作（供并发执行模式使用）
     *
     * @return true 表示工作流应该停止，false 表示继续
     */
    private suspend fun executeStepWithErrorHandling(
        step: WorkflowStep,
        workflow: WorkflowDefinition,
        context: WorkflowExecutionContext,
        continueOnError: Boolean,
        executionId: String
    ): Boolean {
        val stepResult = try {
            executeStep(step, workflow, context)
        } catch (e: TimeoutCancellationException) {
            return handleStepExecutionFailure(
                step = step,
                workflow = workflow,
                context = context,
                continueOnError = continueOnError,
                failure = WorkflowExecutionException(buildStepTimeoutMessage(step, workflow), e)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return handleStepExecutionFailure(
                step = step,
                workflow = workflow,
                context = context,
                continueOnError = continueOnError,
                failure = e
            )
        }

        val transitionResult = handleTransitions(step, stepResult, workflow, context)
        // Non-throwing failure path (e.g. sub_workflow returned StepResult(success=false))
        // must honour on_failure recovery too — otherwise on_failure.next only works for
        // exceptions, and a sub_workflow failure with an explicit on_failure transition
        // still counts as "unrecovered" and flips the overall workflow result to failed.
        if (!stepResult.success && transitionResult.hasRecoveryPath) {
            context.recoveredFailures.add(step.step)
            logger.info {
                "[Workflow:${workflow.name}] Step '${step.step}' returned failed but recovery path is configured; treating as recovered"
            }
        }
        if (transitionResult.shouldStop) {
            logger.info { "[Workflow:${workflow.name}] Workflow stopped by step '${step.step}'" }
            return true
        }
        return false
    }

    /**
     * 为每次步骤执行创建新的 Agent 实例
     *
     * koog 框架的 Agent 是 StatefulSingleUseAIAgent，run() 只能调用一次，
     * 因此每次步骤执行都必须创建新的实例，不能缓存复用。
     *
     * 根据 AgentDefinition.sessionIdStrategy 生成唯一 session_id，避免多 agent 冲突：
     * - "auto"（默认）：{executionId}:{agentName}:{stepName}，完全隔离
     * - "per_execution"：{executionId}:{agentName}，同一 agent 在同一执行中共享
     * - "per_agent"：{workflowName}:{agentName}，跨执行共享（长期记忆）
     * - "fixed"：使用 AgentDefinition.sessionId 的固定值
     */
    private suspend fun createManagedAgentForStep(
        agentName: String,
        agentDef: AgentDefinition,
        context: WorkflowExecutionContext,
        stepName: String,
        directoryIsolation: DirectoryIsolationConfig = DirectoryIsolationConfig()
    ): ManagedAgent {
        val presetInfo = if (agentDef.preset != null) "preset: ${agentDef.preset}" else "legacy mode"
        val resolvedSessionId = resolveSessionId(agentDef, context, agentName, stepName)
        logger.info { "Creating agent '$agentName' ($presetInfo) with session_id='$resolvedSessionId'" }
        return createAgent(
            agentDef,
            resolvedSessionId,
            context,
            context.executionId,
            stepName,
            directoryIsolation,
            agentName,
            context.workflowName,
            sharedRagTools = context.sharedKnowledgeBase
        )
    }

    /**
     * 根据 session_id_strategy 解析最终的 session_id
     */
    internal fun resolveSessionId(
        agentDef: AgentDefinition,
        context: WorkflowExecutionContext,
        agentName: String,
        stepName: String
    ): String {
        // 优先从 resolvedParameters 获取策略和 session_id（支持 preset overrides）
        val resolvedParams = agentDef.resolveParameters()
        val strategy = (resolvedParams["session_id_strategy"] as? JsonPrimitive)?.content
            ?: agentDef.sessionIdStrategy
            ?: "auto"
        val fixedSessionId = (resolvedParams["session_id"] as? JsonPrimitive)?.content
            ?: agentDef.sessionId

        return when (strategy) {
            "fixed" -> fixedSessionId ?: "${context.workflowName}:${agentName}"
            "per_agent" -> "${context.workflowName}:${agentName}"
            "per_execution" -> "${context.executionId}:${agentName}"
            else -> "${context.executionId}:${agentName}:${stepName}" // "auto" or unknown
        }
    }

    /**
     * 创建 Agent 实例
     *
     * 使用 AgentDefinition.resolveParameters() 将预设+覆盖合并为最终参数，
     * 然后转换为 ConfigurationParameter 列表传给 buildAgent。
     * 支持预设模式和向后兼容的旧字段模式。
     *
     * 当目录隔离启用时，自动注入 per-execution 隔离的目录路径：
     * - working_dir: per-execution + per-step 隔离
     * - output_dir: per-execution 隔离
     * - persistence_storage_root: per-execution 隔离
     * - file_cache_storage: 全局共享（基于内容 hash）
     * - history_storage_root: 全局共享（已通过 session_id 隔离）
     *
     * @param agentDef Agent 定义
     * @param sessionId 由 resolveSessionId 生成的唯一 session ID
     * @param directoryIsolation 目录隔离配置
     * @param agentName Agent 名称（用于路径模板占位符）
     * @param workflowName 工作流名称（用于路径模板占位符）
     */
    private suspend fun prepareAgentRuntime(
        agentDef: AgentDefinition,
        sessionId: String,
        context: WorkflowExecutionContext? = null,
        executionId: String? = null,
        stepName: String? = null,
        directoryIsolation: DirectoryIsolationConfig = DirectoryIsolationConfig(),
        agentName: String? = null,
        workflowName: String? = null,
        sharedRagTools: RAGTools? = null
    ): PreparedAgentRuntime {
        // 解析最终参数（预设 + 覆盖 或 向后兼容字段）
        val resolvedParams = agentDef.resolveParameters().toMutableMap()
        normalizeRuntimeDirectoryAliases(resolvedParams)
        injectWorkflowRuntimeParameters(
            target = resolvedParams,
            sessionId = sessionId,
            executionId = executionId,
            stepName = stepName,
            directoryIsolation = directoryIsolation,
            agentName = agentName,
            workflowName = workflowName
        )
        val materializedSkillsRuntime = prepareMaterializedSkillsRuntime(
            target = resolvedParams,
            executionId = executionId,
            stepName = stepName,
            directoryIsolation = directoryIsolation,
            agentName = agentName,
            workflowName = workflowName
        )

        // 允许 agent preset overrides 使用工作流模板变量，例如：
        // im_telegram_bot_token: "{{var:telegram_bot_token}}"
        // working_dir: "{{var:working_dir}}"
        context?.let { workflowContext ->
            val templateContext = createStepTemplateContext(
                context = workflowContext,
                stepName = stepName ?: "default",
                agentName = agentName,
                directoryIsolation = directoryIsolation,
                skillsDirOverride = materializedSkillsRuntime?.primarySkillsDir
            )
            resolvedParams.replaceAll { _, value -> resolveJsonTemplates(value, templateContext) }
        }
        enforceProtectedRuntimeParameters(resolvedParams)

        val parameters = mergeParameters(baseParameters, resolvedParams)
        val systemPrompt = parameters.parameter("system_prompt", "")
        val rawToolNames = parameters.parameter("tool_set", emptyList<String>().toMutableSet()).toList()
        val skillsDisabled = skillsSubsystemDisabled(parameters)

        // Skills 是第一公民：如果 tool_set 非空但不含 skill_tools，自动注入
        val toolNames = when {
            skillsDisabled -> rawToolNames.filterNot { it == "skill_tools" }
            rawToolNames.isNotEmpty() && "skill_tools" !in rawToolNames -> {
                logger.info { "Auto-injecting 'skill_tools' into tool_set for agent '$agentName' (skills are first-class citizens)" }
                rawToolNames + "skill_tools"
            }
            else -> rawToolNames
        }

        val skillEventCallback: MonitoringEventCallback? =
            if (enableMonitoring && executionId != null && stepName != null) {
                { type, summary, detail ->
                    WorkflowMonitor.addEvent(
                        executionId,
                        stepName,
                        AgentEvent(
                            type = type,
                            category = "tool",
                            subCategory = "skill",
                            summary = summary,
                            detail = detail
                        )
                    )
                }
            } else null

        val subAgentEventCallback: MonitoringEventCallback? =
            if (enableMonitoring && executionId != null && stepName != null) {
                { type, summary, detail ->
                    WorkflowMonitor.addEvent(
                        executionId,
                        stepName,
                        AgentEvent(
                            type = type,
                            category = "agent",
                            subCategory = "sub_agent",
                            summary = summary,
                            detail = detail
                        )
                    )
                }
            } else null

        val reasoningCallback: ReasoningMonitorCallback? =
            if (enableMonitoring && executionId != null && stepName != null) {
                { reasoning ->
                    val reasoningText = reasoning.trim().takeIf { it.isNotEmpty() }
                    // 一些 reasoning 类模型 (Kimi / DeepSeek-R1 等经 OpenRouter
                    // 转发) 会把"我接下来要调的工具"以 {tool_call_id, tool_name,
                    // tool_args} 的 JSON 信封塞进 reasoning 通道。这段内容不
                    // 是真正的"思考"——它和紧随其后的 tool_call_starting 事件
                    // 内容完全重复,在 UI 的 推理链 → 思考 区里显示就成了同一
                    // 段 JSON 出现两次。这里跳过,真正的散文式推理仍会上报。
                    if (reasoningText != null && !isToolCallEnvelopeJson(reasoningText)) {
                        WorkflowMonitor.addEvent(
                            executionId,
                            stepName,
                            AgentEvent(
                                type = "reasoning_message",
                                category = "llm",
                                subCategory = "reasoning",
                                summary = buildSummaryWithPreview("💭 模型推理", reasoningText),
                                detail = truncateDetail(reasoningText)
                            )
                        )
                    }
                }
            } else null

        val hookEventCallback: MonitoringEventCallback? =
            if (enableMonitoring && executionId != null && stepName != null) {
                { type, summary, detail ->
                    WorkflowMonitor.addEvent(
                        executionId,
                        stepName,
                        AgentEvent(
                            type = type,
                            category = "hook",
                            subCategory = "hook",
                            summary = summary,
                            detail = detail
                        )
                    )
                }
            } else null

        val userProgressNotifier: UserProgressNotifier? = createUserProgressNotifier(parameters, executionId, stepName)
        val skillManager = createSkillManager(parameters)

        // 构建监控事件收集的 installFeatures
        val monitoringInstallFeatures: GraphAIAgent.FeatureContext.() -> Unit =
            if (enableMonitoring && executionId != null && stepName != null) {
                createMonitoringInstallFeatures(
                    executionId,
                    stepName,
                    userProgressNotifier,
                    parameters.parameter("im_service", "telegram").lowercase()
                )
            } else {
                defaultInstallFeatures
            }

        // 技能子系统初始化事件现在由 SkillTools.init 自动发出（包含加载的技能数量和名称）
        // 不再需要在此处发出静态事件

        // 用户交互处理器：当 tool_set 包含 "user_interaction" 且启用监控时创建
        val userInteractionHandler: com.fartech.agents.tools.UserInteractionHandler? =
            if (enableMonitoring && executionId != null && stepName != null &&
                toolNames.contains("user_interaction")) {
                createUserInteractionHandler(executionId, stepName)
            } else null

        // 如果有共享知识库，将其作为 customToolSet 注入（避免重复创建 RAGTools 实例）
        val customToolSets = if (sharedRagTools != null) {
            listOf(sharedRagTools)
        } else {
            emptyList()
        }

        // 如果有共享知识库且工具集中未显式声明 rag_tools，自动注入
        val finalToolNames = if (sharedRagTools != null && "rag_tools" !in toolNames) {
            logger.info { "Auto-injecting 'rag_tools' for agent '$agentName' (shared knowledge base enabled)" }
            toolNames + "rag_tools"
        } else {
            toolNames
        }

        val toolRegistry = parseToolSet(
            parameters,
            httpAccess,
            tools = finalToolNames,
            customToolSets = customToolSets,
            skillManager = skillManager,
            onSkillEvent = skillEventCallback,
            onSubAgentEvent = subAgentEventCallback,
            onHookEvent = hookEventCallback,
            userInteractionHandler = userInteractionHandler
        )

        return PreparedAgentRuntime(
            sessionId = parameters.parameter("session_id", sessionId),
            parameters = parameters,
            systemPrompt = systemPrompt,
            toolRegistry = toolRegistry,
            skillManager = skillManager,
            installFeatures = monitoringInstallFeatures,
            reasoningCallback = reasoningCallback
        )
    }

    private suspend fun createAgent(
        agentDef: AgentDefinition,
        sessionId: String,
        context: WorkflowExecutionContext? = null,
        executionId: String? = null,
        stepName: String? = null,
        directoryIsolation: DirectoryIsolationConfig = DirectoryIsolationConfig(),
        agentName: String? = null,
        workflowName: String? = null,
        sharedRagTools: RAGTools? = null
    ): ManagedAgent {
        val runtime = prepareAgentRuntime(
            agentDef = agentDef,
            sessionId = sessionId,
            context = context,
            executionId = executionId,
            stepName = stepName,
            directoryIsolation = directoryIsolation,
            agentName = agentName,
            workflowName = workflowName,
            sharedRagTools = sharedRagTools
        )

        return buildAgent(
            httpAccess = httpAccess,
            parameters = runtime.parameters,
            systemPrompt = runtime.systemPrompt,
            toolRegistry = runtime.toolRegistry,
            skillManager = runtime.skillManager,
            installFeatures = runtime.installFeatures,
            strategyBuilder = { params, http, toolRegistry ->
                determineDefaultStrategy(
                    http,
                    params,
                    toolRegistry = toolRegistry,
                    skillManager = runtime.skillManager,
                    onReasoningMessage = runtime.reasoningCallback,
                )
            }
        ).let { agent ->
            ManagedAgent(
                agent = agent,
                sessionId = runtime.sessionId,
                skillManager = runtime.skillManager,
                parameters = runtime.parameters
            )
        }
    }

    private fun mergeParameters(
        base: List<ConfigurationParameter>,
        overrides: Map<String, JsonElement>
    ): MutableList<ConfigurationParameter> {
        val merged = LinkedHashMap<String, ConfigurationParameter>()
        base.forEach { merged[it.key] = it }
        overrides.forEach { (key, value) ->
            merged[key] = ConfigurationParameter(key, value)
        }
        return merged.values.toMutableList()
    }

    private fun prepareMaterializedSkillsRuntime(
        target: MutableMap<String, JsonElement>,
        executionId: String?,
        stepName: String?,
        directoryIsolation: DirectoryIsolationConfig,
        agentName: String?,
        workflowName: String?
    ): MaterializedSkillsRuntime? {
        if (!directoryIsolation.enabled || executionId.isNullOrBlank()) return null

        val toolNames = extractToolNames(target["tool_set"])
        val sourceConfig = decodeSkillsConfiguration(target["skills_config"])
            ?: if ("skill_tools" in toolNames) {
                SkillsConfiguration(
                    skillsPath = target["skills_dir"]?.jsonPrimitive?.contentOrNull
                        ?: target["shared_skills_dir"]?.jsonPrimitive?.contentOrNull
                        ?: directoryIsolation.sharedSkillsDir
                )
            } else {
                return null
            }

        if (!sourceConfig.enabled || !sourceConfig.materializeRuntimeSkills) return null

        val materialized = materializeSkillsConfigForExecution(
            config = sourceConfig,
            executionId = executionId,
            stepName = stepName,
            directoryIsolation = directoryIsolation,
            agentName = agentName,
            workflowName = workflowName
        )

        target["skills_config"] = workflowJson.encodeToJsonElement(
            SkillsConfiguration.serializer(),
            materialized.config
        )
        materialized.primarySkillsDir?.let { stagedDir ->
            target["shared_skills_dir"] = JsonPrimitive(stagedDir)
            target["skills_dir"] = JsonPrimitive(stagedDir)
        }
        return materialized
    }

    private fun decodeSkillsConfiguration(element: JsonElement?): SkillsConfiguration? {
        val obj = element as? JsonObject ?: return null
        return runCatching {
            workflowJson.decodeFromJsonElement(SkillsConfiguration.serializer(), obj)
        }.getOrNull()
    }

    private fun extractToolNames(element: JsonElement?): List<String> = when (element) {
        is JsonArray -> element.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }.filter { it.isNotEmpty() }
        is JsonPrimitive -> element.contentOrNull
            ?.split(',', ';')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        else -> emptyList()
    }

    private fun materializeSkillsConfigForExecution(
        config: SkillsConfiguration,
        executionId: String,
        stepName: String?,
        directoryIsolation: DirectoryIsolationConfig,
        agentName: String?,
        workflowName: String?
    ): MaterializedSkillsRuntime {
        val userId = baseParameters.parameter("user_id", "default")
        val safeStepName = stepName?.ifBlank { "default" } ?: "default"
        val safeAgentName = agentName?.ifBlank { "default" } ?: "default"
        val safeWorkflowName = workflowName?.ifBlank { "default" } ?: "default"
        val executionRoot = File(
            directoryIsolation.getOutputDir(
                executionId,
                safeStepName,
                safeAgentName,
                safeWorkflowName,
                userId
            )
        ).absoluteFile.parentFile ?: File(directoryIsolation.baseDir).absoluteFile
        val runtimeRoot = File(executionRoot, ".skills-runtime")
        ensureWorkflowRuntimeDirectory(runtimeRoot, relaxDockerPermissions = isDockerSubprocessMode())

        val stagedPrimaryDir = materializeSkillDirectory(
            sourcePath = config.skillsPath,
            destinationRoot = runtimeRoot,
            directoryKey = "configured"
        )
        val stagedAdditionalDirs = config.additionalSkillPaths.mapIndexedNotNull { index, path ->
            materializeSkillDirectory(
                sourcePath = path,
                destinationRoot = runtimeRoot,
                directoryKey = "additional-$index"
            )
        }

        val rewrittenConfig = config.copy(
            skillsPath = stagedPrimaryDir ?: config.skillsPath,
            additionalSkillPaths = stagedAdditionalDirs,
            scanStandardPaths = false
        )

        logger.info {
            "[SkillMaterialize] execution=$executionId, primary=${stagedPrimaryDir ?: "unchanged"}, " +
                "additional=${stagedAdditionalDirs.size}, scanStandardPaths->false"
        }

        return MaterializedSkillsRuntime(
            config = rewrittenConfig,
            primarySkillsDir = stagedPrimaryDir
        )
    }

    private fun materializeSkillDirectory(
        sourcePath: String,
        destinationRoot: File,
        directoryKey: String
    ): String? {
        val sourceDir = sourcePath
            .takeIf { it.isNotBlank() && !it.startsWith("classpath:", ignoreCase = true) }
            ?.let { File(it) }
            ?.let { file -> runCatching { file.canonicalFile }.getOrElse { file.absoluteFile } }
            ?: return null

        if (!sourceDir.exists() || !sourceDir.isDirectory) {
            logger.warn { "[SkillMaterialize] Skip non-directory skills path: ${sourceDir.absolutePath}" }
            return null
        }

        val suffix = sanitizePathSegment(
            sourceDir.name.ifBlank {
                sourceDir.absolutePath.hashCode().toUInt().toString(16)
            }
        )
        val destinationDir = File(destinationRoot, "$directoryKey-$suffix")
        val materializationLock = skillMaterializationLocks[
            floorMod(destinationDir.absolutePath.hashCode(), skillMaterializationLocks.size)
        ]
        synchronized(materializationLock) {
            if (isMaterializedSkillDirectoryReady(destinationDir, sourceDir)) {
                ensureWorkflowRuntimeDirectory(destinationDir, relaxDockerPermissions = isDockerSubprocessMode())
                return destinationDir.absolutePath
            }

            val tempDir = File(
                destinationRoot,
                "${destinationDir.name}.tmp-${UUID.randomUUID().toString().take(8)}"
            )
            if (tempDir.exists()) {
                tempDir.deleteRecursively()
            }
            sourceDir.copyRecursively(tempDir, overwrite = true)
            writeMaterializedSkillsMarker(tempDir, sourceDir)
            if (destinationDir.exists()) {
                destinationDir.deleteRecursively()
            }
            runCatching {
                Files.move(
                    tempDir.toPath(),
                    destinationDir.toPath(),
                    StandardCopyOption.ATOMIC_MOVE
                )
            }.recoverCatching {
                Files.move(
                    tempDir.toPath(),
                    destinationDir.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }.getOrElse { error ->
                tempDir.deleteRecursively()
                throw error
            }
        }
        ensureWorkflowRuntimeDirectory(destinationDir, relaxDockerPermissions = isDockerSubprocessMode())
        return destinationDir.absolutePath
    }

    private fun isMaterializedSkillDirectoryReady(
        destinationDir: File,
        sourceDir: File
    ): Boolean {
        if (!destinationDir.exists() || !destinationDir.isDirectory) return false
        val markerFile = File(destinationDir, MATERIALIZED_SKILLS_MARKER)
        if (!markerFile.isFile) return false
        val marker = runCatching {
            workflowJson.decodeFromString(MaterializedSkillsMarker.serializer(), markerFile.readText())
        }.getOrNull() ?: return false
        return marker.sourcePath == sourceDir.absolutePath
    }

    private fun writeMaterializedSkillsMarker(
        destinationDir: File,
        sourceDir: File
    ) {
        val marker = MaterializedSkillsMarker(sourcePath = sourceDir.absolutePath)
        File(destinationDir, MATERIALIZED_SKILLS_MARKER).writeText(
            workflowJson.encodeToString(MaterializedSkillsMarker.serializer(), marker)
        )
    }

    private fun sanitizePathSegment(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "skills" }

    private fun floorMod(value: Int, mod: Int): Int {
        val remainder = value % mod
        return if (remainder >= 0) remainder else remainder + mod
    }

    private fun injectWorkflowRuntimeParameters(
        target: MutableMap<String, JsonElement>,
        sessionId: String,
        executionId: String?,
        stepName: String?,
        directoryIsolation: DirectoryIsolationConfig,
        agentName: String? = null,
        workflowName: String? = null
    ) {
        target["session_id"] = JsonPrimitive(sessionId)
        executionId?.let { target["execution_id"] = JsonPrimitive(it) }
        stepName?.takeIf { it.isNotBlank() }?.let { target["step_name"] = JsonPrimitive(it) }
        val userId = baseParameters.parameter("user_id", "default")
        directoryIsolation.sharedSkillsDir
            .takeIf { it.isNotBlank() }
            ?.let { rawPath ->
                runCatching { File(rawPath).canonicalPath }.getOrElse { File(rawPath).absolutePath }
            }
            ?.let {
                target["shared_skills_dir"] = JsonPrimitive(it)
                target["skills_dir"] = JsonPrimitive(it)
            }
        val cacheDir = runCatching { File(directoryIsolation.sharedCacheDir).canonicalFile }
            .getOrElse { File(directoryIsolation.sharedCacheDir).absoluteFile }
        val historyDir = runCatching { File(directoryIsolation.sharedHistoryDir).canonicalFile }
            .getOrElse { File(directoryIsolation.sharedHistoryDir).absoluteFile }
        target["file_cache_storage"] = JsonPrimitive(cacheDir.path)
        target["history_storage_root"] = JsonPrimitive(historyDir.path)
        if (isDockerSubprocessMode()) {
            ensureWorkflowRuntimeDirectory(cacheDir, relaxDockerPermissions = true)
            ensureWorkflowRuntimeDirectory(historyDir, relaxDockerPermissions = true)
        }

        if (!directoryIsolation.enabled || executionId == null) {
            return
        }

        val safeStepName = stepName ?: "default"
        val safeAgentName = agentName ?: "default"
        val safeWorkflowName = workflowName ?: "default"

        // 所有路径必须转为绝对路径，因为 koog 框架的文件工具要求绝对路径。
        val workingDir = File(
            directoryIsolation.getWorkingDir(executionId, safeStepName, safeAgentName, safeWorkflowName, userId)
        ).absolutePath
        val outputDir = File(
            directoryIsolation.getOutputDir(executionId, safeStepName, safeAgentName, safeWorkflowName, userId)
        ).canonicalPath
        val persistenceDir = File(
            directoryIsolation.getPersistenceDir(executionId, safeStepName, safeAgentName, safeWorkflowName, userId)
        ).canonicalPath

        if (target["working_dir"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
            target["working_dir"] = JsonPrimitive(workingDir)
        }
        target["output_dir"] = JsonPrimitive(outputDir)
        target["persistence_storage_root"] = JsonPrimitive(persistenceDir)

        listOf(workingDir, outputDir, persistenceDir).forEach { dir ->
            ensureWorkflowRuntimeDirectory(
                File(dir),
                relaxDockerPermissions = isDockerSubprocessMode()
            )
        }

        logger.info {
            "[DirIsolation] Agent '$safeAgentName' step '$safeStepName': " +
                "working=$workingDir, output=$outputDir, persistence=$persistenceDir"
        }
    }

    private fun normalizeRuntimeDirectoryAliases(target: MutableMap<String, JsonElement>) {
        val workingDir = target["working_dir"]?.jsonPrimitive?.contentOrNull
        val workspaceDir = target["workspace_dir"]?.jsonPrimitive?.contentOrNull
        if (workingDir.isNullOrBlank() && !workspaceDir.isNullOrBlank()) {
            target["working_dir"] = JsonPrimitive(workspaceDir)
        }
    }

    private fun enforceProtectedRuntimeParameters(target: MutableMap<String, JsonElement>) {
        PROTECTED_RUNTIME_PARAMETER_KEYS.forEach { key ->
            baseParameters.firstOrNull { it.key == key }?.value?.let { target[key] = it }
        }
    }

    private fun resolveJsonTemplates(
        element: JsonElement,
        context: WorkflowExecutionContext
    ): JsonElement = when (element) {
        is JsonPrimitive -> {
            if (element.isString) JsonPrimitive(resolveTemplate(element.content, context)) else element
        }
        is JsonObject -> JsonObject(element.mapValues { (_, value) -> resolveJsonTemplates(value, context) })
        is JsonArray -> JsonArray(element.map { value -> resolveJsonTemplates(value, context) })
    }

    private suspend fun <T> withManagedAgent(
        managedAgent: ManagedAgent,
        block: suspend (AIAgent<String, String>) -> T
    ): T {
        try {
            return block(managedAgent.agent)
        } finally {
            try {
                managedAgent.agent.close()
            } catch (closeError: Exception) {
                logger.warn(closeError) { "Failed to close managed workflow agent (session_id=${managedAgent.sessionId})" }
            }

            try {
                managedAgent.skillManager?.dispatchHooks(BraidrunHookEvent.SESSION_END, managedAgent.sessionId)
            } catch (hookError: Exception) {
                logger.warn(hookError) { "Failed to dispatch SESSION_END hook (session_id=${managedAgent.sessionId})" }
            }
        }
    }

    private suspend fun runManagedAgent(
        managedAgent: ManagedAgent,
        input: String
    ): String {
        val inputStr = input.trim()

        saveHistoryMessage(managedAgent.parameters, "user", input)
        managedAgent.skillManager?.dispatchHooks(
            BraidrunHookEvent.MESSAGE,
            managedAgent.sessionId,
            mapOf("content" to inputStr, "direction" to "in")
        )
        managedAgent.skillManager?.dispatchHooks(
            BraidrunHookEvent.MESSAGE_RECEIVED,
            managedAgent.sessionId,
            mapOf("content" to inputStr)
        )
        managedAgent.skillManager?.dispatchHooks(
            BraidrunHookEvent.MESSAGE_TRANSCRIBED,
            managedAgent.sessionId,
            mapOf("content" to inputStr)
        )
        managedAgent.skillManager?.dispatchHooks(
            BraidrunHookEvent.MESSAGE_PREPROCESSED,
            managedAgent.sessionId,
            mapOf("content" to inputStr)
        )

        if (inputStr.startsWith("/")) {
            val command = inputStr.substringBefore(" ").substring(1)
            managedAgent.skillManager?.dispatchHooks(
                BraidrunHookEvent.COMMAND,
                managedAgent.sessionId,
                mapOf("command" to command)
            )
            when (command.lowercase()) {
                "new" -> managedAgent.skillManager?.dispatchHooks(BraidrunHookEvent.COMMAND_NEW, managedAgent.sessionId)
                "reset" -> managedAgent.skillManager?.dispatchHooks(BraidrunHookEvent.COMMAND_RESET, managedAgent.sessionId)
                "stop" -> managedAgent.skillManager?.dispatchHooks(BraidrunHookEvent.COMMAND_STOP, managedAgent.sessionId)
            }
        }

        return try {
            val output = withManagedAgent(managedAgent) { agent -> agent.run(input) }
            saveHistoryMessage(managedAgent.parameters, "assistant", output)
            managedAgent.skillManager?.dispatchHooks(
                BraidrunHookEvent.MESSAGE,
                managedAgent.sessionId,
                mapOf("content" to output, "direction" to "out")
            )
            managedAgent.skillManager?.dispatchHooks(
                BraidrunHookEvent.MESSAGE_SENT,
                managedAgent.sessionId,
                mapOf("content" to output)
            )
            output
        } catch (e: Exception) {
            managedAgent.skillManager?.dispatchHooks(
                BraidrunHookEvent.AGENT_ERROR,
                managedAgent.sessionId,
                mapOf("error" to (e.message ?: e.toString()))
            )
            throw e
        }
    }

    private suspend fun runStepAgent(
        agentName: String,
        agentDef: AgentDefinition,
        context: WorkflowExecutionContext,
        stepName: String,
        input: String,
        directoryIsolation: DirectoryIsolationConfig = DirectoryIsolationConfig()
    ): String {
        if (agentDef.isClaudeCodeAgent()) {
            return runClaudeCodeAgentStep(
                agentName = agentName,
                agentDef = agentDef,
                context = context,
                stepName = stepName,
                input = input,
                directoryIsolation = directoryIsolation
            )
        }

        if (agentDef.isCodexAgent()) {
            return runCodexAgentStep(
                agentName = agentName,
                agentDef = agentDef,
                context = context,
                stepName = stepName,
                input = input,
                directoryIsolation = directoryIsolation
            )
        }

        val managedAgent = createManagedAgentForStep(
            agentName = agentName,
            agentDef = agentDef,
            context = context,
            stepName = stepName,
            directoryIsolation = directoryIsolation
        )
        val output = runManagedAgent(managedAgent, input)
        return enrichAgentOutputWithExitToolPayload(context.executionId, stepName, output)
    }

    private suspend fun runClaudeCodeAgentStep(
        agentName: String,
        agentDef: AgentDefinition,
        context: WorkflowExecutionContext,
        stepName: String,
        input: String,
        directoryIsolation: DirectoryIsolationConfig = DirectoryIsolationConfig()
    ): String {
        val executor = codeStepExecutor
            ?: throw WorkflowExecutionException(
                "Claude Code Agent '$agentName' requires a SubprocessExecutor. " +
                    "Configure codeStepExecutor / subprocess_mode for this runtime."
            )

        val sessionId = resolveSessionId(agentDef, context, agentName, stepName)
        val resolvedParams = resolveRuntimeParametersForDirectAgent(
            agentDef = agentDef,
            sessionId = sessionId,
            context = context,
            executionId = context.executionId,
            stepName = stepName,
            directoryIsolation = directoryIsolation,
            agentName = agentName,
            workflowName = context.workflowName
        )
        val parameters = mergeParameters(baseParameters, resolvedParams)
        val workingDir = parameters.parameter("working_dir", "").trim().takeIf { it.isNotEmpty() }?.let(::File)
        val outputDir = parameters.parameter(HOST_OUTPUT_DIR_RUNTIME_KEY, "").trim().takeIf { it.isNotEmpty() }?.let(::File)
            ?: parameters.parameter("output_dir", "").trim().takeIf { it.isNotEmpty() }?.let(::File)
        val skillsDir = parameters.parameter("skills_dir", "").trim().takeIf { it.isNotEmpty() }?.let(::File)

        val eventCallback: MonitoringEventCallback? =
            if (enableMonitoring) {
                { type, summary, detail ->
                    WorkflowMonitor.addEvent(
                        context.executionId,
                        stepName,
                        AgentEvent(
                            type = type,
                            category = "agent",
                            subCategory = "claude_code_agent",
                            summary = summary,
                            detail = detail,
                            inputTokens = parseLongFromEventDetail(detail, "input_tokens")?.toInt(),
                            outputTokens = parseLongFromEventDetail(detail, "output_tokens")?.toInt()
                        )
                    )
                }
            } else null

        val claudeAgent = ExternalAgentTools(
            executor = executor,
            parameters = parameters,
            userId = baseParameters.parameter("user_id", "default"),
            context = SubprocessToolContext(
                workspaceDir = workingDir,
                outputDir = outputDir,
                skillsDir = skillsDir,
                executionId = context.executionId,
                stepName = stepName,
                sessionId = sessionId
            ),
            onMonitorEvent = eventCallback
        )

        return claudeAgent.runClaudeCodeSubAgent(
            ExternalAgentContext(
                prompt = input,
                name = agentName,
                allowedTools = parseExternalAgentListParameter(parameters, "external_agent_claude_allowed_tools"),
                disallowedTools = parseExternalAgentListParameter(parameters, "external_agent_claude_disallowed_tools"),
                workingDir = workingDir?.absolutePath,
                systemPrompt = parameters.parameter("system_prompt", ""),
                maxTurns = parameters.parameter("external_agent_claude_max_turns", 32),
                timeoutSeconds = parameters.parameter("external_agent_claude_timeout_seconds", 1800),
                model = ""
            )
        )
    }

    private suspend fun runCodexAgentStep(
        agentName: String,
        agentDef: AgentDefinition,
        context: WorkflowExecutionContext,
        stepName: String,
        input: String,
        directoryIsolation: DirectoryIsolationConfig = DirectoryIsolationConfig()
    ): String {
        val executor = codeStepExecutor
            ?: throw WorkflowExecutionException(
                "Codex Agent '$agentName' requires a SubprocessExecutor. " +
                    "Configure codeStepExecutor / subprocess_mode for this runtime."
            )

        val sessionId = resolveSessionId(agentDef, context, agentName, stepName)
        val resolvedParams = resolveRuntimeParametersForDirectAgent(
            agentDef = agentDef,
            sessionId = sessionId,
            context = context,
            executionId = context.executionId,
            stepName = stepName,
            directoryIsolation = directoryIsolation,
            agentName = agentName,
            workflowName = context.workflowName
        )
        val parameters = mergeParameters(baseParameters, resolvedParams)
        val workingDir = parameters.parameter("working_dir", "").trim().takeIf { it.isNotEmpty() }?.let(::File)
        val outputDir = parameters.parameter(HOST_OUTPUT_DIR_RUNTIME_KEY, "").trim().takeIf { it.isNotEmpty() }?.let(::File)
            ?: parameters.parameter("output_dir", "").trim().takeIf { it.isNotEmpty() }?.let(::File)
        val skillsDir = parameters.parameter("skills_dir", "").trim().takeIf { it.isNotEmpty() }?.let(::File)

        val eventCallback: MonitoringEventCallback? =
            if (enableMonitoring) {
                { type, summary, detail ->
                    WorkflowMonitor.addEvent(
                        context.executionId,
                        stepName,
                        AgentEvent(
                            type = type,
                            category = "agent",
                            subCategory = "codex_agent",
                            summary = summary,
                            detail = detail,
                            inputTokens = parseLongFromEventDetail(detail, "input_tokens")?.toInt(),
                            outputTokens = parseLongFromEventDetail(detail, "output_tokens")?.toInt()
                        )
                    )
                }
            } else null

        val codexAgent = ExternalAgentTools(
            executor = executor,
            parameters = parameters,
            userId = baseParameters.parameter("user_id", "default"),
            context = SubprocessToolContext(
                workspaceDir = workingDir,
                outputDir = outputDir,
                skillsDir = skillsDir,
                executionId = context.executionId,
                stepName = stepName,
                sessionId = sessionId
            ),
            onMonitorEvent = eventCallback
        )

        // Codex `exec` has no --append-system-prompt; fold the system prompt into the
        // prompt so the field stays meaningful for a Codex Agent step.
        val systemPrompt = parameters.parameter("system_prompt", "").trim()
        val effectivePrompt = if (systemPrompt.isEmpty()) input else "$systemPrompt\n\n$input"

        return codexAgent.runCodexSubAgent(
            ExternalAgentContext(
                prompt = effectivePrompt,
                name = agentName,
                workingDir = workingDir?.absolutePath,
                maxTurns = parameters.parameter("external_agent_codex_max_turns", 32),
                timeoutSeconds = parameters.parameter("external_agent_codex_timeout_seconds", 1800),
                model = ""
            )
        )
    }

    private fun AgentDefinition.isClaudeCodeAgent(): Boolean {
        val resolvedType = resolveParameters()["type"]?.jsonPrimitive?.contentOrNull ?: type
        return resolvedType == "claude_code_agent"
    }

    private fun AgentDefinition.isCodexAgent(): Boolean {
        val resolvedType = resolveParameters()["type"]?.jsonPrimitive?.contentOrNull ?: type
        return resolvedType == "codex_agent"
    }

    private fun resolveRuntimeParametersForDirectAgent(
        agentDef: AgentDefinition,
        sessionId: String,
        context: WorkflowExecutionContext,
        executionId: String?,
        stepName: String?,
        directoryIsolation: DirectoryIsolationConfig,
        agentName: String?,
        workflowName: String?
    ): MutableMap<String, JsonElement> {
        val resolvedParams = agentDef.resolveParameters().toMutableMap()
        normalizeRuntimeDirectoryAliases(resolvedParams)
        injectWorkflowRuntimeParameters(
            target = resolvedParams,
            sessionId = sessionId,
            executionId = executionId,
            stepName = stepName,
            directoryIsolation = directoryIsolation,
            agentName = agentName,
            workflowName = workflowName
        )
        context.let { workflowContext ->
            val templateContext = createStepTemplateContext(
                context = workflowContext,
                stepName = stepName ?: "default",
                agentName = agentName,
                directoryIsolation = directoryIsolation
            )
            resolvedParams.replaceAll { _, value -> resolveJsonTemplates(value, templateContext) }
        }
        enforceProtectedRuntimeParameters(resolvedParams)
        rewriteDirectAgentRuntimePathsForDocker(resolvedParams)
        return resolvedParams
    }

    private fun rewriteDirectAgentRuntimePathsForDocker(resolvedParams: MutableMap<String, JsonElement>) {
        if (!isDockerSubprocessMode()) return

        val rawHostOutputDir = resolvedParams["output_dir"]?.jsonPrimitive?.content.orEmpty()
        if (rawHostOutputDir.isBlank()) return

        val resolvedHostOutputDir = runCatching { File(rawHostOutputDir).canonicalFile.path }
            .getOrElse { rawHostOutputDir }
        resolvedParams[HOST_OUTPUT_DIR_RUNTIME_KEY] = JsonPrimitive(resolvedHostOutputDir)
        resolvedParams["output_dir"] = JsonPrimitive(CONTAINER_OUTPUT_DIR)
        resolvedParams["output_dir_abs"] = JsonPrimitive(CONTAINER_OUTPUT_DIR)

        resolvedParams.replaceAll { key, value ->
            rewriteRuntimePathValueByKey(key, value, resolvedHostOutputDir, rawHostOutputDir)
        }
    }

    private fun rewriteRuntimePathValueByKey(
        key: String,
        value: JsonElement,
        resolvedHostOutputDir: String,
        rawHostOutputDir: String
    ): JsonElement = when (value) {
        is JsonArray -> JsonArray(value.map { rewriteRuntimePathValueByKey(key, it, resolvedHostOutputDir, rawHostOutputDir) })
        is JsonObject -> JsonObject(
            value.mapValues { (nestedKey, nestedValue) ->
                rewriteRuntimePathValueByKey(
                    nestedKey,
                    nestedValue,
                    resolvedHostOutputDir,
                    rawHostOutputDir
                )
            }
        )
        is JsonPrimitive -> {
            if (!value.isString || value.content.isBlank() || key !in setOf("dataset_path", "write_to")) {
                value
            } else {
                JsonPrimitive(rewriteDirectAgentPathValue(value.content, resolvedHostOutputDir, rawHostOutputDir))
            }
        }
    }

    private fun rewriteDirectAgentPathValue(
        value: String,
        resolvedHostOutputDir: String,
        rawHostOutputDir: String
    ): String {
        if (value == resolvedHostOutputDir || value == rawHostOutputDir) return CONTAINER_OUTPUT_DIR

        val hostPrefixes = listOfNotNull(
            rawHostOutputDir.takeIf { it.isNotBlank() },
            resolvedHostOutputDir.takeIf { it.isNotBlank() }
        ).flatMap { base ->
            listOf("$base/", "$base\\")
        }

        for (hostPrefix in hostPrefixes) {
            if (!value.startsWith(hostPrefix)) continue
            val suffix = value.substring(hostPrefix.length)
            return when {
                hostPrefix.endsWith("\\") -> "$CONTAINER_OUTPUT_DIR/$suffix"
                else -> CONTAINER_OUTPUT_DIR + "/$suffix"
            }
        }

        return value
    }

    private fun parseExternalAgentListParameter(
        parameters: List<ConfigurationParameter>,
        key: String
    ): List<String> {
        val raw = parameters.parameter(key, "").trim()
        if (raw.isBlank()) return emptyList()
        return raw.split(',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun parseLongFromEventDetail(detail: String?, key: String): Long? {
        if (detail.isNullOrBlank()) return null
        return Regex("""(?:^|,\s*)${Regex.escape(key)}=([0-9]+)""")
            .find(detail)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
    }

    private suspend fun runStepAgentWithStructuredOutput(
        agentName: String,
        agentDef: AgentDefinition,
        context: WorkflowExecutionContext,
        stepName: String,
        input: String,
        structuredOutput: StructuredOutputConfig,
        directoryIsolation: DirectoryIsolationConfig = DirectoryIsolationConfig()
    ): String {
        if (agentDef.isClaudeCodeAgent() || agentDef.isCodexAgent()) {
            return runStepAgent(
                agentName = agentName,
                agentDef = agentDef,
                context = context,
                stepName = stepName,
                input = input,
                directoryIsolation = directoryIsolation
            )
        }

        return when (normalizeStructuredOutputSchema(structuredOutput.schema)) {
            "ai_commentary_parts", "ai_commentary_parts_v1", "periodic_ai_commentary_parts" ->
                runTypedStructuredStep<AiCommentaryStructuredOutput>(
                    agentName = agentName,
                    agentDef = agentDef,
                    context = context,
                    stepName = stepName,
                    input = input,
                    structuredOutput = structuredOutput,
                    directoryIsolation = directoryIsolation,
                    isEmpty = { it.isBusinessEmpty() },
                    isUsable = { it.hasRequiredBusinessCoverage() },
                    qualityScore = { it.businessScore() },
                    toJsonElement = { it.toAiCommentaryPartsJson() },
                    structureExamples = listOf(AI_COMMENTARY_PARTS_EXAMPLE),
                    looseParser = ::parseAiCommentaryStructuredOutputLoose
                )

            else -> throw WorkflowExecutionException(
                "Unsupported structured_output.schema '${structuredOutput.schema}' for step '$stepName'"
            )
        }
    }

    private suspend inline fun <reified Output> runTypedStructuredStep(
        agentName: String,
        agentDef: AgentDefinition,
        context: WorkflowExecutionContext,
        stepName: String,
        input: String,
        structuredOutput: StructuredOutputConfig,
        directoryIsolation: DirectoryIsolationConfig = DirectoryIsolationConfig(),
        noinline isEmpty: (Output) -> Boolean,
        noinline isUsable: (Output) -> Boolean = { !isEmpty(it) },
        noinline qualityScore: (Output) -> Int = { if (isEmpty(it)) 0 else 1 },
        noinline toJsonElement: (Output) -> JsonElement,
        structureExamples: List<Output> = emptyList(),
        noinline looseParser: ((String) -> Output?)? = null
    ): String {
        val sessionId = resolveSessionId(agentDef, context, agentName, stepName)
        val runtime = prepareAgentRuntime(
            agentDef = agentDef,
            sessionId = sessionId,
            context = context,
            executionId = context.executionId,
            stepName = stepName,
            directoryIsolation = directoryIsolation,
            agentName = agentName,
            workflowName = context.workflowName,
            sharedRagTools = context.sharedKnowledgeBase
        )

        var lastAssistantContent: String? = null
        val structuredOutputFiles: () -> List<File> = {
            val writeTo = structuredOutput.writeTo?.trim().orEmpty()
            if (writeTo.isBlank()) {
                emptyList()
            } else {
                structuredOutputCandidateFiles(
                    writeTo = writeTo,
                    context = context,
                    stepName = stepName,
                    agentName = agentName,
                    directoryIsolation = directoryIsolation
                )
            }
        }
        val fallbackResult: () -> Output? = {
            lastAssistantContent
                ?.takeIf { it.isNotBlank() }
                ?.let { looseParser?.invoke(it) }
                ?.takeUnless(isEmpty)
        }

        val persistedResults: () -> List<Pair<Output, String>> = {
            val writeTo = structuredOutput.writeTo?.trim().orEmpty()
            if (writeTo.isBlank()) {
                emptyList()
            } else {
                structuredOutputFiles().mapNotNull { file ->
                    runCatching {
                        file
                            .takeIf { it.isFile && it.length() > 0L }
                            ?.readText(Charsets.UTF_8)
                            ?.let { looseParser?.invoke(it) }
                            ?.takeUnless(isEmpty)
                            ?.let { it to file.absoluteFile.toPath().normalize().toString() }
                    }.getOrNull()
                }
            }
        }

        val bestRecoveredResult: () -> Pair<Output, Map<String, String>>? = {
            val candidates = mutableListOf<Pair<Output, Map<String, String>>>()
            persistedResults().forEach { (result, sourcePath) ->
                candidates += result to mapOf(
                    "structured_output_recovered_from" to persistedRecoverySource(structuredOutput, sourcePath)
                )
            }
            fallbackResult()?.let { result ->
                candidates += result to mapOf("structured_output_recovered_from" to "assistant_output")
            }
            candidates
                .filter { (result, _) -> isUsable(result) }
                .maxByOrNull { (result, _) -> qualityScore(result) }
        }

        val structuredResult = try {
            val (_, result) = buildAndRunAgent<String, Output>(
                httpAccess = httpAccess,
                parameters = runtime.parameters,
                systemPrompt = runtime.systemPrompt,
                input = input,
                toolRegistry = runtime.toolRegistry,
                skillManager = runtime.skillManager,
                installFeatures = runtime.installFeatures
            ) { _, _, toolRegistry ->
                strategy<String, Output>(
                    name = "__workflow_structured_output_agent_strategy__"
                ) {
                    val requestStructure by structureToolGraph<Output>(
                        // Koog 1.0.0 widened ToolRegistry.tools to List<ToolBase<*, *>>; our
                        // structureToolGraph still expects List<Tool<*, *>> (the
                        // no-metadata convenience subclass). Every @Tool-annotated
                        // function in braidrun-agent compiles to a Tool subclass, so
                        // filterIsInstance preserves the full set — only pure ToolBase
                        // implementations (AgentContextAwareTool, custom metadata-aware
                        // tools) would drop out, and we have none.
                        tools = toolRegistry.tools
                            .filterIsInstance<ai.koog.agents.core.tools.Tool<*, *>>()
                            .filterNot { it.descriptor.name == "__exit__" },
                        structureExamples = structureExamples,
                        name = "__workflow_structured_output__",
                        onAssistantContent = { lastAssistantContent = it },
                        finalizeWithoutToolsAfterToolResults = true
                    )
                    nodeStart then requestStructure then nodeFinish
                }
            }
            result
        } catch (e: Exception) {
            bestRecoveredResult()?.let { (recovered, metadata) ->
                return persistStructuredOutput(
                    stepName = stepName,
                    structuredOutput = structuredOutput,
                    toJsonElement = toJsonElement,
                    result = recovered,
                    metadata = metadata
                )
            }
            writeStructuredOutputRepairContext(
                stepName = stepName,
                structuredOutput = structuredOutput,
                reason = "parse_error: ${e.message.orEmpty()}",
                assistantContent = lastAssistantContent,
                candidateFiles = structuredOutputFiles(),
                executionId = context.executionId
            )
            throw WorkflowExecutionException(
                "Step '$stepName' failed to produce parseable structured output" +
                    e.message?.let { ": $it" }.orEmpty(),
                e
            )
        }

        val candidates = mutableListOf<Pair<Output, Map<String, String>>>()
        structuredResult?.let { candidates += it to emptyMap() }
        persistedResults().forEach { (recovered, sourcePath) ->
            candidates += recovered to mapOf(
                "structured_output_recovered_from" to persistedRecoverySource(structuredOutput, sourcePath)
            )
        }
        fallbackResult()?.let { recovered ->
            candidates += recovered to mapOf("structured_output_recovered_from" to "assistant_output")
        }

        val bestResult = candidates
            .filter { (result, _) -> isUsable(result) }
            .maxByOrNull { (result, _) -> qualityScore(result) }
            ?: run {
                val reason = if (candidates.isEmpty()) "missing_output" else "insufficient_business_coverage"
                writeStructuredOutputRepairContext(
                    stepName = stepName,
                    structuredOutput = structuredOutput,
                    reason = reason,
                    assistantContent = lastAssistantContent,
                    candidateFiles = structuredOutputFiles(),
                    executionId = context.executionId
                )
                if (candidates.isEmpty()) {
                    throw WorkflowExecutionException("Step '$stepName' did not produce structured output")
                }
                throw WorkflowExecutionException(
                    "Step '$stepName' produced insufficient structured output coverage"
                )
            }

        val (result, metadata) = bestResult
        if (structuredOutput.failOnEmpty && isEmpty(result)) {
            writeStructuredOutputRepairContext(
                stepName = stepName,
                structuredOutput = structuredOutput,
                reason = "empty_output",
                assistantContent = lastAssistantContent,
                candidateFiles = structuredOutputFiles(),
                executionId = context.executionId
            )
            throw WorkflowExecutionException(
                "Step '$stepName' produced empty structured output" +
                    lastAssistantContent?.let { "; assistant_output_preview=${it.take(240)}" }.orEmpty()
            )
        }

        return persistStructuredOutput(stepName, structuredOutput, toJsonElement, result, metadata)
    }

    private fun writeStructuredOutputRepairContext(
        stepName: String,
        structuredOutput: StructuredOutputConfig,
        reason: String,
        assistantContent: String?,
        candidateFiles: List<File> = emptyList(),
        executionId: String? = null
    ) {
        val writeTo = structuredOutput.writeTo?.trim().orEmpty()
        if (writeTo.isBlank()) return
        val persistedContents = (candidateFiles.ifEmpty { listOf(File(writeTo)) })
            .mapNotNull { file ->
                runCatching {
                    file
                        .takeIf { it.isFile && it.length() > 0L }
                        ?.let { existingFile ->
                            normalizeFilePath(existingFile) to existingFile.readText(Charsets.UTF_8)
                        }
                }.getOrNull()
            }
        val assistantSnippet = assistantContent?.trim()?.takeIf(String::isNotBlank)
        val eventSnippets = structuredOutputRepairEventSnippets(executionId, stepName)

        val target = structuredOutputRepairContextFile(writeTo)
        val content = buildString {
            appendLine("Structured output repair request")
            appendLine("step=$stepName")
            appendLine("schema=${structuredOutput.schema}")
            appendLine("failure_reason=$reason")
            appendLine()
            appendLine("Decide whether this is a true business failure or only a formatting/channel failure.")
            appendLine("If it is a true failure, return an empty structured object for this schema.")
            appendLine("If it is only formatting/channel failure, output the repaired structured object using only the content below.")
            persistedContents.forEach { (sourcePath, persistedText) ->
                val persistedSnippet = persistedText.trim().takeIf(String::isNotBlank) ?: return@forEach
                appendLine()
                appendLine(
                    "=== Existing persisted file content ($sourcePath) " +
                        "(possibly malformed or written by the previous AI) ==="
                )
                appendLine(truncateStructuredRepairSource(persistedSnippet))
            }
            if (assistantSnippet != null) {
                appendLine()
                appendLine("=== Previous assistant final content ===")
                appendLine(truncateStructuredRepairSource(assistantSnippet))
            }
            eventSnippets.forEachIndexed { index, snippet ->
                appendLine()
                appendLine("=== Previous agent event trace candidate ${index + 1} ===")
                appendLine(snippet)
            }
        }
        runCatching {
            target.parentFile?.mkdirs()
            target.writeText(content, Charsets.UTF_8)
        }
    }

    private fun structuredOutputRepairEventSnippets(executionId: String?, stepName: String): List<String> {
        if (executionId.isNullOrBlank()) return emptyList()
        val markers = listOf(
            "investment_decisions",
            "investment_decision",
            "summaries",
            "summary_commentary",
            "followups",
            "followup_suggestion",
            "row_commentaries",
            "row_commentary",
            "focus_commentaries",
            "focus_commentary",
            "ai_commentary_parts"
        )
        val events = WorkflowMonitor.getMetrics(executionId)
            ?.stepMetrics
            ?.get(stepName)
            ?.eventsSnapshot()
            .orEmpty()
        return events
            .mapNotNull { event ->
                val text = listOfNotNull(event.summary, event.detail).joinToString("\n").trim()
                if (text.isBlank()) return@mapNotNull null
                if (markers.none { marker -> text.contains(marker, ignoreCase = true) }) return@mapNotNull null
                buildString {
                    appendLine("event_type=${event.type}, category=${event.category}, sub_category=${event.subCategory.orEmpty()}")
                    appendLine("summary=${event.summary}")
                    event.detail?.trim()?.takeIf(String::isNotBlank)?.let {
                        appendLine("detail:")
                        appendLine(truncateStructuredRepairSource(it, limit = 12000))
                    }
                }.trim()
            }
            .distinct()
            .takeLast(8)
    }

    private fun structuredOutputRepairContextFile(writeTo: String): File {
        val target = File(writeTo)
        val baseName = target.name.substringBeforeLast('.', target.name)
        return File(target.parentFile ?: File("."), "$baseName.repair_input.txt")
    }

    private fun structuredOutputCandidateFiles(
        writeTo: String,
        context: WorkflowExecutionContext,
        stepName: String,
        agentName: String,
        directoryIsolation: DirectoryIsolationConfig
    ): List<File> {
        val target = File(writeTo)
        val candidates = linkedMapOf<String, File>()

        fun addCandidate(file: File) {
            candidates[normalizeFilePath(file)] = file
        }

        addCandidate(target)

        val userId = baseParameters.parameter("user_id", "default")
        val workingDir = File(
            directoryIsolation.getWorkingDir(
                executionId = context.executionId,
                stepName = stepName,
                agentName = agentName,
                workflowName = context.workflowName,
                userId = userId
            )
        )
        addCandidate(File(workingDir, target.name))

        return candidates.values.toList()
    }

    private fun persistedRecoverySource(
        structuredOutput: StructuredOutputConfig,
        sourcePath: String
    ): String {
        val writeTo = structuredOutput.writeTo?.trim().orEmpty()
        if (writeTo.isBlank()) return "persisted_file"
        return if (normalizeFilePath(File(writeTo)) == sourcePath) {
            "write_to"
        } else {
            "workspace_file"
        }
    }

    private fun normalizeFilePath(file: File): String =
        file.absoluteFile.toPath().normalize().toString()

    private fun truncateStructuredRepairSource(value: String, limit: Int = 24000): String {
        val text = value.trim()
        if (text.length <= limit) return text
        val headLength = limit * 2 / 3
        val tailLength = limit - headLength
        return buildString {
            append(text.take(headLength))
            appendLine()
            appendLine("\n...[truncated ${text.length - limit} chars]...\n")
            append(text.takeLast(tailLength))
        }
    }

    private fun <Output> persistStructuredOutput(
        stepName: String,
        structuredOutput: StructuredOutputConfig,
        toJsonElement: (Output) -> JsonElement,
        result: Output,
        metadata: Map<String, String> = emptyMap()
    ): String {
        val jsonElement = toJsonElement(result)
        val jsonText = structuredOutputFileJson.encodeToString(JsonElement.serializer(), jsonElement)
        val writeTo = structuredOutput.writeTo?.trim().orEmpty()
        if (writeTo.isNotBlank()) {
            val target = File(writeTo)
            target.parentFile?.mkdirs()
            target.writeText(jsonText, Charsets.UTF_8)
        }

        return buildString {
            appendLine("structured_output_schema=${structuredOutput.schema}")
            if (writeTo.isNotBlank()) appendLine("structured_output_file=$writeTo")
            metadata.forEach { (key, value) -> appendLine("$key=$value") }
            append(jsonText)
        }.trimEnd()
    }

    private fun normalizeStructuredOutputSchema(schema: String): String =
        schema.trim().lowercase().replace('-', '_')

    private fun enrichAgentOutputWithExitToolPayload(
        executionId: String,
        stepName: String,
        output: String
    ): String {
        if (extractBestBusinessJsonText(output) != null) return output

        val exitPayload = WorkflowMonitor.getMetrics(executionId)
            ?.stepMetrics
            ?.get(stepName)
            ?.eventsSnapshot()
            .orEmpty()
            .asReversed()
            .firstNotNullOfOrNull { event ->
                val isExitEvent = event.category == "tool" &&
                    (event.summary.contains("__exit__") || event.detail.orEmpty().contains("__exit__"))
                if (!isExitEvent) return@firstNotNullOfOrNull null
                extractBestBusinessJsonText(event.detail.orEmpty())
            }
            ?: return output

        if (output.contains(exitPayload)) return output
        return buildString {
            append(output.trimEnd())
            if (isNotEmpty()) appendLine().appendLine()
            append(exitPayload)
        }
    }

    private suspend fun <T> withStepAgent(
        agentName: String,
        agentDef: AgentDefinition,
        context: WorkflowExecutionContext,
        stepName: String,
        directoryIsolation: DirectoryIsolationConfig = DirectoryIsolationConfig(),
        block: suspend (AIAgent<String, String>) -> T
    ): T {
        val managedAgent = createManagedAgentForStep(
            agentName = agentName,
            agentDef = agentDef,
            context = context,
            stepName = stepName,
            directoryIsolation = directoryIsolation
        )
        return withManagedAgent(managedAgent, block)
    }

    private fun augmentPromptWithPersistenceRequirements(
        resolvedInput: String,
        expectedPersistedFiles: Set<String>
    ): String {
        if (expectedPersistedFiles.isEmpty()) return resolvedInput

        return buildString {
            appendLine(resolvedInput.trimEnd())
            appendLine()
            appendLine("=== Persistence Requirement ===")
            appendLine("You MUST actually create or update the following file(s) before finishing this step:")
            expectedPersistedFiles.forEach { appendLine("- `$it`") }
            appendLine("Treat each path inside backticks as one exact file path, even when it contains spaces.")
            if (expectedPersistedFiles.size == 1) {
                appendLine("If there is exactly one required file, make your final response the same core content that you wrote into that file.")
            }
            appendLine("Use the available file tools to write the content.")
            appendLine("Do not merely claim that the files were saved. The filesystem will be validated after your response.")
        }
    }

    private fun validatePersistedOutputs(
        step: WorkflowStep,
        executionId: String,
        output: String,
        stepStartedAt: Long,
        expectedPersistedFiles: Set<String>,
        workingDir: String?
    ) {
        val claimedPersistedFiles = pruneClaimedPersistedFilePaths(
            expectedPersistedFiles = expectedPersistedFiles,
            claimedPersistedFiles = extractPersistedFilePathsFromText(output, workingDir)
        )
        val validationTargets = selectPersistedFilePathsForValidation(
            expectedPersistedFiles = expectedPersistedFiles,
            claimedPersistedFiles = claimedPersistedFiles
        )
        var validation = validatePersistedFilePaths(validationTargets, stepStartedAt)
        if (validation.checkedPaths.isEmpty()) return

        val autoPersistedPath = if (!validation.isValid) {
            runCatching {
                autoPersistStepOutputIfPossible(
                    output = output,
                    expectedPersistedFiles = expectedPersistedFiles,
                    claimedPersistedFiles = claimedPersistedFiles,
                    validation = validation
                )
            }.onFailure { error ->
                logger.warn(error) { "[Workflow] Auto-persist fallback failed for step '${step.step}'" }
            }.getOrNull()
        } else {
            null
        }

        if (autoPersistedPath != null) {
            validation = validatePersistedFilePaths(validationTargets, stepStartedAt)
            if (validation.isValid) {
                logger.info { "[Workflow] Auto-persisted output for step '${step.step}' to '$autoPersistedPath'" }
                if (enableMonitoring) {
                    WorkflowMonitor.addEvent(
                        executionId, step.step, AgentEvent(
                            type = "step_output_auto_persisted",
                            category = "agent",
                            subCategory = "validation",
                            summary = "📝 已自动补写缺失文件",
                            detail = "Auto-persisted output to $autoPersistedPath"
                        )
                    )
                }
            }
        }

        if (!validation.isValid) {
            val detail = buildString {
                if (validation.missingPaths.isNotEmpty()) {
                    appendLine("Missing files:")
                    validation.missingPaths.forEach { appendLine("- $it") }
                }
                if (validation.stalePaths.isNotEmpty()) {
                    appendLine("Files were not updated during this step:")
                    validation.stalePaths.forEach { appendLine("- $it") }
                }
                appendLine("Prompt save targets: ${expectedPersistedFiles.joinToString(", ").ifBlank { "none" }}")
                appendLine("Output save claims: ${claimedPersistedFiles.joinToString(", ").ifBlank { "none" }}")
            }.trim()

            if (enableMonitoring) {
                WorkflowMonitor.addEvent(
                    executionId, step.step, AgentEvent(
                        type = "step_output_persistence_failed",
                        category = "agent",
                        subCategory = "validation",
                        summary = "❌ 落盘校验失败",
                        detail = detail
                    )
                )
            }

            throw WorkflowExecutionException("Step '${step.step}' failed persistence validation.\n$detail")
        }

        logger.info { "[Workflow] Step '${step.step}' persistence verified for ${validation.checkedPaths.size} file(s)" }
        if (enableMonitoring) {
            WorkflowMonitor.addEvent(
                executionId, step.step, AgentEvent(
                    type = "step_output_persistence_verified",
                    category = "agent",
                    subCategory = "validation",
                    summary = "💾 落盘校验通过: ${validation.checkedPaths.size} 个文件",
                    detail = validation.checkedPaths.joinToString("\n")
                )
            )
        }
    }

    /**
     * 创建用户交互处理器
     *
     * 当 Agent 调用 tellUser/askUser 工具时，通过此处理器：
     * 1. 将消息/问题作为监控事件发送到 WorkflowMonitor（前端通过 WebSocket 接收）
     * 2. askUser 时注册一个 CompletableDeferred 到 UserInteractionRegistry，等待用户通过 WebSocket 回复
     */
    private fun createUserInteractionHandler(
        executionId: String,
        stepName: String
    ): com.fartech.agents.tools.UserInteractionHandler {
        return object : com.fartech.agents.tools.UserInteractionHandler {
            override fun sendMessage(message: String) {
                WorkflowMonitor.addEvent(
                    executionId,
                    stepName,
                    AgentEvent(
                        type = "user_interaction_message",
                        category = "user_interaction",
                        subCategory = "message",
                        summary = "💬 $message",
                        detail = message
                    )
                )
            }

            override suspend fun askQuestion(question: String, timeoutMs: Long): String {
                val registry = com.fartech.agents.tools.UserInteractionRegistry
                val requestId = registry.generateRequestId(executionId, stepName)
                val deferred = kotlinx.coroutines.CompletableDeferred<String>()

                val request = com.fartech.agents.tools.UserInteractionRequest(
                    requestId = requestId,
                    executionId = executionId,
                    stepName = stepName,
                    message = question,
                    requiresResponse = true
                )
                registry.registerRequest(request, deferred)

                // 发送事件通知前端显示问题并等待用户输入
                WorkflowMonitor.addEvent(
                    executionId,
                    stepName,
                    AgentEvent(
                        type = "user_interaction_request",
                        category = "user_interaction",
                        subCategory = "question",
                        summary = "❓ $question",
                        detail = buildString {
                            append("""{"requestId":"""")
                            append(requestId)
                            append("""","question":"""")
                            append(question.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"))
                            append("""","requiresResponse":true}""")
                        }
                    )
                )

                return try {
                    withTimeout(timeoutMs.milliseconds) {
                        deferred.await()
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    registry.cancelRequest(requestId)
                    WorkflowMonitor.addEvent(
                        executionId,
                        stepName,
                        AgentEvent(
                            type = "user_interaction_timeout",
                            category = "user_interaction",
                            subCategory = "timeout",
                            summary = "⏰ 用户回复超时",
                            detail = "请求 $requestId 等待用户回复超时（${timeoutMs / 1000}秒）"
                        )
                    )
                    "[用户未在规定时间内回复，请求已超时]"
                }
            }
        }
    }

    /**
     * 创建监控事件收集的 installFeatures
     *
     * 将 Agent 的工具调用、LLM 调用、生命周期事件等通过 handleEvents 收集并记录到 WorkflowMonitor，
     * 供前端实时展示执行过程中的详细信息。
     */
    private fun createUserProgressNotifier(
        parameters: List<ConfigurationParameter>,
        executionId: String? = null,
        stepName: String? = null
    ): UserProgressNotifier? {
        if (!parameters.parameter("im_progress_notify_enabled", false)) return null

        val service = parameters.parameter("im_service", "telegram").lowercase()
        if (service != "telegram") return null

        val botToken = parameters.parameter("im_telegram_bot_token", "")
        if (botToken.isBlank()) return null

        val configuredChatId = parameters.parameter("im_telegram_chat_id", "")
        val sessionId = parameters.parameter("session_id", "")
        val notifyMode = parameters.parameter("im_progress_notify_mode", "all").lowercase()
        val notifyFailures = parameters.parameter("im_progress_notify_notify_failures", true)
        val includeArgs = parameters.parameter("im_progress_notify_include_args", false)
        val minIntervalMs = parameters.parameter("im_progress_notify_min_interval_seconds", 1) * 1000L
        val sameToolCooldownMs = parameters.parameter("im_progress_notify_same_tool_cooldown_seconds", 8) * 1000L

        val skippedTools = setOf("waitUserMessage", "askUserViaIM", "sendMessageToUser", "sendFileToUser")
        val microTools = setOf(
            "__read_file__", "__list_directory__", "__write_file__", "__edit_file__",
            "readFileRange", "createDirectory", "listFiles", "readTextFile", "writeTextFile",
            "getCurrentDateTime", "getTimezoneTime", "readNote", "listNotes"
        )
        val userFriendlyNames = mapOf(
            "webSearch" to "网页搜索",
            "searchWeb" to "网页搜索",
            "searchNews" to "新闻搜索",
            "fetchWebPage" to "网页抓取",
            "fetchWebpage" to "网页抓取",
            "executeShellCmd" to "Shell 命令",
            "executeShellScript" to "Shell 脚本",
            "runCode" to "代码执行",
            "runSubAgent" to "内部协作 Agent",
            "runSubAgentInParallel" to "并行内部协作",
            "getCurrentDateTime" to "时间查询",
            "getTimezoneTime" to "时区时间查询",
            "transformJson" to "数据转换",
            "saveMemory" to "长期记忆写入",
            "saveNote" to "笔记沉淀",
            "readNote" to "读取笔记",
            "listNotes" to "笔记列表",
            "gitStatus" to "Git 状态检查",
            "gitCommit" to "Git 提交",
            "gitClone" to "Git 克隆",
            "gitPull" to "Git 拉取",
            "gitPush" to "Git 推送",
            "gitLog" to "Git 日志",
            "convertFormat" to "格式转换",
            "extractText" to "文本提取",
            "generateImage" to "图片生成",
            "ocrImage" to "OCR 识别"
        )

        return object : UserProgressNotifier {
            @Volatile
            private var lastMessageAt: Long = 0L
            @Volatile
            private var lastToolName: String? = null
            @Volatile
            private var lastToolStartedAt: Long = 0L

            private fun currentChatId(): String {
                if (configuredChatId.isNotBlank()) return configuredChatId
                return com.fartech.agents.tools.InstantMessagingTools.discoveredChatIds[
                    com.fartech.agents.tools.InstantMessagingTools.telegramConversationKey(botToken, sessionId)
                ] ?: com.fartech.agents.tools.InstantMessagingTools.discoveredChatIds[botToken].orEmpty()
            }

            private fun shouldNotify(toolName: String): Boolean {
                if (toolName in skippedTools) return false
                if (notifyMode == "significant" && toolName in microTools) return false
                return true
            }

            private fun maybeSend(message: String) {
                val now = System.currentTimeMillis()
                if (now - lastMessageAt < minIntervalMs) {
                    logger.info { "[ProgressNotify] Skip sending due to throttle window (${minIntervalMs}ms)" }
                    return
                }
                val chatId = currentChatId()
                if (chatId.isBlank()) {
                    logger.info { "[ProgressNotify] Skip sending because no Telegram chat is bound yet" }
                    return
                }
                lastMessageAt = now
                notifierScope.launch {
                    try {
                        logger.info {
                            "[ProgressNotify] Sending Telegram progress update to chat $chatId: ${message.take(200)}"
                        }
                        httpAccess.post<Map<String, String>, String>(
                            "https://api.telegram.org/bot$botToken/sendMessage",
                            mapOf("chat_id" to chatId, "text" to message),
                            mapOf("Content-Type" to "application/json")
                        )
                        if (!executionId.isNullOrBlank() && !stepName.isNullOrBlank()) {
                            WorkflowMonitor.addEvent(
                                executionId,
                                stepName,
                                AgentEvent(
                                    type = "external_message",
                                    category = "message",
                                    subCategory = "outgoing_progress",
                                    summary = "发送消息: ${message.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()}",
                                    detail = JsonObject(
                                        linkedMapOf(
                                            "service" to JsonPrimitive("telegram"),
                                            "direction" to JsonPrimitive("outgoing_progress"),
                                            "toolName" to JsonPrimitive("im_progress_notify"),
                                            "content" to JsonPrimitive(message),
                                            "chat_id" to JsonPrimitive(chatId)
                                        )
                                    ).toString()
                                )
                            )
                        }
                        logger.info { "[ProgressNotify] Telegram progress update sent to chat $chatId" }
                    } catch (e: Exception) {
                        logger.warn(e) { "[ProgressNotify] Failed to send Telegram progress update to chat $chatId" }
                    }
                }
            }

            override fun onToolStarting(toolName: String, toolArgs: Any?) {
                if (!shouldNotify(toolName)) return
                val now = System.currentTimeMillis()
                if (toolName == lastToolName && now - lastToolStartedAt < sameToolCooldownMs) {
                    logger.info {
                        "[ProgressNotify] Skip duplicate tool notification for '$toolName' within ${sameToolCooldownMs}ms"
                    }
                    return
                }
                lastToolName = toolName
                lastToolStartedAt = now
                val friendlyName = userFriendlyNames[toolName] ?: toolName
                val suffix = if (includeArgs) {
                    toolArgs?.toString()?.take(160)?.let { "\n参数: $it" }.orEmpty()
                } else {
                    ""
                }
                maybeSend("⏳ 正在$friendlyName...$suffix")
            }

            override fun onToolFailed(toolName: String, error: String?) {
                if (!notifyFailures || !shouldNotify(toolName)) return
                val friendlyName = userFriendlyNames[toolName] ?: toolName
                val suffix = error?.takeIf { it.isNotBlank() }?.let { "\n错误: ${it.take(160)}" }.orEmpty()
                maybeSend("⚠️ $friendlyName 遇到问题，正在重试...$suffix")
            }
        }
    }

    private fun createMonitoringInstallFeatures(
        executionId: String,
        stepName: String,
        userProgressNotifier: UserProgressNotifier? = null,
        imService: String = "telegram"
    ): GraphAIAgent.FeatureContext.() -> Unit = {
        fun emit(
            type: String,
            category: String,
            summary: String,
            detail: String? = null,
            subCategory: String? = null,
            inputTokens: Int? = null,
            outputTokens: Int? = null,
            totalTokens: Int? = null,
        ) {
            WorkflowMonitor.addEvent(
                executionId,
                stepName,
                AgentEvent(
                    type = type,
                    category = category,
                    subCategory = subCategory,
                    summary = summary,
                    detail = detail,
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    totalTokens = totalTokens,
                )
            )
        }

        // 已知的技能工具名称集合，用于在监控事件中正确标记 subCategory
        val skillToolNames = setOf(
            "searchSkills", "getSkillDetails", "downloadSkillFromClawHub",
            "downloadSkillFromGit", "inspectSkill", "listCachedSkills",
            "clearSkillCache", "useSkill", "findLocalSkills",
            "refreshSkills", "listConfiguredSkills"
        )

        // 已知的 Sub Agent 工具名称集合
        val subAgentToolNames = setOf(
            "runSubAgent", "runSubAgentInParallel", "getAvailableLLMs"
        )

        // 根据工具名称推断子分类
        fun detectToolSubCategory(toolName: String): String? = when {
            toolName in skillToolNames -> "skill"
            toolName in subAgentToolNames -> "sub_agent"
            else -> null
        }

        val telegramToolNames = setOf("sendMessageToUser", "askUserViaIM", "waitUserMessage", "sendFileToUser")
        val externalMessageService = imService.ifBlank { "telegram" }

        // 直接读取 koog 的结构化参数/结果,而不是重新解析 toString() 渲染:
        // koog 1.0.0 的 JSONPrimitive.toString() 会带引号且不做转义,导致
        // (a) "Error..." 前缀检测永远失败(首字符总是引号) —— 失败/超时被记成成功;
        // (b) "[telegram_message]" 元数据头永远不匹配;
        // (c) 含引号/反斜杠的消息让 Json.parseToJsonElement 失败 —— 整条外发记录被静默丢弃。
        fun telegramStringArg(args: ai.koog.serialization.JSONObject?, vararg keys: String): String? {
            if (args == null) return null
            for (key in keys) {
                val value = (args.entries[key] as? ai.koog.serialization.JSONPrimitive)
                    ?.contentOrNull?.trim()
                if (!value.isNullOrBlank()) return value
            }
            return null
        }

        fun telegramResultText(result: ai.koog.serialization.JSONElement?): String? = when (result) {
            null -> null
            is ai.koog.serialization.JSONPrimitive -> result.contentOrNull
            else -> result.toString()
        }

        // 仅认可 InstantMessagingTools 真实产出的元数据键。用户消息本身可以伪造
        // "[telegram_message]\nkey=value\n---message---" 形态;不加白名单时,
        // direction/content/service 等基础字段会被入站内容覆盖,在执行历史里伪造记录。
        val allowedTelegramMetadataKeys = setOf(
            "chat_id", "chat_type", "chat_title",
            "sender_id", "sender_name", "sender_username", "message_id"
        )

        fun parseTelegramIncoming(raw: String): Pair<String, Map<String, String>> {
            if (!raw.startsWith("[telegram_message]")) return raw to emptyMap()

            val marker = "---message---"
            val markerIndex = raw.indexOf(marker)
            if (markerIndex < 0) return raw to emptyMap()

            val metadata = raw
                .substringBefore(marker)
                .lineSequence()
                .drop(1)
                .mapNotNull { line ->
                    val idx = line.indexOf('=')
                    if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                }
                .filter { (key, value) -> key in allowedTelegramMetadataKeys && value.isNotBlank() }
                .toMap()

            return raw.substring(markerIndex + marker.length).trimStart('\n', '\r') to metadata
        }

        fun telegramDetailJson(
            direction: String,
            toolName: String,
            content: String,
            metadata: Map<String, String> = emptyMap(),
            extra: Map<String, String> = emptyMap()
        ): String {
            val fields = linkedMapOf<String, JsonElement>()
            // metadata 先写、基础字段后写:基础字段(direction/content/service/toolName)
            // 是记录的事实来源,绝不允许被入站消息携带的同名键覆盖。
            (metadata + extra).forEach { (key, value) ->
                if (value.isNotBlank()) fields[key] = JsonPrimitive(value)
            }
            fields["service"] = JsonPrimitive(externalMessageService)
            fields["direction"] = JsonPrimitive(direction)
            fields["toolName"] = JsonPrimitive(toolName)
            fields["content"] = JsonPrimitive(content)
            return JsonObject(fields).toString()
        }

        fun summarizeTelegram(direction: String, content: String, fallback: String): String {
            val preview = content
                .lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.take(120)
                .orEmpty()
            val label = when (direction) {
                "incoming" -> "收到消息"
                "outgoing" -> "发送消息"
                "outgoing_file" -> "发送文件"
                else -> fallback
            }
            return if (preview.isBlank()) "📨 $label" else "📨 $label: $preview"
        }

        fun emitTelegramRecord(
            direction: String,
            toolName: String,
            content: String,
            metadata: Map<String, String> = emptyMap(),
            extra: Map<String, String> = emptyMap()
        ) {
            if (content.isBlank()) return
            emit(
                type = "external_message",
                category = "message",
                subCategory = direction,
                summary = summarizeTelegram(direction, content, "消息"),
                detail = telegramDetailJson(direction, toolName, content, metadata, extra)
            )
        }

        fun isTelegramToolSuccess(resultStr: String?): Boolean =
            !resultStr.isNullOrBlank() && !resultStr.trimStart().startsWith("Error", ignoreCase = true)

        fun didAskUserPromptReachTelegram(resultStr: String?): Boolean {
            val result = resultStr?.trim().orEmpty()
            if (result.isBlank()) return false
            if (!result.startsWith("Error", ignoreCase = true)) return true
            return result.contains("Timed out waiting for user reply", ignoreCase = true)
        }

        fun emitTelegramRecordsForTool(
            toolName: String,
            toolArgs: ai.koog.serialization.JSONObject?,
            toolResult: ai.koog.serialization.JSONElement?
        ) {
            if (toolName !in telegramToolNames) return
            val resultStr = telegramResultText(toolResult)
            when (toolName) {
                "sendMessageToUser" -> {
                    if (isTelegramToolSuccess(resultStr)) {
                        telegramStringArg(toolArgs, "message")?.let { message ->
                            emitTelegramRecord("outgoing", toolName, message)
                        }
                    }
                }
                "askUserViaIM" -> {
                    val prompt = telegramStringArg(toolArgs, "prompt")
                    if (prompt != null && didAskUserPromptReachTelegram(resultStr)) {
                        emitTelegramRecord("outgoing", toolName, prompt)
                    }
                    if (externalMessageService == "telegram" && isTelegramToolSuccess(resultStr)) {
                        val (content, metadata) = parseTelegramIncoming(resultStr!!.trim())
                        emitTelegramRecord("incoming", toolName, content, metadata)
                    }
                }
                "waitUserMessage" -> {
                    if (isTelegramToolSuccess(resultStr)) {
                        val (content, metadata) = parseTelegramIncoming(resultStr!!.trim())
                        emitTelegramRecord("incoming", toolName, content, metadata)
                    }
                }
                "sendFileToUser" -> {
                    if (isTelegramToolSuccess(resultStr)) {
                        val filePath = telegramStringArg(toolArgs, "filePath", "file_path").orEmpty()
                        val caption = telegramStringArg(toolArgs, "caption").orEmpty()
                        val content = listOf(filePath, caption).filter { it.isNotBlank() }.joinToString("\n")
                        emitTelegramRecord(
                            direction = "outgoing_file",
                            toolName = toolName,
                            content = content,
                            extra = mapOf("filePath" to filePath, "caption" to caption)
                        )
                    }
                }
            }
        }

        // 添加监控事件收集
        handleEvents {
            // --- 工具调用事件 ---
            onToolCallStarting { eventContext ->
                val toolName = eventContext.toolName
                val subCat = detectToolSubCategory(toolName)
                val emoji = if (subCat == "skill") "🧩" else if (subCat == "sub_agent") "🤖" else "🔧"
                val argsStr = try { eventContext.toolArgs.toString() } catch (_: Exception) { null }
                logProgress(AnsiColor.YELLOW, "Tool", "${emoji}调用工具: $toolName")
                userProgressNotifier?.onToolStarting(toolName, eventContext.toolArgs)
                emit(
                    type = "tool_call_starting",
                    category = "tool",
                    subCategory = subCat,
                    summary = "$emoji 调用工具: $toolName",
                    detail = argsStr
                )
            }
            onToolCallCompleted { eventContext ->
                val toolName = eventContext.toolName
                val subCat = detectToolSubCategory(toolName)
                val argsStr = try { eventContext.toolArgs.toString() } catch (_: Exception) { null }
                val resultStr = try { eventContext.toolResult.toString() } catch (_: Exception) { null }
                val detailParts = listOfNotNull(
                    argsStr?.let { "args: $it" },
                    resultStr?.let { "result: $it" }
                )
                val detail = detailParts.joinToString("\n").ifEmpty { null }
                logProgress(AnsiColor.GREEN, "Tool", "✅工具完成: $toolName")
                emit(
                    type = "tool_call_completed",
                    category = "tool",
                    subCategory = subCat,
                    summary = "✅ 工具完成: $toolName",
                    detail = detail
                )
                emitTelegramRecordsForTool(toolName, eventContext.toolArgs, eventContext.toolResult)
            }
            onToolCallFailed { eventContext ->
                val toolName = eventContext.toolName
                val subCat = detectToolSubCategory(toolName)
                val error = eventContext.error?.message
                logProgress(AnsiColor.RED, "Tool", "❌工具失败: $toolName — $error")
                userProgressNotifier?.onToolFailed(toolName, error)
                emit(
                    type = "tool_call_failed",
                    category = "tool",
                    subCategory = subCat,
                    summary = "❌ 工具失败: $toolName",
                    detail = error
                )
            }
            onToolValidationFailed { eventContext ->
                val toolName = eventContext.toolName
                val subCat = detectToolSubCategory(toolName)
                val error = eventContext.error.message
                logProgress(AnsiColor.RED, "Tool", "⚠️工具验证失败: $toolName — $error")
                emit(
                    type = "tool_validation_failed",
                    category = "tool",
                    subCategory = subCat,
                    summary = "⚠️ 工具验证失败: $toolName",
                    detail = error
                )
            }

            // --- Agent 生命周期事件 ---
            onAgentStarting {
                logProgress(AnsiColor.CYAN, "Agent", "🚀 Agent 开始执行")
                emit(
                    type = "agent_starting",
                    category = "agent",
                    summary = "🚀 Agent 开始执行"
                )
            }
            onAgentCompleted {
                val result = it.result?.toString() ?: ""
                logProgress(AnsiColor.GREEN, "Agent", "✅ Agent 执行完成")
                emit(
                    type = "agent_completed",
                    category = "agent",
                    summary = "✅ Agent 执行完成",
                    detail = if (result.length > 200) result.take(200) + "..." else result.ifEmpty { null }
                )
            }
            onAgentExecutionFailed {
                logProgress(AnsiColor.RED, "Agent", "❌ Agent 执行失败")
                emit(
                    type = "agent_failed",
                    category = "agent",
                    summary = "❌ Agent 执行失败"
                )
            }

            // --- 策略事件 ---
            onStrategyStarting {
                logProgress(AnsiColor.CYAN, "Strategy", "📋 策略开始执行")
                emit(
                    type = "strategy_starting",
                    category = "strategy",
                    summary = "📋 策略开始执行"
                )
            }
            onStrategyCompleted {
                logProgress(AnsiColor.GREEN, "Strategy", "✅ 策略执行完成")
                emit(
                    type = "strategy_completed",
                    category = "strategy",
                    summary = "✅ 策略执行完成"
                )
            }

            // --- LLM 调用事件 ---
            onLLMCallStarting { eventContext ->
                // 把模型 id + provider 显示名塞进 detail,前端用统一的
                // `model=<id>, provider=<display>` 文本格式提取出来,在步骤
                // 卡上以芯片形式展示当前用了哪个模型 + 哪家供应商。
                val modelInfo = formatModelInfoDetail(eventContext.model)
                emit(
                    type = "llm_call_starting",
                    category = "llm",
                    subCategory = "call",
                    summary = buildLLMCallSummary("🧠 模型调用开始", eventContext.model),
                    detail = modelInfo
                )
            }
            onLLMCallCompleted { eventContext ->
                // Koog 1.0.0 changed `LLMCallCompletedContext.responses: List<Message.Assistant>`
                // (multi-choice + intermixed reasoning / assistant messages) to a single
                // `response: Message.Assistant?`. Reasoning is no longer a separate message
                // but a `MessagePart.Reasoning` inside the assistant message's parts list.
                // We surface the same UI events as before by walking the parts directly.
                val response = eventContext.response
                val inputTokens = response?.metaInfo?.inputTokensCount?.takeIf { it > 0 }
                val outputTokens = response?.metaInfo?.outputTokensCount?.takeIf { it > 0 }
                val totalTokens = response?.metaInfo?.totalTokensCount?.takeIf { it > 0 }

                val tokenDetail = listOfNotNull(
                    inputTokens?.let { "input=$it" },
                    outputTokens?.let { "output=$it" },
                    totalTokens?.let { "total=$it" }
                ).joinToString(", ").ifBlank { null }

                // 同上:detail 前缀加 model=/provider= 让步骤卡和事件 tab 都能
                // 看到本次调用用的模型;两个段用 `; ` 分隔保持可读 + 可机器解析。
                val modelInfo = formatModelInfoDetail(eventContext.model)
                val detail = listOfNotNull(modelInfo, tokenDetail).joinToString("; ").ifBlank { null }

                emit(
                    type = "llm_call_completed",
                    category = "llm",
                    subCategory = "call",
                    summary = buildLLMCallSummary("✅ 模型调用完成", eventContext.model),
                    detail = detail,
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    totalTokens = totalTokens
                )

                if (inputTokens != null || outputTokens != null || totalTokens != null) {
                    emit(
                        type = "token_usage",
                        category = "llm",
                        subCategory = "token",
                        summary = "🧮 Token 统计（发送:${inputTokens ?: 0} / 接收:${outputTokens ?: 0}）",
                        detail = tokenDetail,
                        inputTokens = inputTokens,
                        outputTokens = outputTokens,
                        totalTokens = totalTokens
                    )
                }

                if (response != null) {
                    val assistantText = response.textContent().trim().takeIf { it.isNotEmpty() }
                    if (assistantText != null) {
                        emit(
                            type = "model_reply",
                            category = "llm",
                            subCategory = "reply",
                            summary = buildSummaryWithPreview("💬 模型回复", assistantText),
                            detail = truncateDetail(assistantText),
                        )
                    }

                    response.parts.filterIsInstance<MessagePart.Reasoning>().forEach { part ->
                        val content = extractReasoningText(part) ?: return@forEach
                        // Skip the {tool_call_id, tool_name, tool_args} JSON
                        // envelope some OpenRouter reasoning models route through
                        // the Reasoning channel — see reasoningCallback above for
                        // the rationale. The next tool_call_starting event will
                        // surface the same content as a proper Action card.
                        if (isToolCallEnvelopeJson(content)) return@forEach
                        emit(
                            type = "reasoning_message",
                            category = "llm",
                            subCategory = "reasoning",
                            summary = buildSummaryWithPreview("💭 模型推理", content),
                            detail = truncateDetail(content),
                        )
                    }
                }
            }

            // --- LLM 流式事件 ---
            onLLMStreamingStarting {
                emit(
                    type = "llm_streaming_starting",
                    category = "llm",
                    summary = "📡 模型流式传输开始"
                )
            }
            onLLMStreamingCompleted {
                emit(
                    type = "llm_streaming_completed",
                    category = "llm",
                    summary = "✅ 模型流式传输完成"
                )
            }
            onLLMStreamingFailed {
                logProgress(AnsiColor.RED, "LLM", "❌ LLM 流式传输失败")
                emit(
                    type = "llm_streaming_failed",
                    category = "llm",
                    summary = "❌ 模型流式传输失败"
                )
            }
        }
    }

    /**
     * Extract a single string of "what the model thought" from a
     * [MessagePart.Reasoning]. Reasoning content is now a `List<String>`
     * (one entry per thinking block / continuation), with optional
     * `summary: List<String>?` and `encrypted: String?` fallbacks for
     * providers that hide raw thinking (OpenAI o1, etc.).
     *
     * Returns null when there's nothing user-visible to show — the caller
     * is expected to short-circuit the event emission in that case.
     */
    private fun extractReasoningText(part: MessagePart.Reasoning): String? {
        fun normalize(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
        fun normalizeList(values: List<String>?): String? =
            values?.joinToString("\n")?.trim()?.takeIf { it.isNotEmpty() }

        return normalizeList(part.content)
            ?: normalizeList(part.summary)
            ?: normalize(part.encrypted)?.let { "[加密推理内容，长度:${it.length}]" }
    }

    private fun buildSummaryWithPreview(prefix: String, content: String?, maxLength: Int = 80): String {
        val normalized = content
            ?.replace("\n", " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return prefix

        val preview = if (normalized.length > maxLength) {
            normalized.take(maxLength) + "..."
        } else {
            normalized
        }

        return "$prefix：$preview"
    }

    private fun truncateDetail(content: String?, maxLength: Int = 8000): String? {
        val normalized = content?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (normalized.length > maxLength) {
            normalized.take(maxLength) + "..."
        } else {
            normalized
        }
    }

    /**
     * Build the parseable detail string for `llm_call_*` events. The
     * frontend (`ExecutionTimeline.vue` step card) regex-extracts these
     * key=value pairs to render a "model · provider" chip next to the
     * agent chip. Keep the format stable across versions — change here
     * also requires updating the frontend extractor.
     */
    internal fun formatModelInfoDetail(model: ai.koog.prompt.llm.LLModel): String {
        val providerDisplay = model.provider.display.takeIf { it.isNotBlank() } ?: model.provider.id
        return "model=${model.id}, provider=$providerDisplay"
    }

    /**
     * Append the model id to the LLM call summary so even consumers that
     * only show event summaries (the event log filter, recovery dialogs)
     * can tell which model was hit. Keeps the original prefix verbatim
     * so existing string-match callers still work.
     */
    internal fun buildLLMCallSummary(prefix: String, model: ai.koog.prompt.llm.LLModel): String {
        val providerDisplay = model.provider.display.takeIf { it.isNotBlank() } ?: model.provider.id
        return "$prefix · ${model.id}（$providerDisplay）"
    }

    /**
     * Heuristic: does this string look like a tool-call envelope, the
     * `{"tool_call_id":"...","tool_name":"...","tool_args":{...}}` shape some
     * reasoning models route through the Reasoning channel instead of as a
     * proper tool call? We avoid full JSON parsing — a quick string check on
     * the three required keys keeps this O(content length) and tolerant of
     * minor whitespace / key-order variations. Repro source:
     * `execution-process-0377fa69-...` where every cycle's 思考 section was a
     * verbatim copy of the next 动作 section.
     */
    internal fun isToolCallEnvelopeJson(content: String): Boolean {
        val trimmed = content.trimStart()
        if (!trimmed.startsWith("{")) return false
        // Use a single bounded scan so we don't burn cycles on huge prose.
        val window = trimmed.take(2048)
        return window.contains("\"tool_call_id\"") &&
            window.contains("\"tool_name\"") &&
            window.contains("\"tool_args\"")
    }

    /**
     * 处理手动审批
     */
    private suspend fun handleManualApproval(step: WorkflowStep, context: WorkflowExecutionContext) {
        val approvalConfig = step.manualApproval ?: return

        logger.info { "[Workflow:${context.workflowName}] Step '${step.step}' requires manual approval" }

        // 标记为等待审批
        if (enableMonitoring) {
            WorkflowMonitor.awaitingApproval(context.executionId, step.step)
        }

        // 加载 reviewable_actions 中每组 source_var 指向的 JSON 数组,塞进 ApprovalRequest。
        val reviewableGroups = approvalConfig.reviewableActions.map { group ->
            loadReviewableGroup(group, context, step.step)
        }

        val approvalId = "${context.executionId}:${step.step}"
        val request = ApprovalRequest(
            approvalId = approvalId,
            workflowName = context.workflowName,
            executionId = context.executionId,
            stepName = step.step,
            message = approvalConfig.approvalMessage ?: "Approval required for step '${step.step}'",
            approvers = approvalConfig.approvers,
            requestedAt = System.currentTimeMillis(),
            timeout = approvalConfig.timeout.toString(),
            reviewableGroups = reviewableGroups
        )

        val pendingApproval = PendingApproval(request)
        activeApprovals[approvalId] = pendingApproval

        val approvalStart = System.currentTimeMillis()
        try {
            val decision = withTimeoutOrNull((approvalConfig.timeout * 1000L).milliseconds) {
                approvalHandler?.requestApproval(request) ?: pendingApproval.decision.await()
            } ?: throw WorkflowExecutionException(
                "Manual approval timed out for step '${step.step}' after ${approvalConfig.timeout}s"
            )

            if (!decision.approved) {
                // Mirror the variables that the approved branch sets via
                // applyApprovedReviewableActions(), so downstream handlers that catch
                // WorkflowApprovalRequiredException (or sub_workflow callers reading
                // context after a rejection) see a consistent state. Pre-Phase-10
                // these were never set on rejection, so a step like
                // `if: "{{var:approval_decision}} == 'rejected'"` silently never matched.
                val approvalComment = decision.comment?.trim().orEmpty()
                context.setVariable("approval_decision", "rejected")
                context.setVariable("approval_comment", approvalComment)
                context.setVariable("approval_approved_count", "0")
                context.setVariable("${step.step}_approval_decision", "rejected")
                context.setVariable("${step.step}_approval_comment", approvalComment)
                context.setVariable("${step.step}_approved_count", "0")
                throw WorkflowApprovalRequiredException(
                    "Manual approval rejected for step '${step.step}'",
                    approvalConfig
                )
            }

            // 批准后:把每组的最终 items(审批人编辑/勾选过的子集,或原样)写出到独立文件,
            // 并把路径放到 context.variables[outputVar],下游 execute_* 步骤直接读 outputVar。
            applyApprovedReviewableActions(approvalConfig, request, decision, context, step.step)
        } finally {
            // 把"等待人工审批"占用的墙钟时间从 workflow 总超时预算中扣除 ——
            // 详见 [ExecutionDeadline] 的注释。无论审批是被批准、拒绝、超时还是
            // 被父协程取消,审批等待都已经发生过,都应当从 active execution 预算里扣掉。
            // 审批可能发生在 sub-workflow 里:当前 executionId 是派生的子 id,而
            // deadline 挂在祖先上,所以把已知的整条链(自身/父/根)都记上,否则
            // 父 workflow 的 timeout.total 会被人工审批的等待时间误耗尽。
            val waited = (System.currentTimeMillis() - approvalStart).coerceAtLeast(0L)
            setOfNotNull(context.executionId, context.parentExecutionId, context.rootExecutionId)
                .forEach { id -> executionDeadlines[id]?.approvalWaitMs?.addAndGet(waited) }
            activeApprovals.remove(approvalId)
        }
    }

    /**
     * 读取一组 reviewable_action 的来源 JSON 数组。失败时返回空 items,审批仍可继续。
     */
    private fun loadReviewableGroup(
        group: ReviewableActionGroup,
        context: WorkflowExecutionContext,
        stepName: String
    ): ReviewablePayloadGroup {
        val sourceRaw = (context.getVariable(group.sourceVar) as? String)?.trim().orEmpty()
        if (sourceRaw.isEmpty()) {
            logger.info { "[Workflow:${context.workflowName}] Step '$stepName' reviewable group '${group.name}' source_var=${group.sourceVar} 未设置,跳过加载" }
            return ReviewablePayloadGroup(
                name = group.name,
                sourceVar = group.sourceVar,
                outputVar = group.outputVar,
                title = group.title,
                keyField = group.keyField,
                columns = group.columns,
                items = emptyList(),
                sourcePath = null
            )
        }
        return try {
            val file = File(sourceRaw)
            if (!file.exists()) {
                logger.warn { "[Workflow:${context.workflowName}] Step '$stepName' reviewable group '${group.name}' 文件不存在: $sourceRaw" }
                return ReviewablePayloadGroup(
                    name = group.name,
                    sourceVar = group.sourceVar,
                    outputVar = group.outputVar,
                    title = group.title,
                    keyField = group.keyField,
                    columns = group.columns,
                    items = emptyList(),
                    sourcePath = sourceRaw
                )
            }
            val parsed = workflowJson.parseToJsonElement(file.readText(Charsets.UTF_8))
            val items = if (parsed is JsonArray) parsed.toList() else emptyList()
            ReviewablePayloadGroup(
                name = group.name,
                sourceVar = group.sourceVar,
                outputVar = group.outputVar,
                title = group.title,
                keyField = group.keyField,
                columns = group.columns,
                items = items,
                sourcePath = sourceRaw
            )
        } catch (e: Exception) {
            logger.warn(e) { "[Workflow:${context.workflowName}] Step '$stepName' reviewable group '${group.name}' 读取失败: $sourceRaw" }
            ReviewablePayloadGroup(
                name = group.name,
                sourceVar = group.sourceVar,
                outputVar = group.outputVar,
                title = group.title,
                keyField = group.keyField,
                columns = group.columns,
                items = emptyList(),
                sourcePath = sourceRaw
            )
        }
    }

    /**
     * 把审批结果落盘:每组写出 reviewed_<name>.json 到 source 文件同目录(没有 source 文件
     * 时回落到 .workflow-runs/<exec>/),并把绝对路径塞进 context.variables[outputVar]。
     */
    private fun applyApprovedReviewableActions(
        config: ManualApprovalConfig,
        request: ApprovalRequest,
        decision: ApprovalDecision,
        context: WorkflowExecutionContext,
        stepName: String
    ) {
        val approvalComment = decision.comment?.trim().orEmpty()
        context.setVariable("approval_comment", approvalComment)
        context.setVariable("${stepName}_approval_comment", approvalComment)
        context.setVariable("approval_decision", "approved")
        context.setVariable("${stepName}_approval_decision", "approved")

        if (config.reviewableActions.isEmpty()) {
            context.setVariable("approval_approved_count", "0")
            context.setVariable("${stepName}_approved_count", "0")
            return
        }
        val edits = decision.edits.orEmpty()
        var totalApproved = 0
        for (group in request.reviewableGroups) {
            val approvedItems: List<JsonElement> = edits[group.name] ?: group.items
            totalApproved += approvedItems.size
            val outDir: File = group.sourcePath?.let { File(it).parentFile }
                ?: File("./.workflow-runs/${context.executionId}").also { it.mkdirs() }
            val outFile = File(outDir, "reviewed_${group.name}.json")
            try {
                outFile.parentFile?.mkdirs()
                outFile.writeText(
                    workflowJson.encodeToString(JsonArray.serializer(), JsonArray(approvedItems)),
                    Charsets.UTF_8
                )
                context.setVariable(group.outputVar, outFile.absolutePath)
                logger.info {
                    "[Workflow:${context.workflowName}] Step '$stepName' reviewable group '${group.name}': " +
                        "wrote ${approvedItems.size} approved item(s) to ${outFile.absolutePath} (${group.outputVar})"
                }
            } catch (e: Exception) {
                logger.error(e) {
                    "[Workflow:${context.workflowName}] Step '$stepName' reviewable group '${group.name}': " +
                        "failed to write approved subset to ${outFile.absolutePath}"
                }
            }
        }
        context.setVariable("approval_approved_count", totalApproved.toString())
        context.setVariable("${stepName}_approved_count", totalApproved.toString())
    }

    /**
     * 批准待审批的步骤(无编辑,按原样)
     */
    fun approveStep(approvalId: String): Boolean {
        val pendingApproval = activeApprovals[approvalId] ?: return false
        return pendingApproval.decision.complete(ApprovalDecision.APPROVED_AS_IS)
    }

    /**
     * 批准并附带审批人编辑后的负载
     */
    fun approveStep(approvalId: String, edits: Map<String, List<JsonElement>>?, comment: String? = null): Boolean {
        val pendingApproval = activeApprovals[approvalId] ?: return false
        return pendingApproval.decision.complete(ApprovalDecision(approved = true, edits = edits, comment = comment))
    }

    /**
     * 拒绝待审批的步骤
     */
    fun rejectStep(approvalId: String): Boolean {
        val pendingApproval = activeApprovals[approvalId] ?: return false
        return pendingApproval.decision.complete(ApprovalDecision.REJECTED)
    }

    /**
     * 获取所有待审批的请求
     */
    fun getPendingApprovals(): List<ApprovalRequest> = activeApprovals.values.map { it.request }

    /**
     * 清理待审批请求
     */
    fun clearPendingApprovals() {
        activeApprovals.values.forEach { pending ->
            pending.decision.cancel()
        }
        activeApprovals.clear()
        notifierScope.cancel()
    }

    fun continueDebug(): Boolean = debugController?.resumeContinue() == true

    fun stepDebug(): Boolean = debugController?.resumeStep() == true

    fun requestDebugPause(): Boolean = debugController?.requestPause() == true

    fun updateDebugBreakpoints(breakpoints: List<WorkflowDebugBreakpoint>): Boolean {
        val controller = debugController ?: return false
        controller.replaceBreakpoints(breakpoints)
        return true
    }

    fun getDebugState(): WorkflowDebugState? = debugController?.getState()

    fun getDebugSnapshot(): WorkflowDebugSnapshot? = debugController?.getCurrentSnapshot()

    fun getDebugController(): WorkflowDebugController? = debugController

    fun modifyDebugVariables(variables: Map<String, String>) {
        val context = currentExecutionContext ?: return
        variables.forEach { (key, value) ->
            context.setVariable(key, value)
        }
        // Rebuild latest snapshot to reflect variable changes immediately
        debugController?.let { controller ->
            val snapshot = controller.getCurrentSnapshot() ?: return@let
            controller.updateLatestSnapshot(
                snapshot.copy(
                    capturedAt = System.currentTimeMillis(),
                    variables = context.variables.toSortedMap().mapValues { (_, v) -> v.toString() }
                )
            )
        }
    }

    private suspend fun awaitDebugCheckpoint(
        point: WorkflowDebugPoint,
        stepName: String,
        context: WorkflowExecutionContext,
        label: String? = null
    ) {
        val controller = debugController ?: return
        val pauseResult = controller.checkpoint(
            WorkflowDebugLocation(
                point = point,
                stepName = stepName,
                label = label
            ),
            context
        ) ?: return

        if (enableMonitoring) {
            val reason = pauseResult.pause.reason.wireName
            val labelDetail = label?.let { "\nlabel=$it" }.orEmpty()
            WorkflowMonitor.addEvent(
                context.executionId,
                stepName,
                AgentEvent(
                    type = "debug_pause",
                    category = "debug",
                    subCategory = pauseResult.pause.location.point.wireName,
                    summary = "⏸️ Debug 暂停: ${pauseResult.pause.location.point.wireName}",
                    detail = "reason=$reason\npause_id=${pauseResult.pause.pauseId}$labelDetail"
                )
            )
            WorkflowMonitor.addEvent(
                context.executionId,
                stepName,
                AgentEvent(
                    type = "debug_resume",
                    category = "debug",
                    subCategory = pauseResult.pause.location.point.wireName,
                    summary = "▶️ Debug 继续: ${pauseResult.resumeMode.name.lowercase()}",
                    detail = "pause_id=${pauseResult.pause.pauseId}"
                )
            )
        }
    }

    // ==================== 工作流级共享知识库 ====================

    /**
     * 初始化工作流级共享知识库
     *
     * 当 workflow.knowledgeBase 存在且 enabled=true 时：
     * 1. 构造 RAGTools 实例，参数从 KnowledgeBaseConfig 映射
     * 2. 预加载 source_files 中声明的文件
     * 3. 将实例存入 context.sharedKnowledgeBase，后续所有 Agent 共享
     */
    private suspend fun initializeKnowledgeBase(
        workflow: WorkflowDefinition,
        context: WorkflowExecutionContext,
        executionId: String
    ) {
        val kb = workflow.knowledgeBase ?: return
        if (!kb.enabled) return

        logger.info { "[Workflow:${workflow.name}] Initializing shared knowledge base (embedding=${kb.embeddingModel}, provider=${kb.embeddingProvider})" }

        // 构造 RAGTools 参数
        val storageDir = kb.storageDir
            ?: ".workflow-runs/$executionId/knowledge-base"
        val absoluteStorageDir = File(storageDir).absolutePath

        val ragParams = baseParameters.toMutableList()
        ragParams.add(ConfigurationParameter("rag_storage_dir", JsonPrimitive(absoluteStorageDir)))
        ragParams.add(ConfigurationParameter("rag_chunk_size", JsonPrimitive(kb.chunkSize.toString())))
        ragParams.add(ConfigurationParameter("rag_chunk_overlap", JsonPrimitive(kb.chunkOverlap.toString())))
        ragParams.add(ConfigurationParameter("rag_embedding_model", JsonPrimitive(kb.embeddingModel)))
        ragParams.add(ConfigurationParameter("rag_embedding_provider", JsonPrimitive(kb.embeddingProvider)))
        injectKnowledgeBaseEmbeddingFallback(workflow, ragParams)

        val ragTools = RAGTools.createFromParameters(
            parameters = ragParams,
            baseClient = httpAccess.client
        )
        context.sharedKnowledgeBase = ragTools

        // 预加载 source_files
        if (kb.sourceFiles.isNotEmpty()) {
            logger.info { "[Workflow:${workflow.name}] Pre-loading ${kb.sourceFiles.size} source file(s) into knowledge base" }
            for (sf in kb.sourceFiles) {
                try {
                    val absolutePath = File(sf.path).let {
                        if (it.isAbsolute) it.absolutePath else it.absolutePath
                    }
                    // Use the suspend variant so we don't park a Dispatchers.Default
                    // worker via runBlocking inside an already-running coroutine.
                    val result = ragTools.indexFileSuspend(absolutePath, sf.tags)
                    logger.info { "[Workflow:${workflow.name}] KB pre-load '${sf.path}': $result" }
                } catch (e: Exception) {
                    logger.warn(e) { "[Workflow:${workflow.name}] Failed to pre-load '${sf.path}' into knowledge base" }
                }
            }
        }

        // 发送监控事件
        if (enableMonitoring) {
            WorkflowMonitor.addEvent(
                executionId,
                "__workflow__",
                AgentEvent(
                    type = "knowledge_base_initialized",
                    category = "workflow",
                    subCategory = "knowledge_base",
                    summary = "📚 共享知识库已初始化 (${kb.embeddingModel}, ${kb.sourceFiles.size} 个预加载文件)",
                    detail = "storage_dir=$absoluteStorageDir, auto_index_outputs=${kb.autoIndexOutputs}, " +
                            "auto_inject_rag_tools=${kb.autoInjectRagTools}, chunk_size=${kb.chunkSize}"
                )
            )
        }

        logger.info { "[Workflow:${workflow.name}] Shared knowledge base ready at $absoluteStorageDir" }
    }

    private fun injectKnowledgeBaseEmbeddingFallback(
        workflow: WorkflowDefinition,
        ragParams: MutableList<ConfigurationParameter>
    ) {
        if (ragParams.parameter("rag_embedding_provider", "openai") != "openai") {
            return
        }

        if (System.getenv("OPENAI_API_KEY")?.isNotBlank() == true) {
            return
        }

        if (findNonBlankParameterValue(ragParams, "rag_embedding_api_key") != null) {
            return
        }

        val fallback = resolveKnowledgeBaseEmbeddingCredentials(workflow, ragParams) ?: return
        ragParams.removeAll { it.key == "rag_embedding_api_key" || it.key == "rag_embedding_base_url" }
        ragParams.add(ConfigurationParameter("rag_embedding_api_key", JsonPrimitive(fallback.apiKey)))
        fallback.baseUrl?.let {
            ragParams.add(ConfigurationParameter("rag_embedding_base_url", JsonPrimitive(it)))
        }

        val source = if (fallback.usesOpenRouter) "openrouter-compatible" else "openai-compatible"
        logger.info { "[Workflow:${workflow.name}] Knowledge base embedding credentials derived from $source workflow parameters" }
    }

    private data class EmbeddingFallbackCredentials(
        val apiKey: String,
        val baseUrl: String? = null,
        val usesOpenRouter: Boolean = false
    )

    private fun resolveKnowledgeBaseEmbeddingCredentials(
        workflow: WorkflowDefinition,
        ragParams: List<ConfigurationParameter>
    ): EmbeddingFallbackCredentials? {
        findNonBlankParameterValue(ragParams, "openai_api_key")?.let {
            return EmbeddingFallbackCredentials(apiKey = it)
        }
        findProviderKey(ragParams, "openai")?.let {
            return EmbeddingFallbackCredentials(apiKey = it)
        }

        val baseOpenRouterUrl = findNonBlankParameterValue(ragParams, "openrouter_base_url")
            ?: "https://openrouter.ai/api/v1"
        findNonBlankParameterValue(ragParams, "openrouter_api_key")?.let {
            return EmbeddingFallbackCredentials(apiKey = it, baseUrl = baseOpenRouterUrl, usesOpenRouter = true)
        }
        findProviderKey(ragParams, "openrouter")?.let {
            return EmbeddingFallbackCredentials(apiKey = it, baseUrl = baseOpenRouterUrl, usesOpenRouter = true)
        }

        workflow.agents.values.forEach { agentDefinition ->
            val resolved = agentDefinition.resolveParameters()
            resolved["openai_api_key"]?.jsonScalarContent()?.takeIf { it.isNotBlank() }?.let {
                return EmbeddingFallbackCredentials(apiKey = it)
            }
            resolved["llm_provider_keys"]?.jsonProviderKey("openai")?.takeIf { it.isNotBlank() }?.let {
                return EmbeddingFallbackCredentials(apiKey = it)
            }

            val openRouterUrl = resolved["openrouter_base_url"]?.jsonScalarContent()
                ?: "https://openrouter.ai/api/v1"
            resolved["openrouter_api_key"]?.jsonScalarContent()?.takeIf { it.isNotBlank() }?.let {
                return EmbeddingFallbackCredentials(apiKey = it, baseUrl = openRouterUrl, usesOpenRouter = true)
            }
            resolved["llm_provider_keys"]?.jsonProviderKey("openrouter")?.takeIf { it.isNotBlank() }?.let {
                return EmbeddingFallbackCredentials(apiKey = it, baseUrl = openRouterUrl, usesOpenRouter = true)
            }
        }

        return null
    }

    private fun findNonBlankParameterValue(
        parameters: List<ConfigurationParameter>,
        key: String
    ): String? = parameters.firstOrNull { it.key == key }?.value?.jsonScalarContent()?.takeIf { it.isNotBlank() }

    private fun findProviderKey(
        parameters: List<ConfigurationParameter>,
        provider: String
    ): String? = parameters.firstOrNull { it.key == "llm_provider_keys" }?.value?.jsonProviderKey(provider)

    private fun JsonElement.jsonScalarContent(): String? = when (this) {
        is JsonPrimitive -> content
        else -> null
    }

    private fun JsonElement.jsonProviderKey(provider: String): String? {
        val value = (this as? JsonObject)?.get(provider) as? JsonPrimitive ?: return null
        return value.content
    }

    /**
     * 自动将步骤输出索引到共享知识库
     *
     * 当 auto_index_outputs=true 且步骤输出非空时，
     * 以步骤名称为 documentId 将输出内容索引到知识库。
     */
    private suspend fun autoIndexStepOutput(
        workflow: WorkflowDefinition,
        context: WorkflowExecutionContext,
        stepName: String,
        output: String
    ) {
        val kb = workflow.knowledgeBase ?: return
        if (!kb.enabled || !kb.autoIndexOutputs) return

        val ragTools = context.sharedKnowledgeBase ?: return

        // 跳过过短的输出（避免无意义的索引）
        if (output.trim().length < 50) {
            logger.debug { "[Workflow:${workflow.name}] Skipping KB auto-index for step '$stepName' (output too short: ${output.trim().length} chars)" }
            return
        }

        // 检查文档数量限制
        if (kb.maxIndexedDocuments > 0) {
            try {
                val listResult = ragTools.listDocuments()
                val currentCount = Regex("""(\d+) document\(s\)""").find(listResult)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (currentCount >= kb.maxIndexedDocuments) {
                    logger.warn { "[Workflow:${workflow.name}] KB max_indexed_documents (${kb.maxIndexedDocuments}) reached, skipping auto-index for step '$stepName'" }
                    return
                }
            } catch (e: Exception) {
                logger.debug(e) { "KB document listing failed, proceeding to index" }
            }
        }

        try {
            val documentId = "step:$stepName"
            // Suspend variant — the enclosing step execution is already a coroutine.
            val result = ragTools.indexDocumentWithSourceSuspend(documentId, output, "workflow,step,$stepName", null)
            logger.info { "[Workflow:${workflow.name}] Auto-indexed step '$stepName' output to KB: $result" }

            // 发送监控事件
            if (enableMonitoring) {
                WorkflowMonitor.addEvent(
                    context.executionId,
                    stepName,
                    AgentEvent(
                        type = "knowledge_base_indexed",
                        category = "workflow",
                        subCategory = "knowledge_base",
                        summary = "📚 步骤输出已自动索引到共享知识库",
                        detail = "document_id=$documentId, output_length=${output.length}"
                    )
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "[Workflow:${workflow.name}] Failed to auto-index step '$stepName' output to KB" }
        }
    }

}

/**
 * 审批请求
 */
data class ApprovalRequest(
    val approvalId: String,
    val workflowName: String,
    val executionId: String,
    val stepName: String,
    val message: String,
    val approvers: List<String> = emptyList(),
    val requestedAt: Long,
    val timeout: String?,
    /**
     * 可审核分组列表(若 step 配置了 reviewable_actions)。已经把 source_var 指向的 JSON
     * 文件读出来塞进 items,审批 UI 直接渲染即可。每组保留 outputVar 以便审批人决定后引擎
     * 把编辑结果写回对应输出变量。
     */
    val reviewableGroups: List<ReviewablePayloadGroup> = emptyList()
)

/**
 * 单个可审核分组(运行时已加载好 items)。
 */
data class ReviewablePayloadGroup(
    val name: String,
    val sourceVar: String,
    val outputVar: String,
    val title: String? = null,
    val keyField: String? = null,
    val columns: List<ReviewableActionColumn> = emptyList(),
    /** 来自 source_var 文件的原始数组(JSON 元素列表)。 */
    val items: List<JsonElement> = emptyList(),
    /** source_var 文件本身的解析路径(便于 UI 提示/审计)。 */
    val sourcePath: String? = null
)

/**
 * 审批决策结果。`approved=false` 时 `edits` 必为 null。
 * `edits` 缺省表示审批人没有改任何东西,引擎按原样把 source 拷贝到 output。
 * 否则:每个分组内 items 即为审批人勾选+编辑后的子集 JSON 数组。
 */
data class ApprovalDecision(
    val approved: Boolean,
    val edits: Map<String, List<JsonElement>>? = null,
    val comment: String? = null
) {
    companion object {
        val APPROVED_AS_IS = ApprovalDecision(approved = true)
        val REJECTED = ApprovalDecision(approved = false)
    }
}

/**
 * 审批处理器接口
 */
interface ApprovalHandler {
    suspend fun requestApproval(request: ApprovalRequest): ApprovalDecision
}

/**
 * 内部步骤结果（简化版）
 */
private data class StepResult(
    val success: Boolean,
    val output: String? = null,
    val error: String? = null
)

private data class StateExecutionResult(
    val success: Boolean,
    val event: String,
    val output: String? = null,
    val error: String? = null
)
