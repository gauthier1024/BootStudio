package com.arthenica.ffmpegkit

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import utils.CommandExecutor

object FFmpegKit {
    private var binaryPath: String? = null
    private var libDir: String? = null

    fun init(context: Context) {
        val folder = context.filesDir
        libDir = folder.absolutePath

        // 1. Extract Binary
        val ffmpegFile = File(folder, "ffmpeg")
        extractAsset(context, "ffmpeg-bin", ffmpegFile)
        ffmpegFile.setExecutable(true, false)
        binaryPath = ffmpegFile.absolutePath

        // 2. Extract Shared Libraries
        try {
            val assets = context.assets.list("") ?: emptyArray()
            Log.d("FFmpegKit-Binary", "Assets found: ${assets.joinToString()}")
            
            assets.forEach { fileName ->
                if (fileName.startsWith("lib") && fileName.endsWith(".so")) {
                    val destFile = File(folder, fileName)
                    extractAsset(context, fileName, destFile)
                    destFile.setReadable(true, false)
                }
            }
        } catch (e: Exception) {
            Log.e("FFmpegKit-Binary", "Error during init: ${e.message}")
        }
        
        // 3. Debug: Log what we have in the folder
        val files = folder.listFiles()?.map { "${it.name} (${it.length()} bytes)" } ?: emptyList()
        Log.d("FFmpegKit-Binary", "Files in storage: ${files.joinToString()}")
    }

    private fun extractAsset(context: Context, assetName: String, destFile: File) {
        try {
            context.assets.open(assetName).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d("FFmpegKit-Binary", "Extracted $assetName")
        } catch (e: Exception) {
            Log.e("FFmpegKit-Binary", "Failed to extract $assetName: ${e.message}")
        }
    }

    fun execute(command: String, onLine: ((String) -> Unit)? = null): FFmpegSession {
        if (binaryPath == null || libDir == null) {
            return FFmpegSession(-1, "FFmpeg not initialized")
        }

        // Append exit code detection to the shell command
        val fullCommand = "export LD_LIBRARY_PATH='$libDir' && '$binaryPath' $command 2>&1; echo \"EXIT_CODE:$?\""
        
        Log.d("FFmpegKit-Binary", "Executing: $fullCommand")

        return try {
            val output = CommandExecutor.executeWithSu(fullCommand, purpose = "ffmpeg", onLine = onLine)
            Log.d("FFmpegKit-Binary", "FFmpeg Result: $output")
            
            val exitCodeMatch = Regex("EXIT_CODE:(\\d+)").find(output)
            val exitCode = exitCodeMatch?.groupValues?.get(1)?.toInt() ?: -1
            
            if (exitCode != 0 || output.contains("CANNOT LINK") || output.contains("not found")) {
                 FFmpegSession(if (exitCode == 0) -1 else exitCode, output)
            } else {
                 FFmpegSession(0, output)
            }
        } catch (e: Exception) {
            Log.e("FFmpegKit-Binary", "Execution crash: ${e.message}")
            FFmpegSession(-1, e.message ?: "Unknown error")
        }
    }

    fun executeAsync(command: String, callback: (FFmpegSession) -> Unit) {
        Thread { callback(execute(command)) }.start()
    }

    /**
     * Executes FFmpeg in a background process without blocking the persistent root shell.
     * Ideal for non-critical background tasks like preview generation.
     */
    fun executeBackground(command: String): FFmpegSession {
        if (binaryPath == null || libDir == null) {
            return FFmpegSession(-1, "FFmpeg not initialized")
        }
        val fullCommand = "export LD_LIBRARY_PATH='$libDir' && '$binaryPath' $command"
        Log.d("FFmpegKit-Binary", "Executing Background: $fullCommand")
        return try {
            val process = ProcessBuilder("su", "-c", fullCommand).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val error = process.errorStream.bufferedReader().use { it.readText() }
            process.waitFor()
            val rc = process.exitValue()
            if (rc == 0) FFmpegSession(0, output) else FFmpegSession(rc, error)
        } catch (e: Exception) {
            Log.e("FFmpegKit-Binary", "Background execution crash: ${e.message}")
            FFmpegSession(-1, e.message ?: "Unknown error")
        }
    }
    
    fun getVersion(): String = "Local FFmpeg Binary"
}

class FFmpegSession(val returnCode: Int, val allLogsAsString: String = "")

object ReturnCode {
    fun isSuccess(rc: Int): Boolean = rc == 0
}

object FFmpegKitConfig {
    fun setEnvironmentVariable(name: String, value: String) {}
}
