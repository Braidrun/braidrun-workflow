package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.OutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Runner injects confined bytes and live authority; the legacy singleton stays unchanged. */
@LLMDescription("Bounded image processing within the host-authorized task directory. Multi-frame inputs use the first frame.")
class ImageProcessingToolSet(private val files: ToolFileAccess, private val guard: () -> Unit) : ToolSet {
    companion object {
        const val INPUT_BYTES = 16 * 1024 * 1024
        const val OUTPUT_BYTES = 16 * 1024 * 1024
        const val MAX_PIXELS = 16_000_000L
        const val TOTAL_PIXELS = 32_000_000L
        const val MAX_EDGE = 8192
    }
    private class Budget(var bytes: Long = 0, var pixels: Long = 0)
    private data class Decoded(val image: BufferedImage, val format: String, val bytes: Int)
    private fun dimensions(width: Long, height: Long) {
        require(width in 1..MAX_EDGE.toLong() && height in 1..MAX_EDGE.toLong() && width * height <= MAX_PIXELS) {
            "Image dimensions or pixel limit exceeded"
        }
    }
    private fun decode(path: String, budget: Budget = Budget()): Decoded {
        val bytes = ToolByteBuffers.read(files, path, INPUT_BYTES, guard)
        budget.bytes += bytes.size
        require(budget.bytes <= 32L * 1024 * 1024) { "Combined image byte limit exceeded" }
        MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            require(readers.hasNext()) { "Unsupported or unreadable image format" }
            val reader = readers.next()
            try {
                require(reader.javaClass.name.startsWith("com.sun.imageio.plugins.")) { "Unreviewed image reader" }
                reader.setInput(stream, false, true)
                val frames = reader.getNumImages(true)
                require(frames in 1..16) { "Image frame limit exceeded" }
                for (frame in 0 until frames) {
                    guard()
                    val width = reader.getWidth(frame).toLong(); val height = reader.getHeight(frame).toLong()
                    dimensions(width, height)
                    budget.pixels += width * height
                    require(budget.pixels <= TOTAL_PIXELS) { "Combined image pixel limit exceeded" }
                }
                guard()
                val image = requireNotNull(reader.read(0)) { "Image decoding failed" }
                dimensions(image.width.toLong(), image.height.toLong())
                guard()
                return Decoded(image, reader.formatName, bytes.size)
            } finally { reader.dispose() }
        }
    }
    private fun format(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "JPEG"
        "png" -> "PNG"
        "bmp" -> "BMP"
        "gif" -> "GIF"
        "tif", "tiff" -> "TIFF"
        else -> throw IllegalArgumentException("Unsupported image output extension")
    }
    private class BoundedImageOutput(output: OutputStream, private val limit: Int, private val guard: () -> Unit) : MemoryCacheImageOutputStream(output) {
        override fun write(value: Int) { guard(); require(streamPosition < limit) { "Image output limit exceeded" }; super.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            guard(); require(length >= 0 && streamPosition + length <= limit) { "Image output limit exceeded" }
            super.write(bytes, offset, length)
        }
    }
    private fun encode(image: BufferedImage, path: String, quality: Float? = null): Int {
        guard()
        require(path.length in 1..4096 && '\u0000' !in path) { "Invalid output path" }
        val format = format(path)
        if (quality != null) require(format == "JPEG" && quality.isFinite() && quality in 0f..1f) { "JPEG quality must be between zero and one" }
        val writers = ImageIO.getImageWritersByFormatName(format)
        require(writers.hasNext()) { "No image writer for $format" }
        val writer = writers.next()
        try {
            require(writer.javaClass.name.startsWith("com.sun.imageio.plugins.")) { "Unreviewed image writer" }
            val rendered = if (format == "JPEG" || format == "BMP") canvas(image.width, image.height, BufferedImage.TYPE_INT_RGB).also { rgb ->
                graphics(rgb) { g -> g.color = Color.WHITE; g.fillRect(0, 0, rgb.width, rgb.height); g.drawImage(image, 0, 0, null) }
            } else image
            val output = BoundedToolOutput(OUTPUT_BYTES, guard)
            BoundedImageOutput(output, OUTPUT_BYTES, guard).use { stream ->
                writer.output = stream
                val parameters = writer.defaultWriteParam
                if (quality != null) {
                    require(parameters.canWriteCompressed()) { "JPEG writer lacks compression support" }
                    parameters.compressionMode = ImageWriteParam.MODE_EXPLICIT
                    parameters.compressionQuality = quality
                }
                writer.write(null, IIOImage(rendered, null, null), parameters)
                stream.flush()
            }
            val bytes = output.bytes()
            require(bytes.isNotEmpty()) { "Image writer produced no bytes" }
            guard(); files.writeBytes(path, bytes); guard()
            return bytes.size
        } finally { writer.dispose() }
    }
    private fun canvas(width: Int, height: Int, type: Int = BufferedImage.TYPE_INT_ARGB): BufferedImage {
        dimensions(width.toLong(), height.toLong()); guard()
        return BufferedImage(width, height, type)
    }
    private inline fun graphics(image: BufferedImage, action: (java.awt.Graphics2D) -> Unit) {
        val graphics = image.createGraphics()
        try { graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR); action(graphics) }
        finally { graphics.dispose() }
    }
    private fun result(text: String): String = ToolByteBuffers.result(text, guard)

    @Tool
    @LLMDescription("Get detailed information about an image file (dimensions, format, color depth, file size).")
    fun getImageInfo(
        @LLMDescription("Path to the image file")
        path: String
    ): String {
        guard()
        val decoded = decode(path); val image = decoded.image
        return result("Path: $path\nFormat: ${decoded.format}\nWidth: ${image.width} px\nHeight: ${image.height} px\nColor model: ${image.colorModel.pixelSize} bits/pixel\nHas alpha: ${image.colorModel.hasAlpha()}\nFile size: ${decoded.bytes} bytes")
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
        guard()
        require(width > 0 || height > 0) { "At least one target dimension must be positive" }
        val image = decode(inputPath).image
        val w = if (width > 0) width.toLong() else image.width.toLong() * height / image.height
        val h = if (height > 0) height.toLong() else image.height.toLong() * width / image.width
        dimensions(w, h)
        val resized = canvas(w.toInt(), h.toInt())
        graphics(resized) { it.drawImage(image, 0, 0, resized.width, resized.height, null) }
        encode(resized, outputPath)
        return result("Successfully resized image to ${resized.width}x${resized.height}: $outputPath")
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
        guard()
        val image = decode(inputPath).image
        dimensions(width.toLong(), height.toLong())
        require(x >= 0 && y >= 0 && x.toLong() + width <= image.width && y.toLong() + height <= image.height) { "Crop exceeds image bounds" }
        encode(image.getSubimage(x, y, width, height), outputPath)
        return result("Successfully cropped image to ${width}x${height}: $outputPath")
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
        guard()
        require(degrees.isFinite()) { "Rotation must be finite" }
        val image = decode(inputPath).image
        val radians = Math.toRadians(degrees % 360.0)
        val sine = abs(sin(radians)).let { if (it < 1e-12) 0.0 else it }
        val cosine = abs(cos(radians)).let { if (it < 1e-12) 0.0 else it }
        val w = kotlin.math.ceil(image.width * cosine + image.height * sine).toLong()
        val h = kotlin.math.ceil(image.width * sine + image.height * cosine).toLong()
        dimensions(w, h)
        val rotated = canvas(w.toInt(), h.toInt())
        graphics(rotated) { g ->
            val transform = AffineTransform(); transform.translate(w / 2.0, h / 2.0)
            transform.rotate(radians); transform.translate(-image.width / 2.0, -image.height / 2.0)
            g.transform = transform; g.drawImage(image, 0, 0, null)
        }
        encode(rotated, outputPath)
        return result("Successfully rotated image by $degrees degrees: $outputPath")
    }

    @Tool
    @LLMDescription("Convert an image from one format to another (e.g., PNG to JPEG, BMP to PNG).")
    fun convertImageFormat(
        @LLMDescription("Path to the input image")
        inputPath: String,
        @LLMDescription("Path to save the converted image (format determined by file extension, e.g., '.jpg', '.png')")
        outputPath: String
    ): String {
        guard()
        val image = decode(inputPath).image
        val bytes = encode(image, outputPath)
        return result("Successfully converted image to ${format(outputPath)}: $outputPath ($bytes bytes)")
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
        guard()
        require(quality.isFinite() && quality in 0f..1f) { "Invalid compression quality" }
        val decoded = decode(inputPath)
        val bytes = encode(decoded.image, outputPath, quality)
        return result("Successfully compressed image (quality=$quality): $outputPath\nSize: ${decoded.bytes} -> $bytes bytes")
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
        guard()
        require(inputPaths.length <= 65536 && gap in 0..MAX_EDGE) { "Invalid image list or gap" }
        val paths = inputPaths.split(",").map { it.trim() }
        require(paths.size in 2..16 && paths.none { it.isEmpty() }) { "Merge requires two to sixteen images" }
        require(direction in setOf("horizontal", "vertical")) { "Invalid merge direction" }
        val budget = Budget(); val images = paths.map { decode(it, budget).image }
        val horizontal = direction == "horizontal"; val totalGap = gap.toLong() * (images.size - 1)
        val w = if (horizontal) images.sumOf { it.width.toLong() } + totalGap else images.maxOf { it.width }.toLong()
        val h = if (horizontal) images.maxOf { it.height }.toLong() else images.sumOf { it.height.toLong() } + totalGap
        dimensions(w, h)
        val merged = canvas(w.toInt(), h.toInt())
        graphics(merged) { g ->
            var offset = 0
            images.forEach { image ->
                guard(); g.drawImage(image, if (horizontal) offset else 0, if (horizontal) 0 else offset, null)
                offset += (if (horizontal) image.width else image.height) + gap
            }
        }
        encode(merged, outputPath)
        return result("Successfully merged ${images.size} images: $outputPath")
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
        guard()
        require(text.length <= 4096 && text.toByteArray().size <= 4096 && fontSize in 1..256 && opacity.isFinite() && opacity in 0f..1f) { "Invalid watermark bounds" }
        require(position in setOf("center", "top-left", "top-right", "bottom-left", "bottom-right")) { "Invalid watermark position" }
        val image = decode(inputPath).image; val output = canvas(image.width, image.height)
        graphics(output) { g ->
            g.drawImage(image, 0, 0, null); g.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, fontSize)
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity); g.color = Color.WHITE
            val metrics = g.fontMetrics; val tw = metrics.stringWidth(text); val th = metrics.height; val padding = 10
            val x = when { position == "center" -> (image.width - tw) / 2; position.endsWith("left") -> padding; else -> image.width - tw - padding }
            val y = when { position == "center" -> (image.height + th) / 2; position.startsWith("top") -> th + padding; else -> image.height - padding }
            g.drawString(text, x, y)
        }
        encode(output, outputPath)
        return result("Successfully added watermark: $outputPath")
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
        guard()
        require(direction in setOf("horizontal", "vertical")) { "Invalid flip direction" }
        val image = decode(inputPath).image; val output = canvas(image.width, image.height)
        graphics(output) { g ->
            if (direction == "horizontal") g.drawImage(image, image.width, 0, -image.width, image.height, null)
            else g.drawImage(image, 0, image.height, image.width, -image.height, null)
        }
        encode(output, outputPath)
        return result("Successfully flipped image ($direction): $outputPath")
    }
}
