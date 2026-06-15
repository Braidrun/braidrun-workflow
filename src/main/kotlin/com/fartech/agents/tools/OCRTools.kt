package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.fartech.ftapp2.commonsKt.AnsiColor
import com.fartech.ftapp2.commonsKt.printlnColor
import mu.KotlinLogging
import java.io.File
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

@LLMDescription(
    "OCR (Optical Character Recognition) tools for extracting text from images. " +
            "Uses Tesseract OCR engine which must be installed on the system. " +
            "On macOS: 'brew install tesseract'. On Ubuntu: 'apt install tesseract-ocr'. " +
            "On Windows: download from https://github.com/UB-Mannheim/tesseract/wiki. " +
            "For best results with non-English text, install additional language packs."
)
object OCRTools : ToolSet {

    private const val TIMEOUT_SECONDS = 60L
    private const val MAX_OUTPUT_LENGTH = 100_000
    private const val MAX_IMAGE_BYTES = 25L * 1024L * 1024L

    /**
     * Tesseract language codes we're willing to forward. Any input that doesn't match
     * is rejected before we call `tesseract`, which prevents an LLM from smuggling
     * command fragments or reading from a surprising `tessdata` directory. New
     * languages can be added here as they're installed in the exec image.
     */
    private val SAFE_LANGUAGE_TOKEN = Regex("^[a-z0-9_]{2,32}$", RegexOption.IGNORE_CASE)

    /**
     * Valid tesseract `--psm` values (see `tesseract --help-psm`). Anything outside this
     * range historically gets silently interpreted or errors out in confusing ways.
     */
    private val VALID_PSM = 0..13

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    /**
     * Validate the `language` argument. Accepts tokens like `eng`, `chi_sim`, and
     * `+`-joined combos like `eng+chi_sim`. Returns the normalized string on success;
     * returns `null` if validation fails (caller converts that into a user-facing error).
     */
    internal fun validateLanguage(language: String): String? {
        if (language.isBlank() || language.length > 128) return null
        val tokens = language.split('+').map { it.trim() }
        if (tokens.isEmpty() || tokens.any { !SAFE_LANGUAGE_TOKEN.matches(it) }) return null
        return tokens.joinToString("+")
    }

    internal fun validatePsm(pageSegMode: Int): Boolean = pageSegMode in VALID_PSM

    private fun validateImageInputPath(imagePath: String): File {
        val file = ToolPathSecurity.validateInputPath(imagePath)
        require(file.exists() && file.isFile) { "image file does not exist: $imagePath" }
        require(file.length() in 1..MAX_IMAGE_BYTES) {
            "image file size ${file.length()} bytes exceeds maximum allowed size $MAX_IMAGE_BYTES bytes"
        }
        return file
    }

    private fun findTesseract(): String {
        // Try common paths
        val candidates = if (isWindows()) {
            listOf(
                "tesseract",
                "C:\\Program Files\\Tesseract-OCR\\tesseract.exe",
                "C:\\Program Files (x86)\\Tesseract-OCR\\tesseract.exe"
            )
        } else {
            listOf("tesseract", "/usr/bin/tesseract", "/usr/local/bin/tesseract", "/opt/homebrew/bin/tesseract")
        }

        for (candidate in candidates) {
            try {
                // Phase 11: route through SubprocessSafety so the version probe drains
                // stdout on a background thread (otherwise tesseract's banner on a slow
                // exec spam can wedge the pipe and we hang inside waitFor).
                val r = com.fartech.agents.commons.SubprocessSafety.runCapturedWithTimeout(
                    command = listOf(candidate, "--version"),
                    timeoutSeconds = 5,
                    maxOutputBytes = 16 * 1024
                )
                if (!r.timedOut && r.exitCode == 0) return candidate
            } catch (e: Exception) {
                logger.warn(e) { "OCR processing failed" }
                continue
            }
        }
        return "tesseract" // fallback, will error if not found
    }

