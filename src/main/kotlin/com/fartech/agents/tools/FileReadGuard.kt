package com.fartech.agents.tools

import mu.KotlinLogging
import java.io.File
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Shared read-path validation enforcing constraint C9 (file read/write allowlists).
 *
 * Used by both [SafeFileTools] (direct `readFile` tool) and
 * [com.fartech.agents.commons.SandboxedFileSystemProvider] (Koog `ReadFileTool`
 * via the `readBytes`/`inputStream` overrides).
 *
 * ## Policy
 *
 * 1. **Extension whitelist** — only files whose lowercase extension is in
 *    [DEFAULT_READ_EXTENSION_WHITELIST] may be read. Extensionless files are allowed
 *    (many config/script files have no extension).
 * 2. **Filename blacklist** — files whose lowercase name matches any regex in
 *    [DEFAULT_FILENAME_BLACKLIST] are always blocked, even if the extension is whitelisted.
 *    This catches `.env`, private keys, SSH/AWS/GPG config, credential files, etc.
 *
 * When [enabled] is `false` (dev mode), all reads are permitted — preserving local
 * developer experience (constraint C1).
 */
class FileReadGuard(
    val enabled: Boolean,
    val extensionWhitelist: Set<String> = DEFAULT_READ_EXTENSION_WHITELIST,
    val filenameBlacklist: List<Regex> = DEFAULT_FILENAME_BLACKLIST
) {

    /**
     * Validate a path for reading. Throws [SecurityException] if blocked.
     * No-op when [enabled] is false (dev mode).
     */
    fun validateReadPath(path: Path) {
        if (!enabled) return
        validateReadFile(path.toFile().canonicalFile)
    }

    /**
     * Validate a [File] for reading. Throws [SecurityException] if blocked.
     * No-op when [enabled] is false (dev mode).
     */
    fun validateReadFile(file: File) {
        if (!enabled) return

        val name = file.name.lowercase()

        // 1. Filename blacklist (checked first — overrides extension whitelist)
        filenameBlacklist.firstOrNull { it.matches(name) }?.let {
            logger.warn { "[FileReadGuard/C9] Read denied for blacklisted filename: $name" }
            throw SecurityException(
                "Read denied: filename '$name' is blacklisted for security reasons. " +
                    "Sensitive files (.env, private keys, SSH/AWS config, credentials) cannot be read."
            )
        }

        // 2. Extension whitelist (extensionless files are allowed)
        val ext = file.extension.lowercase()
        if (ext.isNotEmpty() && ext !in extensionWhitelist) {
            logger.warn { "[FileReadGuard/C9] Read denied for extension .$ext: ${file.path}" }
            throw SecurityException(
                "Read denied: file extension '.$ext' is not in the read whitelist. " +
                    "Allowed extensions: ${extensionWhitelist.sorted().joinToString(", ")}. " +
                    "If this file type is needed, contact an administrator to update the whitelist."
            )
        }
    }

    companion object {
        /**
         * Allowed read extensions. We deliberately keep config formats like `.properties`,
         * `.toml`, `.ini` out of this set — those files overwhelmingly carry secrets in
         * real repos (database URLs, API keys) and the filename blacklist below can't
         * catch every workload-specific naming convention (e.g. `prod-api.properties`).
         *
         * Operators that legitimately need `.properties` can override via a custom
         * `FileReadGuard(..., extensionWhitelist = DEFAULT_READ_EXTENSION_WHITELIST + setOf("properties"))`.
         *
         * `.yaml`/`.yml`/`.json` remain in — they're the workflow / config surface the
         * tools are designed to operate on, and blocking them would break the common
         * case. But the filename blacklist is widened to catch `*secret*`, `*token*`,
         * `*api*key*`, `*credentials*` patterns regardless of extension.
         */
        val DEFAULT_READ_EXTENSION_WHITELIST = setOf(
            // Text / code
            "txt", "md", "json", "yaml", "yml", "xml", "csv", "tsv",
            "py", "js", "mjs", "cjs", "ts", "tsx", "jsx",
            "kt", "java", "sh", "rb", "go", "rs",
            "html", "htm", "css", "scss", "sass", "less", "sql", "log",
            // Modern web frontends (component / framework files; plain text)
            "vue", "svelte", "astro",
            // Apple / iOS toolchain (all plain text: Swift + Objective-C sources,
            // XML/JSON-shaped project & resource files). Without these an agent could
            // write `Foo.swift` but not read it back, leaving every coder / reviewer /
            // auditor step half-blind on iOS pipelines.
            "swift", "m", "mm", "h", "modulemap", "podspec",
            "plist", "xcprivacy", "xcstrings", "strings", "stringsdict",
            "pbxproj", "xcconfig", "entitlements", "xcscheme", "xcworkspacedata", "resolved",
            "storyboard", "xib",
            // Web manifests (PWA / browser config — JSON-shaped, W3C-blessed
            // extensions). 2026-04-26: `.webmanifest` was the literal cause
            // of execution-process-868c6031-...-failed.yaml (PWA build agent
            // tried to write `manifest.webmanifest` and got blocked).
            "webmanifest", "ico",
            // Documents (read-only binary)
            "pdf", "docx", "xlsx", "pptx", "odt",
            // Images (read-only binary)
            "png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "bmp", "tiff",
            // Archives
            "zip", "tar", "gz", "bz2", "xz"
            // Intentionally excluded: properties, toml, ini (too credential-prone)
        )

        /**
         * Filename patterns that always block reads regardless of extension.
         *
         * Widened from the previous 13 patterns to also catch:
         *   - Any file whose *name* contains `secret`, `token`, `credential`,
         *     `api_key` / `apikey`, or `password`
         *   - Kubeconfig and docker config defaults
         *   - macOS/Windows credential caches
         */
        val DEFAULT_FILENAME_BLACKLIST = listOf(
            Regex("""^\.env(\..*)?$"""),            // .env, .env.local, .env.production
            Regex("""^id_[a-z0-9_]+$"""),            // id_rsa, id_ed25519, id_ecdsa
            Regex(""".*\.pem$"""),                    // Private keys / certificates
            Regex(""".*\.key$"""),                    // Private key files
            Regex(""".*\.p12$"""),                    // PKCS#12 keystores
            Regex(""".*\.pfx$"""),                    // Windows certificate exports
            Regex(""".*\.jks$"""),                    // Java keystores
            Regex(""".*\.keystore$"""),               // Generic keystores
            Regex("""^known_hosts$"""),               // SSH known hosts
            Regex("""^authorized_keys$"""),           // SSH authorized keys
            Regex("""^\.git.*"""),                    // .git, .gitconfig, .git-credentials
            Regex("""^\.ssh.*"""),                    // .ssh, .ssh/config
            Regex("""^\.aws.*"""),                    // .aws, .aws/credentials
            Regex("""^\.gnupg.*"""),                  // .gnupg, .gnupg/secring.gpg
            Regex("""^\.kube.*"""),                   // kubeconfig
            Regex("""^\.docker.*"""),                 // docker config
            Regex("""^credentials$"""),               // Generic credentials file
            Regex("""^\.npmrc$"""),                   // npm registry tokens
            Regex("""^\.pypirc$"""),                  // PyPI registry tokens
            Regex("""^\.netrc$"""),                   // curl/ftp credentials
            // Content-based keyword patterns — match any file whose name contains the
            // terms regardless of extension. Catches `prod-api-key.json`,
            // `customer_tokens.csv`, `wallet-secrets.txt`, etc.
            Regex(""".*(?:^|[^a-z0-9])secrets?(?:[^a-z0-9].*|$)"""),
            Regex(""".*(?:^|[^a-z0-9])tokens?(?:[^a-z0-9].*|$)"""),
            Regex(""".*(?:^|[^a-z0-9])credential(?:s)?(?:[^a-z0-9].*|$)"""),
            Regex(""".*(?:^|[^a-z0-9])api[_-]?keys?(?:[^a-z0-9].*|$)"""),
            Regex(""".*(?:^|[^a-z0-9])passwords?(?:[^a-z0-9].*|$)""")
        )

        /** Dev mode: all reads permitted, no validation. */
        val DISABLED = FileReadGuard(enabled = false)
    }
}
