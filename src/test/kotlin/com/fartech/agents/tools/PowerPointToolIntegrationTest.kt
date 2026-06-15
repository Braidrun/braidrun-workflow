package com.fartech.agents.tools

import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.io.FileInputStream
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

// Intentionally exercises deprecated tier classes; suppress warnings at the
// class level. Will be simplified when the tier classes are finally removed.
@Suppress("DEPRECATION")
class PowerPointToolIntegrationTest {

    private val basicTools = PowerPointTools
    private val advancedTools = PowerPointAdvancedTools()
    private val enhancedTools = PowerPointEnhancedTools()

    @Test
    fun coversAllPowerPointCreationAndEditingTools(@TempDir tempDir: Path) {
        val assetsDir = tempDir.resolve("assets").createDirectories()
        val exportsDir = tempDir.resolve("exports")
        val slidePng = tempDir.resolve("rendered-slide.png").toFile()
        val imageA = createSampleImage(
            assetsDir.resolve("hero.png").toFile(),
            width = 1200,
            height = 675,
            background = Color(17, 54, 94),
            accent = Color(255, 196, 61),
            label = "Braidrun Agent"
        )
        val imageB = createSampleImage(
            assetsDir.resolve("diagram.png").toFile(),
            width = 900,
            height = 900,
            background = Color(235, 243, 249),
            accent = Color(45, 125, 210),
            label = "Workflow Diagram"
        )

        val deck = tempDir.resolve("powerpoint-tool-coverage.pptx").toFile()
        assertSuccess(advancedTools.createPresentation("PowerPoint Tool Coverage", deck.absolutePath))
        assertEquals(1, slideCount(deck))

        val listBefore = basicTools.listSlides(deck.absolutePath)
        assertTrue(listBefore.contains("PowerPoint Tool Coverage"))

        assertSuccess(
            basicTools.addSlideWithText(
                deck.absolutePath,
                deck.absolutePath,
                "Agenda",
                "1. Core tools\n2. Advanced tools\n3. Enhanced tools"
            )
        )
        assertEquals(2, slideCount(deck))

        assertSuccess(
            advancedTools.addTableToSlide(
                deck.absolutePath,
                slideIndex = 1,
                x = 40,
                y = 150,
                rows = 3,
                cols = 3,
                csv = "Tool,Category,Status\ncreatePresentation,advanced,ok\naddRichSlide,enhanced,ok",
                outputPath = deck.absolutePath
            )
        )
        assertSuccess(
            advancedTools.addBulletList(
                deck.absolutePath,
                slideIndex = 1,
                x = 360,
                y = 150,
                width = 260,
                height = 180,
                items = listOf("List slides", "Render previews", "Merge decks"),
                outputPath = deck.absolutePath
            )
        )
        assertSuccess(
            advancedTools.addImageToSlide(
                deck.absolutePath,
                slideIndex = 1,
                imagePath = imageA.absolutePath,
                x = 40,
                y = 260,
                width = 260,
                height = 146,
                outputPath = deck.absolutePath
            )
        )

        assertSuccess(
            basicTools.renderSlideToImage(
                deck.absolutePath,
                slideIndex = 1,
                outputImagePath = slidePng.absolutePath,
                width = 1280,
                height = 720
            )
        )
        assertTrue(slidePng.exists())
        assertEquals(1280, ImageIO.read(slidePng).width)
        assertEquals(720, ImageIO.read(slidePng).height)

        assertSuccess(advancedTools.duplicateSlide(deck.absolutePath, 1, deck.absolutePath))
        assertEquals(3, slideCount(deck))

        val mergedDeck = tempDir.resolve("merged.pptx").toFile()
        assertSuccess(
            advancedTools.mergePresentations(
                listOf(deck.absolutePath, deck.absolutePath),
                mergedDeck.absolutePath
            )
        )
        assertEquals(6, slideCount(mergedDeck))

        assertSuccess(
            advancedTools.exportAllSlidesToImages(
                mergedDeck.absolutePath,
                exportsDir.toString(),
                width = 960,
                height = 540
            )
        )
        assertEquals(6, exportsDir.listDirectoryEntries("*.png").size)

        val enhancedDeck = tempDir.resolve("enhanced.pptx").toFile()
        assertSuccess(enhancedTools.createEnhancedPresentation(enhancedDeck.absolutePath, widthPt = 720, heightPt = 405))
        assertSuccess(
            enhancedTools.addRichSlide(
                pptxPath = enhancedDeck.absolutePath,
                outputPath = enhancedDeck.absolutePath,
                title = "Braidrun Agent for Beginners",
                body = "Understand the runtime\\nUse the right tools\\nShip real outputs",
                titleFont = "Arial",
                titleColor = "#0B2948",
                bodyColor = "#24415C"
            )
        )
        assertEquals(1, slideCount(enhancedDeck))

        assertSuccess(enhancedTools.setSlideBackground(enhancedDeck.absolutePath, 0, "#F4F8FB", enhancedDeck.absolutePath))
        assertSuccess(
            enhancedTools.addStyledTextBox(
                pptxPath = enhancedDeck.absolutePath,
                slideIndex = 0,
                text = "A runtime that lets agents use real tools.",
                x = 50,
                y = 120,
                width = 320,
                height = 70,
                outputPath = enhancedDeck.absolutePath,
                fontFamily = "Arial",
                fontSize = 18.0,
                bold = true,
                color = "#123E63",
                bgColor = "#DCEAF6"
            )
        )
        assertSuccess(
            enhancedTools.addShape(
                pptxPath = enhancedDeck.absolutePath,
                slideIndex = 0,
                shapeType = "ROUND_RECT",
                x = 410,
                y = 110,
                width = 240,
                height = 120,
                outputPath = enhancedDeck.absolutePath,
                fillColor = "#1F6FB2",
                text = "Agent + Tools",
                textFont = "Arial",
                textFontSize = 20.0
            )
        )
        assertSuccess(
            enhancedTools.addBulletListSlide(
                pptxPath = enhancedDeck.absolutePath,
                outputPath = enhancedDeck.absolutePath,
                title = "Capability Snapshot",
                items = listOf(
                    "Generate Office documents",
                    "Run shell or file tools",
                    "Compose workflows",
                    "Scale from simple prompts to multi-step automation"
                ),
                titleColor = "#0B2948",
                bulletColor = "#1F4262"
            )
        )
        assertEquals(2, slideCount(enhancedDeck))

        assertSuccess(
            enhancedTools.addEnhancedImageToSlide(
                pptxPath = enhancedDeck.absolutePath,
                slideIndex = 1,
                imagePath = imageB.absolutePath,
                x = 420,
                y = 85,
                width = 250,
                height = 250,
                outputPath = enhancedDeck.absolutePath
            )
        )
        assertSuccess(
            enhancedTools.addStyledTableToSlide(
                pptxPath = enhancedDeck.absolutePath,
                slideIndex = 1,
                csvText = "Metric,Value\nTools Tested,25\nRich Media,Yes\nSlides Exported,6",
                x = 40,
                y = 180,
                width = 320,
                height = 140,
                outputPath = enhancedDeck.absolutePath,
                headerBgColor = "#0B2948"
            )
        )
        assertSuccess(
            enhancedTools.addBackgroundImage(
                pptxPath = enhancedDeck.absolutePath,
                slideIndex = 1,
                imagePath = imageA.absolutePath,
                outputPath = enhancedDeck.absolutePath
            )
        )

        val info = enhancedTools.getPresentationInfo(enhancedDeck.absolutePath)
        assertTrue(info.contains("Total Slides: 2"))
        assertTrue(info.contains("XSLF"))

        val movedDeck = tempDir.resolve("enhanced-moved.pptx").toFile()
        assertSuccess(enhancedTools.moveSlide(enhancedDeck.absolutePath, 1, 0, movedDeck.absolutePath))
        assertEquals(2, slideCount(movedDeck))

        val clearedDeck = tempDir.resolve("enhanced-cleared.pptx").toFile()
        assertSuccess(enhancedTools.clearSlide(movedDeck.absolutePath, 1, clearedDeck.absolutePath))
        assertEquals(2, slideCount(clearedDeck))

        val deletedDeck = tempDir.resolve("enhanced-deleted.pptx").toFile()
        assertSuccess(enhancedTools.deleteSlide(clearedDeck.absolutePath, 1, deletedDeck.absolutePath))
        assertEquals(1, slideCount(deletedDeck))
    }

