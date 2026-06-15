package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@LLMDescription(
    "Image processing tools for resizing, cropping, rotating, converting, compressing, " +
            "and merging images. Uses Java's built-in imaging libraries (no external dependencies). " +
            "Supports PNG, JPEG, BMP, GIF, and TIFF formats."
)
object ImageProcessingTools : ToolSet {

    private fun getFormatName(path: String): String {
        val ext = File(path).extension.lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "JPEG"
            "png" -> "PNG"
            "bmp" -> "BMP"
            "gif" -> "GIF"
            "tiff", "tif" -> "TIFF"
            "webp" -> "WEBP"
            else -> "PNG"
        }
    }

    @Tool
    @LLMDescription("Get detailed information about an image file (dimensions, format, color depth, file size).")
    fun getImageInfo(
        @LLMDescription("Path to the image file")
        path: String
    ): String {
        return try {
            val file = try {
                ToolPathSecurity.validateInputPath(path)
            } catch (e: SecurityException) {
                return "Error: ${e.message}"
            }
            if (!file.exists()) return "Error: file does not exist: $path"

            val readers = ImageIO.getImageReadersBySuffix(file.extension)
            val format = if (readers.hasNext()) readers.next().formatName else "Unknown"

            val image = ImageIO.read(file) ?: return "Error: could not read image: $path"
            val sb = StringBuilder()
            sb.appendLine("Path: ${file.absolutePath}")
            sb.appendLine("Format: $format")
            sb.appendLine("Width: ${image.width} px")
            sb.appendLine("Height: ${image.height} px")
            sb.appendLine("Color model: ${image.colorModel.numComponents} components, ${image.colorModel.pixelSize} bits/pixel")
            sb.appendLine("Has alpha: ${image.colorModel.hasAlpha()}")
            sb.appendLine("File size: ${file.length()} bytes (${formatSize(file.length())})")
            sb.toString()
        } catch (e: Exception) {
            "Error getting image info: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "Resize an image to specified dimensions. Maintains aspect ratio if only width or height is specified " +
                "(set the other to 0 or -1)."
    )
    fun resizeImage(
        @LLMDescription("Path to the input image")
        inputPath: String,
        @LLMDescription("Path to save the resized image")
        outputPath: String,
        @LLMDescription("Target width in pixels (use 0 or -1 to auto-calculate from height while maintaining aspect ratio)")
        width: Int,
        @LLMDescription("Target height in pixels (use 0 or -1 to auto-calculate from width while maintaining aspect ratio)")
        height: Int
    ): String {
        return try {
            val inFile = try {
                ToolPathSecurity.validateInputPath(inputPath)
            } catch (e: SecurityException) {
                return "Error: ${e.message}"
            }
            val image = ImageIO.read(inFile) ?: return "Error: could not read image: $inputPath"

            val targetWidth: Int
            val targetHeight: Int
            if (width <= 0 && height <= 0) {
                return "Error: at least one of width or height must be positive"
            } else if (width <= 0) {
                targetHeight = height
                targetWidth = (image.width.toDouble() / image.height * height).toInt()
            } else if (height <= 0) {
                targetWidth = width
                targetHeight = (image.height.toDouble() / image.width * width).toInt()
            } else {
                targetWidth = width
                targetHeight = height
            }

            val resized =
                BufferedImage(targetWidth, targetHeight, image.type.takeIf { it != 0 } ?: BufferedImage.TYPE_INT_ARGB)
            val g2d = resized.createGraphics()
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2d.drawImage(image, 0, 0, targetWidth, targetHeight, null)
            g2d.dispose()

            val outFile = ToolPathSecurity.validateOutputPath(outputPath)
            ImageIO.write(resized, getFormatName(outputPath), outFile)
            "Successfully resized image to ${targetWidth}x${targetHeight}: ${outFile.absolutePath}"
        } catch (e: Exception) {
            "Error resizing image: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Crop an image to a specified rectangular region.")
    fun cropImage(
        @LLMDescription("Path to the input image")
        inputPath: String,
        @LLMDescription("Path to save the cropped image")
        outputPath: String,
        @LLMDescription("X coordinate of the top-left corner of the crop region")
        x: Int,
        @LLMDescription("Y coordinate of the top-left corner of the crop region")
        y: Int,
        @LLMDescription("Width of the crop region in pixels")
        width: Int,
        @LLMDescription("Height of the crop region in pixels")
        height: Int
    ): String {
        return try {
            val inFile = try {
                ToolPathSecurity.validateInputPath(inputPath)
            } catch (e: SecurityException) {
                return "Error: ${e.message}"
            }
            val image = ImageIO.read(inFile) ?: return "Error: could not read image: $inputPath"

            if (x < 0 || y < 0 || width <= 0 || height <= 0) return "Error: invalid crop dimensions"
            if (x + width > image.width || y + height > image.height) {
                return "Error: crop region exceeds image bounds (image: ${image.width}x${image.height})"
            }

            val cropped = image.getSubimage(x, y, width, height)
            val outFile = ToolPathSecurity.validateOutputPath(outputPath)
            ImageIO.write(cropped, getFormatName(outputPath), outFile)
            "Successfully cropped image to ${width}x${height} at ($x,$y): ${outFile.absolutePath}"
        } catch (e: Exception) {
            "Error cropping image: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Rotate an image by a specified angle in degrees (clockwise).")
    fun rotateImage(
        @LLMDescription("Path to the input image")
        inputPath: String,
        @LLMDescription("Path to save the rotated image")
        outputPath: String,
        @LLMDescription("Rotation angle in degrees (clockwise). Common values: 90, 180, 270")
        degrees: Double
    ): String {
        return try {
            val inFile = try {
                ToolPathSecurity.validateInputPath(inputPath)
            } catch (e: SecurityException) {
                return "Error: ${e.message}"
            }
            val image = ImageIO.read(inFile) ?: return "Error: could not read image: $inputPath"

            val radians = Math.toRadians(degrees)
            val sin = abs(sin(radians))
            val cos = abs(cos(radians))
            val newWidth = (image.width * cos + image.height * sin).toInt()
            val newHeight = (image.width * sin + image.height * cos).toInt()

            val rotated =
                BufferedImage(newWidth, newHeight, image.type.takeIf { it != 0 } ?: BufferedImage.TYPE_INT_ARGB)
            val g2d = rotated.createGraphics()
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)

            val transform = AffineTransform()
            transform.translate(newWidth / 2.0, newHeight / 2.0)
            transform.rotate(radians)
            transform.translate(-image.width / 2.0, -image.height / 2.0)
            g2d.transform = transform
            g2d.drawImage(image, 0, 0, null)
            g2d.dispose()

            val outFile = ToolPathSecurity.validateOutputPath(outputPath)
            ImageIO.write(rotated, getFormatName(outputPath), outFile)
            "Successfully rotated image by ${degrees}° to ${newWidth}x${newHeight}: ${outFile.absolutePath}"
        } catch (e: Exception) {
            "Error rotating image: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Convert an image from one format to another (e.g., PNG to JPEG, BMP to PNG).")
    fun convertImageFormat(
        @LLMDescription("Path to the input image")
        inputPath: String,
        @LLMDescription("Path to save the converted image (format determined by file extension, e.g., '.jpg', '.png')")
        outputPath: String
    ): String {
        return try {
            val inFile = try {
                ToolPathSecurity.validateInputPath(inputPath)
            } catch (e: SecurityException) {
                return "Error: ${e.message}"
            }
            val image = ImageIO.read(inFile) ?: return "Error: could not read image: $inputPath"
            val outputFormat = getFormatName(outputPath)

            // If converting to JPEG, need to remove alpha channel
            val outputImage = if (outputFormat == "JPEG" && image.colorModel.hasAlpha()) {
                val rgb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
                val g = rgb.createGraphics()
                g.color = Color.WHITE
                g.fillRect(0, 0, image.width, image.height)
                g.drawImage(image, 0, 0, null)
                g.dispose()
                rgb
            } else {
                image
            }

            val outFile = ToolPathSecurity.validateOutputPath(outputPath)
            ImageIO.write(outputImage, outputFormat, outFile)
            "Successfully converted image to $outputFormat: ${outFile.absolutePath} (${formatSize(outFile.length())})"
        } catch (e: Exception) {
            "Error converting image: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Compress a JPEG image with a specified quality factor (0.0 to 1.0).")
    fun compressImage(
        @LLMDescription("Path to the input image")
        inputPath: String,
        @LLMDescription("Path to save the compressed image (should be .jpg/.jpeg)")
        outputPath: String,
        @LLMDescription("Compression quality from 0.0 (maximum compression, lowest quality) to 1.0 (minimum compression, highest quality)")
        quality: Float = 0.7f
    ): String {
        return try {
            val inFile = try {
                ToolPathSecurity.validateInputPath(inputPath)
            } catch (e: SecurityException) {
                return "Error: ${e.message}"
            }
            val image = ImageIO.read(inFile) ?: return "Error: could not read image: $inputPath"

            // Convert to RGB if needed (JPEG doesn't support alpha)
            val rgbImage = if (image.colorModel.hasAlpha()) {
                val rgb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
                val g = rgb.createGraphics()
                g.color = Color.WHITE
                g.fillRect(0, 0, image.width, image.height)
                g.drawImage(image, 0, 0, null)
                g.dispose()
                rgb
            } else {
                image
            }

            val outFile = ToolPathSecurity.validateOutputPath(outputPath)

            val writer = ImageIO.getImageWritersByFormatName("JPEG").next()
            try {
                val param = writer.defaultWriteParam
                param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                param.compressionQuality = quality.coerceIn(0.0f, 1.0f)

                ImageIO.createImageOutputStream(outFile).use { ios ->
                    writer.output = ios
                    writer.write(null, IIOImage(rgbImage, null, null), param)
                }
            } finally {
                // ImageWriter holds native codec resources — must release on failure too.
                writer.dispose()
            }

            val inputSize = inFile.length()
            val outputSize = outFile.length()
            val ratio = if (inputSize > 0) (100.0 * outputSize / inputSize) else 0.0
            "Successfully compressed image (quality=${quality}): ${outFile.absolutePath}\n" +
                    "Size: ${formatSize(inputSize)} -> ${formatSize(outputSize)} (${"%.1f".format(ratio)}%)"
        } catch (e: Exception) {
            "Error compressing image: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "Merge multiple images into one. Supports horizontal or vertical arrangement."
    )
    fun mergeImages(
        @LLMDescription("Comma-separated list of input image paths")
        inputPaths: String,
        @LLMDescription("Path to save the merged image")
        outputPath: String,
        @LLMDescription("Merge direction: 'horizontal' or 'vertical'")
        direction: String = "horizontal",
        @LLMDescription("Gap between images in pixels (default 0)")
        gap: Int = 0
    ): String {
        return try {
            val paths = inputPaths.split(",").map { it.trim() }
            if (paths.size < 2) return "Error: need at least 2 images to merge"

            val images = paths.map { path ->
                val f = try {
                    ToolPathSecurity.validateInputPath(path)
                } catch (e: SecurityException) {
                    return "Error: ${e.message}"
                }
                ImageIO.read(f) ?: return "Error: could not read image: $path"
            }

            val isHorizontal = direction.lowercase().startsWith("h")
            val totalGap = gap * (images.size - 1)

            val mergedWidth: Int
            val mergedHeight: Int
            if (isHorizontal) {
                mergedWidth = images.sumOf { it.width } + totalGap
                mergedHeight = images.maxOf { it.height }
            } else {
                mergedWidth = images.maxOf { it.width }
                mergedHeight = images.sumOf { it.height } + totalGap
            }

            val merged = BufferedImage(mergedWidth, mergedHeight, BufferedImage.TYPE_INT_ARGB)
            val g2d = merged.createGraphics()
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)

            var offset = 0
            for (img in images) {
                if (isHorizontal) {
                    g2d.drawImage(img, offset, 0, null)
                    offset += img.width + gap
                } else {
                    g2d.drawImage(img, 0, offset, null)
                    offset += img.height + gap
                }
            }
            g2d.dispose()

            val outFile = ToolPathSecurity.validateOutputPath(outputPath)
            ImageIO.write(merged, getFormatName(outputPath), outFile)
            "Successfully merged ${images.size} images (${direction}) to ${mergedWidth}x${mergedHeight}: ${outFile.absolutePath}"
        } catch (e: Exception) {
            "Error merging images: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Add a text watermark to an image.")
    fun addTextWatermark(
        @LLMDescription("Path to the input image")
        inputPath: String,
        @LLMDescription("Path to save the watermarked image")
        outputPath: String,
        @LLMDescription("Watermark text")
        text: String,
        @LLMDescription("Font size (default 36)")
        fontSize: Int = 36,
        @LLMDescription("Opacity from 0.0 (invisible) to 1.0 (fully opaque), default 0.3")
        opacity: Float = 0.3f,
        @LLMDescription("Position: 'center', 'top-left', 'top-right', 'bottom-left', 'bottom-right' (default 'bottom-right')")
        position: String = "bottom-right"
    ): String {
        return try {
            val inFile = try {
                ToolPathSecurity.validateInputPath(inputPath)
            } catch (e: SecurityException) {
                return "Error: ${e.message}"
            }
            val image = ImageIO.read(inFile) ?: return "Error: could not read image: $inputPath"
            val result =
                BufferedImage(image.width, image.height, image.type.takeIf { it != 0 } ?: BufferedImage.TYPE_INT_ARGB)

            val g2d = result.createGraphics()
            g2d.drawImage(image, 0, 0, null)
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val font = java.awt.Font("SansSerif", java.awt.Font.BOLD, fontSize)
            g2d.font = font
            g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity.coerceIn(0.0f, 1.0f))
            g2d.color = Color.WHITE

            val metrics = g2d.fontMetrics
            val textWidth = metrics.stringWidth(text)
            val textHeight = metrics.height
            val padding = 20

            val (x, y) = when (position.lowercase()) {
                "center" -> Pair((image.width - textWidth) / 2, (image.height + textHeight) / 2)
                "top-left" -> Pair(padding, textHeight + padding)
                "top-right" -> Pair(image.width - textWidth - padding, textHeight + padding)
                "bottom-left" -> Pair(padding, image.height - padding)
                else -> Pair(image.width - textWidth - padding, image.height - padding) // bottom-right
            }

            g2d.drawString(text, x, y)
            g2d.dispose()

            val outFile = ToolPathSecurity.validateOutputPath(outputPath)
            ImageIO.write(result, getFormatName(outputPath), outFile)
            "Successfully added watermark to image: ${outFile.absolutePath}"
        } catch (e: Exception) {
            "Error adding watermark: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Flip an image horizontally or vertically.")
    fun flipImage(
        @LLMDescription("Path to the input image")
        inputPath: String,
        @LLMDescription("Path to save the flipped image")
        outputPath: String,
        @LLMDescription("Flip direction: 'horizontal' or 'vertical'")
        direction: String = "horizontal"
    ): String {
        return try {
            val inFile = try {
                ToolPathSecurity.validateInputPath(inputPath)
            } catch (e: SecurityException) {
                return "Error: ${e.message}"
            }
            val image = ImageIO.read(inFile) ?: return "Error: could not read image: $inputPath"
            val flipped =
                BufferedImage(image.width, image.height, image.type.takeIf { it != 0 } ?: BufferedImage.TYPE_INT_ARGB)

            val g2d = flipped.createGraphics()
            val transform = if (direction.lowercase().startsWith("h")) {
                AffineTransform.getScaleInstance(-1.0, 1.0).also {
                    it.translate(-image.width.toDouble(), 0.0)
                }
            } else {
                AffineTransform.getScaleInstance(1.0, -1.0).also {
                    it.translate(0.0, -image.height.toDouble())
                }
            }
            g2d.transform = transform
            g2d.drawImage(image, 0, 0, null)
            g2d.dispose()

            val outFile = ToolPathSecurity.validateOutputPath(outputPath)
            ImageIO.write(flipped, getFormatName(outputPath), outFile)
            "Successfully flipped image ($direction): ${outFile.absolutePath}"
        } catch (e: Exception) {
            "Error flipping image: ${e.message}"
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.2f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.2f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
