package utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.arthenica.ffmpegkit.FFmpegKit
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class BootAnimDesc(
    val width: Int,
    val height: Int,
    val fps: Int,
    val parts: List<BootAnimPart>
)

data class BootAnimPart(
    val type: Char,
    val loop: Int,
    val pause: Int,
    val folder: String
)

object BootAnimParser {

    fun parseDesc(context: Context, zipFile: File): BootAnimDesc? {
        return try {
            ZipInputStream(zipFile.inputStream()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "desc.txt") {
                        return readDescFile(zip)
                    }
                    entry = zip.nextEntry
                }
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun parseDescFromAssets(context: Context, assetPath: String): BootAnimDesc? {
        return try {
            context.assets.open(assetPath).use { inputStream ->
                ZipInputStream(inputStream).use { zip ->
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        if (entry.name == "desc.txt") {
                            return readDescFile(zip)
                        }
                        entry = zip.nextEntry
                    }
                }
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun readDescFile(inputStream: InputStream): BootAnimDesc? {
        val reader = BufferedReader(InputStreamReader(inputStream))
        val firstLine = reader.readLine() ?: return null
        val parts = firstLine.split(" ").filter { it.isNotBlank() }
        if (parts.size < 3) return null

        val width = parts[0].toInt()
        val height = parts[1].toInt()
        val fps = parts[2].toInt()

        val animParts = mutableListOf<BootAnimPart>()
        var line: String? = reader.readLine()
        while (line != null) {
            val lineParts = line.split(" ").filter { it.isNotBlank() }
            if (lineParts.size >= 4) {
                animParts.add(
                    BootAnimPart(
                        type = lineParts[0][0],
                        loop = lineParts[1].toInt(),
                        pause = lineParts[2].toInt(),
                        folder = lineParts[3]
                    )
                )
            }
            line = reader.readLine()
        }

        return BootAnimDesc(width, height, fps, animParts)
    }

    fun getFirstFrame(context: Context, assetPath: String): Bitmap? {
        val cacheKey = assetPath.replace("/", "_") + ".thumb"
        val cacheFile = File(context.cacheDir, cacheKey)
        if (cacheFile.exists()) {
            return BitmapFactory.decodeFile(cacheFile.absolutePath)
        }

        return try {
            context.assets.open(assetPath).use { inputStream ->
                val bitmap = getFirstFrameFromStream(inputStream)
                bitmap?.let {
                    FileOutputStream(cacheFile).use { out ->
                        it.compress(Bitmap.CompressFormat.JPEG, 80, out)
                    }
                }
                bitmap
            }
        } catch (e: Exception) {
            null
        }
    }

    fun getFirstFrameFromFile(context: Context, file: File): Bitmap? {
        val cacheKey = file.absolutePath.replace("/", "_") + ".thumb"
        val cacheFile = File(context.cacheDir, cacheKey)
        if (cacheFile.exists()) {
            return BitmapFactory.decodeFile(cacheFile.absolutePath)
        }

        return try {
            file.inputStream().use { inputStream ->
                val bitmap = getFirstFrameFromStream(inputStream)
                bitmap?.let {
                    FileOutputStream(cacheFile).use { out ->
                        it.compress(Bitmap.CompressFormat.JPEG, 80, out)
                    }
                }
                bitmap
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getFirstFrameFromStream(inputStream: InputStream): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inSampleSize = 2
        }

        val sampledFramesData = mutableListOf<ByteArray>()
        
        ZipInputStream(inputStream.buffered()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            var count = 0
            while (entry != null) {
                if (entry.name.endsWith(".png") || entry.name.endsWith(".jpg")) {
                    if (count % 25 == 0) {
                        sampledFramesData.add(zip.readBytes())
                    }
                    count++
                }
                if (sampledFramesData.size >= 15) break
                entry = zip.nextEntry
            }
        }

        var bestBitmap: Bitmap? = null
        var maxVibrancyScore = -1.0

        for (frameData in sampledFramesData) {
            val bitmap = BitmapFactory.decodeByteArray(frameData, 0, frameData.size, options)
            if (bitmap != null) {
                val score = calculateVisualVibrancy(bitmap)
                if (score > maxVibrancyScore) {
                    maxVibrancyScore = score
                    bestBitmap?.recycle()
                    bestBitmap = bitmap
                } else {
                    bitmap.recycle()
                }
            }
        }
        return bestBitmap
    }

    private fun calculateVisualVibrancy(bitmap: Bitmap): Double {
        val width = bitmap.width
        val height = bitmap.height
        val sampleSize = 20
        var totalNonextremePixels = 0
        var totalSampled = 0
        
        val darkThreshold = 30
        val lightThreshold = 225
        
        for (y in 0 until height step sampleSize) {
            for (x in 0 until width step sampleSize) {
                totalSampled++
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                
                val isNotBlack = r > darkThreshold || g > darkThreshold || b > darkThreshold
                val isNotWhite = r < lightThreshold || g < lightThreshold || b < lightThreshold
                
                if (isNotBlack && isNotWhite) {
                    totalNonextremePixels++
                }
            }
        }
        
        if (totalSampled == 0) return 0.0
        return totalNonextremePixels.toDouble() / totalSampled.toDouble()
    }

    fun getFramesForPartFromAssets(context: Context, assetPath: String, folder: String): List<Bitmap> {
        val frameEntries = mutableListOf<Pair<String, Bitmap>>()
        try {
            context.assets.open(assetPath).use { inputStream ->
                ZipInputStream(inputStream).use { zip ->
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        if (entry.name.startsWith("$folder/") && (entry.name.endsWith(".png") || entry.name.endsWith(".jpg"))) {
                            val bitmap = BitmapFactory.decodeStream(zip)
                            if (bitmap != null) {
                                frameEntries.add(entry.name to bitmap)
                            }
                        }
                        entry = zip.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return frameEntries.sortedBy { it.first }.map { it.second }
    }

    fun getFramesForPart(zipFile: File, folder: String): List<Bitmap> {
        val frameEntries = mutableListOf<Pair<String, Bitmap>>()
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inMutable = false
        }
        try {
            ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    if (entry.name.startsWith("$folder/") && (entry.name.endsWith(".png") || entry.name.endsWith(".jpg"))) {
                        val bitmap = BitmapFactory.decodeStream(zip, null, options)
                        if (bitmap != null) {
                            frameEntries.add(entry.name to bitmap)
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return frameEntries.sortedBy { it.first }.map { it.second }
    }

    fun generateVideoPreview(context: Context, assetPath: String, outputVideoFile: File, onComplete: (Boolean) -> Unit) {
        val desc = parseDescFromAssets(context, assetPath) ?: return onComplete(false)
        try {
            context.assets.open(assetPath).use { inputStream ->
                generateVideoFromStream(context, inputStream, desc, outputVideoFile, onComplete)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            onComplete(false)
        }
    }

    fun generateVideoPreviewFromFile(context: Context, zipFile: File, outputVideoFile: File, onComplete: (Boolean) -> Unit) {
        val desc = parseDesc(context, zipFile) ?: return onComplete(false)
        try {
            zipFile.inputStream().use { inputStream ->
                generateVideoFromStream(context, inputStream, desc, outputVideoFile, onComplete)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            onComplete(false)
        }
    }

    private fun generateVideoFromStream(
        context: Context,
        inputStream: InputStream,
        desc: BootAnimDesc,
        outputVideoFile: File,
        onComplete: (Boolean) -> Unit
    ) {
        val tempDir = File(context.cacheDir, "anim_frames_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            var frameIndex = 0
            ZipInputStream(inputStream.buffered()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    // Only take frames from the first part to keep preview generation fast
                    val firstPartFolder = desc.parts.firstOrNull()?.folder
                    if (firstPartFolder != null && entry.name.startsWith("$firstPartFolder/") && 
                        (entry.name.endsWith(".png") || entry.name.endsWith(".jpg"))) {
                        val frameFile = File(tempDir, "frame_%05d.png".format(frameIndex++))
                        FileOutputStream(frameFile).use { out ->
                            zip.copyTo(out)
                        }
                    }
                    if (frameIndex >= 100) break // Limit frames for preview speed
                    entry = zip.nextEntry
                }
            }

            if (frameIndex == 0) {
                tempDir.deleteRecursively()
                return onComplete(false)
            }

            val command = "-y -framerate ${desc.fps} -i ${tempDir.absolutePath}/frame_%05d.png -c:v libx264 -pix_fmt yuv420p -preset ultrafast ${outputVideoFile.absolutePath}"
            
            FFmpegKit.executeAsync(command) { session ->
                val returnCode = session.returnCode
                tempDir.deleteRecursively()
                onComplete(returnCode.isValueSuccess)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            tempDir.deleteRecursively()
            onComplete(false)
        }
    }
}