    @Test
    fun updatesSpeakerNotesWhenTemplateAlreadyContainsNotes(@TempDir tempDir: Path) {
        // Build a deck whose slide already carries a notes part: addSpeakerNotes
        // can only update existing notes (POI cannot create the notes part).
        val notesSource = tempDir.resolve("notes-template.pptx").toFile()
        XMLSlideShow().use { show ->
            val slide = show.createSlide()
            val seedNotes = show.getNotesSlide(slide)
            seedNotes.placeholders.firstOrNull()?.let { body ->
                body.clearText()
                body.addNewTextParagraph().addNewTextRun().setText("Seed note")
            }
            notesSource.outputStream().use { show.write(it) }
        }

        val updatedDeck = tempDir.resolve("notes-updated.pptx").toFile()
        val noteText = "Integration test note for Braidrun Agent beginners."
        assertSuccess(
            enhancedTools.addSpeakerNotes(
                pptxPath = notesSource.absolutePath,
                slideIndex = 0,
                notes = noteText,
                outputPath = updatedDeck.absolutePath
            )
        )
        assertTrue(updatedDeck.exists())
        assertTrue(zipContainsText(updatedDeck, noteText))
    }

    private fun assertSuccess(result: String) {
        assertTrue(
            !result.startsWith("❌") && !result.startsWith("Error"),
            "Expected success but got: $result"
        )
    }

    private fun slideCount(file: File): Int =
        FileInputStream(file).use { fis ->
            XMLSlideShow(fis).use { it.slides.size }
        }

    private fun createSampleImage(
        output: File,
        width: Int,
        height: Int,
        background: Color,
        accent: Color,
        label: String
    ): File {
        output.parentFile?.mkdirs()
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = background
        g.fillRect(0, 0, width, height)
        g.color = accent
        g.fillRoundRect(60, 60, width - 120, height - 120, 48, 48)
        g.color = Color.WHITE
        g.font = Font("Arial", Font.BOLD, (width * 0.045).toInt())
        val fm = g.fontMetrics
        val textWidth = fm.stringWidth(label)
        g.drawString(label, (width - textWidth) / 2, height / 2)
        g.dispose()
        ImageIO.write(image, output.extension.ifEmpty { "png" }, output)
        return output
    }

    private fun zipContainsText(file: File, text: String): Boolean =
        ZipFile(file).use { zip ->
            zip.entries().asSequence().any { entry ->
                entry.name.contains("notesSlide") &&
                    zip.getInputStream(entry).bufferedReader().use { it.readText().contains(text) }
            }
        }
}
