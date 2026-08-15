package utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
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

    fun parseDesc(zipFile: File): BootAnimDesc? {
        return try {
            zipFile.inputStream().use { inputStream ->
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
        var width = 0
        var height = 0
        var fps = 30
        val animParts = mutableListOf<BootAnimPart>()
        var isStandard = true
        var resolutionFound = false

        var line = reader.readLine()
        while (line != null) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                line = reader.readLine()
                continue
            }

            val parts = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (!resolutionFound) {
                if (parts.size >= 3) {
                    width = parts[0].toIntOrNull() ?: 0
                    height = parts[1].toIntOrNull() ?: 0
                    fps = parts[2].toIntOrNull() ?: 30
                    if (width > 0 && height > 0) {
                        resolutionFound = true
                    }
                }
            } else {
                if (parts.size >= 4) {
                    val typeChar = parts[0][0].lowercaseChar()
                    val loop = parts[1].toIntOrNull()
                    val pause = parts[2].toIntOrNull()
                    val folder = parts[3].removePrefix("./").removePrefix(".\\")

                    if (loop != null && pause != null) {
                        animParts.add(BootAnimPart(typeChar, loop, pause, folder))
                        if (typeChar != 'p' && typeChar != 'c') {
                            isStandard = false
                        }
                    } else {
                        isStandard = false
                    }
                } else if (parts.isNotEmpty()) {
                    isStandard = false
                }
            }
            line = reader.readLine()
        }

        return if (resolutionFound) BootAnimDesc(width, height, fps, animParts, isStandard) else null
    }

    fun hasAudioForPart(zipFile: File, folder: String): Boolean {
        val folderLower = folder.lowercase().trim('/').replace("\\", "/")
        try {
            zipFile.inputStream().use { fis ->
                ZipInputStream(fis.buffered()).use { zip ->
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        val nameLower = entry.name.lowercase().replace("\\", "/").trim('/')
                        if (nameLower == "$folderLower/audio.wav" || nameLower == "audio.wav" && folderLower.isEmpty()) {
                            return true
                        }
                        entry = zip.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    fun getAudioForPart(zipFile: File, folder: String, context: Context): File? {
        val folderLower = folder.lowercase().trim('/').replace("\\", "/")
        try {
            zipFile.inputStream().use { fis ->
                ZipInputStream(fis.buffered()).use { zip ->
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        val nameLower = entry.name.lowercase().replace("\\", "/").trim('/')
                        if (nameLower == "$folderLower/audio.wav" || nameLower == "audio.wav" && folderLower.isEmpty()) {
                            val tempFile = File(context.cacheDir, "temp_audio_${folder.replace("/", "_").replace("\\", "_")}_${System.currentTimeMillis()}.wav")
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

    fun getAudioForPartFromAssets(context: Context, assetPath: String, folder: String): File? {
        val folderLower = folder.lowercase().trim('/').replace("\\", "/")
        try {
            context.assets.open(assetPath).use { fis ->
                ZipInputStream(fis.buffered()).use { zip ->
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        val nameLower = entry.name.lowercase().replace("\\", "/").trim('/')
                        if (nameLower == "$folderLower/audio.wav" || nameLower == "audio.wav" && folderLower.isEmpty()) {
                            val tempFile = File(context.cacheDir, "temp_audio_asset_${folder.replace("/", "_").replace("\\", "_")}_${System.currentTimeMillis()}.wav")
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

    fun generatePreviewMp4(context: Context, zipFile: File, outputMp4File: File, onComplete: (Boolean) -> Unit) {
        val desc = parseDesc(zipFile) ?: return onComplete(false)
        try {
            zipFile.inputStream().use { inputStream ->
                generatePreviewMp4FromStream(context, inputStream, desc, outputMp4File, onComplete)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            onComplete(false)
        }
    }

    fun generatePreviewMp4FromAssets(context: Context, assetPath: String, outputMp4File: File, onComplete: (Boolean) -> Unit) {
        val desc = parseDescFromAssets(context, assetPath) ?: return onComplete(false)
        try {
            context.assets.open(assetPath).use { inputStream ->
                generatePreviewMp4FromStream(context, inputStream, desc, outputMp4File, onComplete)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            onComplete(false)
        }
    }

    private fun generatePreviewMp4FromStream(
        context: Context,
        inputStream: InputStream,
        desc: BootAnimDesc,
        outputMp4File: File,
        onComplete: (Boolean) -> Unit
    ) {
        val parentDir = outputMp4File.parentFile
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs()
        }

        val tempDir = File(context.cacheDir, "preview_gen_${System.currentTimeMillis()}")
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            onComplete(false)
            return
        }

        val extractDir = File(tempDir, "extract").apply { mkdirs() }
        val concatFile = File(tempDir, "concat.txt")

        try {
            // 1. Extract ALL images from ZIP (raw bytes)
            ZipInputStream(inputStream.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val entryName = entry.name.replace("\\", "/")
                        val ext = entryName.substringAfterLast(".", "").lowercase()
                        if (listOf("png", "jpg", "jpeg", "bmp", "webp").contains(ext)) {
                            val file = File(extractDir, entryName)
                            file.parentFile?.mkdirs()
                            FileOutputStream(file).use { out ->
                                zip.copyTo(out)
                            }
                        }
                    }
                    entry = zip.nextEntry
                }
            }

            // 2. Build concat.txt based on desc.txt sequence
            var frameCount = 0
            val maxFrames = 150
            val validExt = listOf("png", "jpg", "jpeg", "bmp", "webp")
            
            val inputFps = desc.fps.coerceAtLeast(1).toDouble()
            val frameDuration = 1.0 / (inputFps * 4.0)

            concatFile.bufferedWriter().use { writer ->
                for (part in desc.parts) {
                    val searchFolder = part.folder.removePrefix("./").removePrefix(".\\").trim('/')
                    val partDir = File(extractDir, searchFolder)
                    
                    if (partDir.exists() && partDir.isDirectory) {
                        val files = partDir.listFiles()?.filter { file ->
                            file.isFile && validExt.contains(file.extension.lowercase())
                        }?.sortedBy { it.name }
                        
                        files?.forEach { file ->
                            if (frameCount < maxFrames) {
                                writer.write("file '${file.absolutePath}'\n")
                                writer.write("duration $frameDuration\n")
                                frameCount++
                            }
                        }
                    }
                    if (frameCount >= maxFrames) break
                }
            }

            if (frameCount == 0) {
                tempDir.deleteRecursively()
                onComplete(false)
                return
            }

            // 3. Create MP4 with concat demuxer (skipping PTS/4 speedup as we control duration in concat)
            val vf = "fps=15,crop=min(iw\\,ih):min(iw\\,ih):(iw-min(iw\\,ih))/2:(ih-min(iw\\,ih))/2,scale=256:256"
            
            // Using concat demuxer is much faster as FFmpeg handles diverse inputs directly
            val command = "-y -f concat -safe 0 -i \"${concatFile.absolutePath}\" " +
                    "-vf \"$vf\" -c:v mpeg4 -q:v 5 -pix_fmt yuv420p -movflags +faststart \"${outputMp4File.absolutePath}\""

            DiagnosticLogger.log("ffmpeg", "preview gen optimized", command)

            Thread {
                val session = FFmpegKit.executeBackground(command)
                tempDir.deleteRecursively()
                onComplete(ReturnCode.isSuccess(session.returnCode))
            }.start()

        } catch (e: Exception) {
            e.printStackTrace()
            tempDir.deleteRecursively()
            onComplete(false)
        }
    }

    /**
     * Cleans up stale temporary files and folders from the cache directory.
     */
    fun cleanCache(context: Context) {
        try {
            val cacheDir = context.cacheDir
            cacheDir.listFiles()?.forEach { file ->
                val name = file.name
                if (name.startsWith("preview_gen_") || 
                    name.startsWith("temp_audio_") ||
                    name.startsWith("temp_gif_")) {
                    file.deleteRecursively()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