    @Tool
    @LLMDescription(
        "Extract text from an image using Tesseract OCR. " +
                "Supports multiple languages. The image should be clear and well-lit for best results. " +
                "Returns the recognized text content."
    )
    fun extractTextFromImage(
        @LLMDescription("Path to the image file (PNG, JPEG, TIFF, BMP)")
        imagePath: String,
        @LLMDescription("OCR language(s), e.g., 'eng' for English, 'chi_sim' for Simplified Chinese, 'eng+chi_sim' for both (default 'eng')")
        language: String = "eng",
        @LLMDescription("Page segmentation mode (0-13, default 3 = fully automatic). Use 6 for single block of text, 7 for single line.")
        pageSegMode: Int = 3
    ): String {
        return try {
            val file = validateImageInputPath(imagePath)

            val safeLanguage = validateLanguage(language)
                ?: return "Error: invalid OCR language '$language'. Use tokens like 'eng', 'chi_sim', or '+'-joined combos."
            if (!validatePsm(pageSegMode)) {
                return "Error: invalid --psm value '$pageSegMode'. Must be between ${VALID_PSM.first} and ${VALID_PSM.last}."
            }

            val tesseract = findTesseract()
            printlnColor(AnsiColor.CYAN, "[OCR] Extracting text from: $imagePath (lang=$safeLanguage)")

            // Phase 11 hardening: secure-perms temp file. `File.createTempFile` honours
            // the JVM umask (default 0644), so on shared hosts another local user can
            // read the OCR'd content before it's deleted — and OCR'd content frequently
            // contains PII (passport scans, receipts). [SecureTempFile] sets `0600`
            // immediately after creation.
            val outputBase = com.fartech.agents.commons.SecureTempFile
                .create("ocr_output_", "").absolutePath
            // Phase 11 hardening: was `readText` BEFORE `waitFor` — if tesseract spammed
            // stderr (merged via redirectErrorStream) past pipe buffer before exiting,
            // the read blocked forever and the timeout never fired. SubprocessSafety
            // drains the stream on a background thread so the timeout actually applies.
            val r = com.fartech.agents.commons.SubprocessSafety.runCapturedWithTimeout(
                command = listOf(
                    tesseract,
                    file.absolutePath,
                    outputBase,
                    "-l", safeLanguage,
                    "--psm", pageSegMode.toString()
                ),
                timeoutSeconds = TIMEOUT_SECONDS,
                maxOutputBytes = 1 * 1024 * 1024
            )
            val stderr = r.output
            if (r.timedOut) {
                return "Error: OCR timed out after ${TIMEOUT_SECONDS}s"
            }

            val outputFile = File("$outputBase.txt")
            if (!outputFile.exists()) {
                return "Error: OCR failed. Tesseract output:\n$stderr"
            }

            val text = outputFile.readText().trim()
            outputFile.delete()
            File(outputBase).delete()

            if (text.isBlank()) {
                "No text detected in image. The image may be unclear or contain no recognizable text."
            } else {
                val truncated = if (text.length > MAX_OUTPUT_LENGTH) {
                    text.take(MAX_OUTPUT_LENGTH) + "\n... [OUTPUT TRUNCATED]"
                } else {
                    text
                }
                "OCR result (${text.length} chars):\n$truncated"
            }
        } catch (e: Exception) {
            "Error performing OCR: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "Extract text from an image and save to a file. " +
                "Useful for batch processing or when you need to preserve the OCR output."
    )
    fun extractTextToFile(
        @LLMDescription("Path to the image file")
        imagePath: String,
        @LLMDescription("Path to save the extracted text")
        outputPath: String,
        @LLMDescription("OCR language(s) (default 'eng')")
        language: String = "eng",
        @LLMDescription("Page segmentation mode (default 3)")
        pageSegMode: Int = 3
    ): String {
        return try {
            val file = validateImageInputPath(imagePath)

            val safeLanguage = validateLanguage(language)
                ?: return "Error: invalid OCR language '$language'. Use tokens like 'eng', 'chi_sim', or '+'-joined combos."
            if (!validatePsm(pageSegMode)) {
                return "Error: invalid --psm value '$pageSegMode'. Must be between ${VALID_PSM.first} and ${VALID_PSM.last}."
            }

            val tesseract = findTesseract()
            printlnColor(AnsiColor.CYAN, "[OCR] Extracting text from: $imagePath -> $outputPath")

            // Tesseract adds .txt extension automatically, so we remove it for the -o param
            val resultFile = ToolPathSecurity.validateOutputPath(
                if (outputPath.endsWith(".txt", ignoreCase = true)) outputPath else "$outputPath.txt"
            )
            val outputBase = if (resultFile.path.endsWith(".txt", ignoreCase = true)) resultFile.path.dropLast(4) else resultFile.path

            val r2 = com.fartech.agents.commons.SubprocessSafety.runCapturedWithTimeout(
                command = listOf(
                    tesseract,
                    file.absolutePath,
                    outputBase,
                    "-l", safeLanguage,
                    "--psm", pageSegMode.toString()
                ),
                timeoutSeconds = TIMEOUT_SECONDS,
                maxOutputBytes = 1 * 1024 * 1024
            )
            val stderr = r2.output
            if (r2.timedOut) {
                return "Error: OCR timed out after ${TIMEOUT_SECONDS}s"
            }

            if (!resultFile.exists()) {
                return "Error: OCR failed. Tesseract output:\n$stderr"
            }

            val textLength = resultFile.readText().trim().length
            "Successfully extracted text ($textLength chars) to: ${resultFile.absolutePath}"
        } catch (e: Exception) {
            "Error performing OCR: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("List available Tesseract OCR languages installed on this system.")
    fun listOCRLanguages(): String {
        return try {
            val tesseract = findTesseract()
            val r = com.fartech.agents.commons.SubprocessSafety.runCapturedWithTimeout(
                command = listOf(tesseract, "--list-langs"),
                timeoutSeconds = 10,
                maxOutputBytes = 256 * 1024
            )
            if (r.timedOut || r.exitCode != 0) {
                "Error: could not list languages. Is Tesseract installed?"
            } else {
                "Available OCR languages:\n${r.output}"
            }
        } catch (e: Exception) {
            "Error listing OCR languages: ${e.message}. Tesseract may not be installed."
        }
    }

    @Tool
    @LLMDescription("Check if Tesseract OCR is installed and return its version information.")
    fun checkOCRInstallation(): String {
        return try {
            val tesseract = findTesseract()
            val r = com.fartech.agents.commons.SubprocessSafety.runCapturedWithTimeout(
                command = listOf(tesseract, "--version"),
                timeoutSeconds = 10,
                maxOutputBytes = 16 * 1024
            )
            if (r.timedOut || r.exitCode != 0) {
                "Tesseract OCR is NOT installed or not accessible.\n" +
                        "Install instructions:\n" +
                        "  macOS: brew install tesseract\n" +
                        "  Ubuntu: sudo apt install tesseract-ocr\n" +
                        "  Windows: Download from https://github.com/UB-Mannheim/tesseract/wiki"
            } else {
                "Tesseract OCR is installed:\n${r.output}"
            }
        } catch (e: Exception) {
            "Tesseract OCR is NOT installed: ${e.message}\n" +
                    "Install instructions:\n" +
                    "  macOS: brew install tesseract\n" +
                    "  Ubuntu: sudo apt install tesseract-ocr\n" +
                    "  Windows: Download from https://github.com/UB-Mannheim/tesseract/wiki"
        }
    }
}
