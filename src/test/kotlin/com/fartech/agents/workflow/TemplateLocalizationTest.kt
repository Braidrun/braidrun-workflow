package com.fartech.agents.workflow

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemplateLocalizationTest {
    private val templatesDir = File("workflows/templates")
    private val supportedLocales = setOf(
        "en", "zh", "zhHant", "ja", "ko", "es", "fr", "de", "ar", "pt", "vi"
    )

    @Test
    fun `every shipped template has complete display translations`() {
        assertFalse(
            File(templatesDir, "test-text-summarizer.yaml").exists(),
            "The temporary text-summarizer test template must not be restored"
        )

        val templates = templateFiles()
        assertTrue(templates.isNotEmpty(), "No templates found in ${templatesDir.absolutePath}")

        templates.forEach { file ->
            val workflow = WorkflowParser.parseYaml(file.readText())
            assertEquals(
                supportedLocales,
                workflow.translations.keys,
                "${file.name} must declare exactly the supported display locales"
            )

            val canonicalDescription = workflow.description.orEmpty().replace(Regex("\\s+"), " ").trim()
            assertEquals(
                canonicalDescription,
                workflow.translations["en"]?.description,
                "${file.name} English display description must match the canonical description"
            )
            if (file.name.startsWith("test-")) {
                assertTrue(
                    workflow.translations["en"]?.name?.endsWith("Test") == true,
                    "${file.name} must have a human-readable English display name ending in 'Test'"
                )
            }

            supportedLocales.forEach { locale ->
                val translation = requireNotNull(workflow.translations[locale])
                assertTrue(!translation.name.isNullOrBlank(), "${file.name} has a blank $locale name")
                assertTrue(!translation.description.isNullOrBlank(), "${file.name} has a blank $locale description")
                if (locale != "en") {
                    assertFalse(
                        translation.description == canonicalDescription,
                        "${file.name} has an untranslated $locale description"
                    )
                }
            }

            workflow.agents.forEach { (agentId, agent) ->
                val agentDescription = agent.description?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                if (file.name.startsWith("test-workflow-")) {
                    assertTrue(
                        agentDescription.isNotBlank(),
                        "${file.name} agent $agentId must declare a display description"
                    )
                }
                if (agentDescription.isBlank()) return@forEach
                assertEquals(
                    supportedLocales,
                    agent.translations.keys,
                    "${file.name} agent $agentId must declare exactly the supported display locales"
                )
                assertEquals(
                    agentDescription,
                    agent.translations["en"]?.description,
                    "${file.name} agent $agentId English description must match its canonical description"
                )
                supportedLocales.forEach { locale ->
                    val localizedDescription = agent.translations[locale]?.description
                    assertTrue(
                        !localizedDescription.isNullOrBlank(),
                        "${file.name} agent $agentId has a blank $locale description"
                    )
                    if (locale != "en") {
                        assertFalse(
                            localizedDescription == agentDescription,
                            "${file.name} agent $agentId has an untranslated $locale description"
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `translated descriptions preserve technical identifiers`() {
        templateFiles().forEach { file ->
            val workflow = WorkflowParser.parseYaml(file.readText())
            val canonical = workflow.description.orEmpty().replace(Regex("\\s+"), " ").trim()
            val protectedTokens = protectedTokenPatterns
                .flatMap { pattern -> pattern.findAll(canonical).map { it.value }.toList() }
                .toSet()

            workflow.translations
                .filterKeys { it != "en" }
                .forEach { (locale, translation) ->
                    val localized = translation.description.orEmpty()
                    val missing = protectedTokens.filterNot(localized::contains)
                    assertTrue(
                        missing.isEmpty(),
                        "${file.name} $locale description changed technical identifiers: $missing"
                    )
                }
        }
    }

    @Test
    fun `runtime template content stays English`() {
        templateFiles().forEach { file ->
            var runtimeText = removeTranslationBlocks(file.readText())
            allowedNonEnglishFixtures.forEach { fixture -> runtimeText = runtimeText.replace(fixture, "") }
            assertFalse(
                nonEnglishScript.containsMatchIn(runtimeText),
                "${file.name} contains non-English runtime text outside translations"
            )
        }
    }

    private fun templateFiles(): List<File> =
        templatesDir.listFiles { file -> file.isFile && file.extension == "yaml" }
            ?.sortedBy { it.name }
            .orEmpty()

    private fun removeTranslationBlocks(source: String): String {
        val kept = mutableListOf<String>()
        var skippedIndent: Int? = null
        source.lineSequence().forEach { line ->
            val indent = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) line.length else it }
            val activeIndent = skippedIndent
            if (activeIndent != null) {
                if (line.isBlank() || indent > activeIndent) return@forEach
                skippedIndent = null
            }
            if (line.trim() == "translations:") {
                skippedIndent = indent
            } else {
                kept += line
            }
        }
        return kept.joinToString("\n")
    }

    companion object {
        private val protectedTokenPatterns = listOf(
            Regex("utility-module-[a-z0-9-]+"),
            Regex("\\b[A-Z][A-Z0-9_]{2,}\\b"),
            Regex("\\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\\b"),
            Regex("\\b[a-z0-9.-]+\\.(?:com|org|io)\\b"),
            Regex("\\b(?:filter|sort|select|limit|groupby|python_format|mustache)\\b")
        )
        private val nonEnglishScript = Regex("[\\u4E00-\\u9FFF\\u3040-\\u30FF\\uAC00-\\uD7AF\\u0600-\\u06FF]")
        private val allowedNonEnglishFixtures = listOf(
            "这是一段完全由简体中文组成的测试文本,用于检测语言识别能力。",
            "云计算正在深刻改变现代软件的构建、部署和运维方式。"
        )
    }
}
