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
    val parts: List<BootAnimPart>,
    val isStandard: Boolean = true
)

data class BootAnimPart(
    val type: Char,
    val loop: Int,
    val pause: Int,
    val folder: String,
    var audioFile: File? = null
)

object BootAnimParser {
    // ... rest of the code

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

        val width = try { parts[0].toInt() } catch (e: Exception) { 0 }
        val height = try { parts[1].toInt() } catch (e: Exception) { 0 }
        val fps = try { parts[2].toInt() } catch (e: Exception) { 30 }

        val animParts = mutableListOf<BootAnimPart>()
        var isStandard = true
        var line: String? = reader.readLine()
        while (line != null) {
            val trimmedLine = line.trim()
            if (trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#")) {
                val lineParts = trimmedLine.split(" ").filter { it.isNotBlank() }

                // A valid part line usually has at least 4 components: type loop pause folder
                if (lineParts.size >= 4) {
                    val typeChar = lineParts[0][0]

                    try {
                        // Check if the first three parts are numbers to identify a valid animation part line
                        val loop = lineParts[1].toInt()
                        val pause = lineParts[2].toInt()
                        val folder = lineParts[3]

                        animParts.add(
                            BootAnimPart(
                                type = typeChar,
                                loop = loop,
                                pause = pause,
                                folder = folder
                            )
                        )

                        if (typeChar != 'p' && typeChar != 'c') {
                            isStandard = false
                        }
                    } catch (e: Exception) {
                        // Not a standard animation part line (e.g., dynamic_colors, level, etc.)
                        // We just ignore it and continue
                        isStandard = false
                    }
                } else {
                    // Line with fewer than 4 parts, could be a comment or special command
                    if (lineParts.isNotEmpty()) isStandard = false
                }
            }
            line = reader.readLine()
        }

        return BootAnimDesc(width, height, fps, animParts, isStandard)
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

    fun getAudioForPartFromAssets(context: Context, assetPath: String, folder: String): File? {
        try {
            context.assets.open(assetPath).use { inputStream ->
                ZipInputStream(inputStream).use { zip ->
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        if (entry.name == "$folder/audio.wav") {
                            val tempFile = File(context.cacheDir, "temp_audio_${folder}_${System.currentTimeMillis()}.wav")
                            FileOutputStream(tempFile).use { output ->
                                zip.copyTo(output)
                            }
                            return tempFile
                        }
                        entry = zip.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
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

    fun getAudioForPart(zipFile: File, folder: String, context: Context): File? {
        try {
            ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "$folder/audio.wav") {
                        val tempFile = File(context.cacheDir, "temp_audio_${folder}_${System.currentTimeMillis()}.wav")
                        FileOutputStream(tempFile).use { output ->
                            zip.copyTo(output)
                        }
                        return tempFile
                    }
                    entry = zip.nextEntry
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun generatePreviewGif(context: Context, zipFile: File, outputGifFile: File, onComplete: (Boolean) -> Unit) {
        val desc = parseDesc(context, zipFile) ?: return onComplete(false)
        try {
            zipFile.inputStream().use { inputStream ->
                generatePreviewGifFromStream(context, inputStream, desc, outputGifFile, onComplete)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            onComplete(false)
        }
    }

    fun generatePreviewGifFromAssets(context: Context, assetPath: String, outputGifFile: File, onComplete: (Boolean) -> Unit) {
        val desc = parseDescFromAssets(context, assetPath) ?: return onComplete(false)
        try {
            context.assets.open(assetPath).use { inputStream ->
                generatePreviewGifFromStream(context, inputStream, desc, outputGifFile, onComplete)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            onComplete(false)
        }
    }

    private fun generatePreviewGifFromStream(
        context: Context,
        inputStream: InputStream,
        desc: BootAnimDesc,
        outputGifFile: File,
        onComplete: (Boolean) -> Unit
    ) {
        val tempDir = File(context.cacheDir, "preview_frames_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            val folders = desc.parts.map { it.folder }.toSet()

            // Optimization: Load stream into memory to avoid repeated extraction/seeking if possible
            val zipData = inputStream.readBytes()

            // 1. Count total frames to calculate step
            var totalAvailableFrames = 0
            ZipInputStream(zipData.inputStream()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    val folder = folders.find { entryName.startsWith("$it/") }
                    if (folder != null && (entryName.endsWith(".png") || entryName.endsWith(".jpg"))) {
                        totalAvailableFrames++
                    }
                    entry = zip.nextEntry
                }
            }

            if (totalAvailableFrames == 0) {
                tempDir.deleteRecursively()
                onComplete(false)
                return
            }

            val maxGifFrames = 50
            val step = if (totalAvailableFrames > maxGifFrames) (totalAvailableFrames / maxGifFrames) + 1 else 1

            // 2. Extract selected frames
            var globalCounter = 0
            var finalFrameIndex = 0
            ZipInputStream(zipData.inputStream()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    val folder = folders.find { entryName.startsWith("$it/") }
                    if (folder != null && (entryName.endsWith(".png") || entryName.endsWith(".jpg"))) {
                        if (globalCounter % step == 0 && finalFrameIndex < maxGifFrames) {
                            val frameFile = File(tempDir, "frame_%05d.png".format(finalFrameIndex++))
                            FileOutputStream(frameFile).use { out -> zip.copyTo(out) }
                        }
                        globalCounter++
                    }
                    entry = zip.nextEntry
                }
            }

            if (finalFrameIndex == 0) {
                tempDir.deleteRecursively()
                onComplete(false)
                return
            }

            // 3. Generate GIF with extreme compatibility settings
            val gifFps = (desc.fps * 1.5).toInt().coerceIn(15, 30)

            // scale=128:-2 (very small, very safe), max_colors=64
            // format=rgb24 ensures alpha channel is flattened to black
            val command = "-y -framerate $gifFps -i ${tempDir.absolutePath}/frame_%05d.png -vf \"scale=128:-2:flags=fast_bilinear,format=rgb24,split[s0][s1];[s0]palettegen=max_colors=64[p];[s1][p]paletteuse\" ${outputGifFile.absolutePath}"

            FFmpegKit.executeAsync(command) { session ->
                tempDir.deleteRecursively()
                onComplete(session.returnCode.isValueSuccess)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            tempDir.deleteRecursively()
            onComplete(false)
        }
    }
}
