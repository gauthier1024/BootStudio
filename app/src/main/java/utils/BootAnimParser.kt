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
                    resolutionFound = true
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

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
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

        try {
            // 1. Extract ALL frames to 'raw' folder
            ZipInputStream(inputStream.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.replace("\\", "/")
                    if (!entry.isDirectory && (name.endsWith(".png", true) || name.endsWith(".jpg", true) ||
                                name.endsWith(".jpeg", true) || name.endsWith(".webp", true))) {

                        val folderPath = name.substringBeforeLast("/", "").lowercase()
                        val frameFolder = File(tempDir, "raw/$folderPath")
                        if (!frameFolder.exists()) frameFolder.mkdirs()

                        val frameFile = File(frameFolder, name.substringAfterLast("/"))
                        FileOutputStream(frameFile).use { out -> zip.copyTo(out) }
                    }
                    entry = zip.nextEntry
                }
            }

            // 2. Normalize and Flatten Sequence (Optimized)
            var totalInSequence = 0
            val sequenceDir = File(tempDir, "sequence").apply { mkdirs() }
            val rawDir = File(tempDir, "raw")
            
            fun getAllSubdirs(file: File): List<File> {
                val subdirs = file.listFiles { it.isDirectory }?.toList() ?: emptyList()
                return subdirs + subdirs.flatMap { getAllSubdirs(it) }
            }
            val availableFolders = (getAllSubdirs(rawDir) + rawDir).distinct()

            // Calculate sampling to target ~15fps
            val sourceFps = desc.fps.coerceAtLeast(1)
            val samplingInterval = (sourceFps / 15).coerceAtLeast(1)
            val maxTotalFrames = 300 // Cap total frames for speed

            for ((index, part) in desc.parts.withIndex()) {
                if (totalInSequence >= maxTotalFrames) break
                
                val searchFolder = part.folder.replace("\\", "/").trim('/').lowercase().removePrefix("./")
                val matchedFolder = availableFolders.find { 
                    val relativePath = it.absolutePath.removePrefix(rawDir.absolutePath).trim('/').lowercase()
                    relativePath == searchFolder || relativePath.endsWith("/$searchFolder") || (searchFolder.isEmpty() && relativePath.isEmpty())
                }
                
                if (matchedFolder == null) continue
                
                val sourceFrames = matchedFolder.listFiles()?.filter { 
                    it.isFile && !it.name.startsWith(".") && 
                    (it.name.endsWith(".png", true) || it.name.endsWith(".jpg", true) || 
                     it.name.endsWith(".jpeg", true) || it.name.endsWith(".webp", true))
                }?.sortedBy { it.name } ?: emptyList()

                if (sourceFrames.isNotEmpty()) {
                    val normalizedPartFrames = mutableListOf<File>()
                    // Sample frames to save time
                    for (i in sourceFrames.indices step samplingInterval) {
                        val srcFile = sourceFrames[i]
                        try {
                            // OPTIMIZATION: Decode with scaling to 256x256 immediately
                            val options = BitmapFactory.Options().apply {
                                inJustDecodeBounds = true
                            }
                            BitmapFactory.decodeFile(srcFile.absolutePath, options)
                            
                            options.inSampleSize = calculateInSampleSize(options, 256, 256)
                            options.inJustDecodeBounds = false
                            options.inPreferredConfig = Bitmap.Config.RGB_565 // Faster decoding
                            
                            val bitmap = BitmapFactory.decodeFile(srcFile.absolutePath, options)
                            if (bitmap != null) {
                                // OPTIMIZATION: Save as JPG instead of PNG for faster I/O
                                val normFile = File(tempDir, "norm_${index}_${i}.jpg")
                                FileOutputStream(normFile).use { out ->
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                                }
                                normalizedPartFrames.add(normFile)
                                bitmap.recycle()
                            }
                        } catch (e: Exception) {
                            // Skip failing frames
                        }
                    }

                    if (normalizedPartFrames.isNotEmpty()) {
                        val loops = if (part.loop <= 0) 1 else part.loop
                        // Cap loops to avoid massive sequences
                        val limitedLoops = loops.coerceAtMost(3)
                        
                        repeat(limitedLoops) {
                            for (normFile in normalizedPartFrames) {
                                if (totalInSequence >= maxTotalFrames) break
                                val dstFile = File(sequenceDir, "frame_%06d.jpg".format(totalInSequence++))
                                normFile.copyTo(dstFile, overwrite = true)
                            }
                        }
                    }
                }
            }

            if (totalInSequence == 0) {
                tempDir.deleteRecursively()
                onComplete(false)
                return
            }

            // 3. Simple FFmpeg Command (Fast)
            // Use the sampled rate as input
            // Use 'mpeg4' instead of 'libx264' for broader compatibility with basic FFmpeg builds
            val inputFps = (sourceFps / samplingInterval).coerceAtLeast(1)
            val command = "-y -framerate $inputFps -i \"${sequenceDir.absolutePath}/frame_%06d.jpg\" -vf \"scale=256:256:force_original_aspect_ratio=decrease,pad=256:256:(ow-iw)/2:(oh-ih)/2:black,setpts=0.25*PTS\" -c:v mpeg4 -q:v 5 \"${outputMp4File.absolutePath}\""
            DiagnosticLogger.log("ffmpeg", "creating mp4", command)

            FFmpegKit.executeAsync(command) { session ->
                if (ReturnCode.isSuccess(session.returnCode)) {
                    tempDir.deleteRecursively()
                    onComplete(true)
                } else {
                    DiagnosticLogger.log("ffmpeg", "creating mp4 Error", "RC ${session.returnCode}")
                    tempDir.deleteRecursively()
                    onComplete(false)
                }
            }
        } catch (e: Exception) {
            tempDir.deleteRecursively()
            onComplete(false)
        }
    }
}
