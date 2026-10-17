package com.arthenica.ffmpegkit

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import utils.CommandExecutor
import utils.ShellUtils

/**
 * Runs the bundled FFmpeg binary. FFmpeg is executed through the root shell, so:
 *  - arguments are passed as a list and quoted one by one (never concatenated into a shell string);
 *  - the binary and libraries live in an app-writable directory, so root first verifies their
 *    SHA-256 against the copies shipped inside the APK and refuses to run anything that was altered.
 */
object FFmpegKit {
    private var binaryPath: String? = null
    private var libDir: String? = null

    /** Absolute path -> expected SHA-256 (hex) of every file root is about to load. */
    private val expectedHashes = LinkedHashMap<String, String>()

    fun init(context: Context) {
        val folder = context.filesDir
        libDir = folder.absolutePath
        expectedHashes.clear()
        binaryPath = null

        // 1. Extract Binary
        val ffmpegFile = File(folder, "ffmpeg")
        val binHash = extractAsset(context, "ffmpeg-bin", ffmpegFile)
        if (binHash != null) {
            ffmpegFile.setExecutable(true, false)
            expectedHashes[ffmpegFile.absolutePath] = binHash
            binaryPath = ffmpegFile.absolutePath
        }

        // 2. Extract Shared Libraries
        try {
            val assets = context.assets.list("") ?: emptyArray()
            assets.forEach { fileName ->
                if (fileName.startsWith("lib") && fileName.endsWith(".so")) {
                    val destFile = File(folder, fileName)
                    val hash = extractAsset(context, fileName, destFile)
                    if (hash != null) {
                        destFile.setReadable(true, false)
                        expectedHashes[destFile.absolutePath] = hash
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("FFmpegKit-Binary", "Error during init: ${e.message}")
        }
    }

    /** Copies the asset to [destFile] and returns the SHA-256 (hex) of what was written, or null on failure. */
    private fun extractAsset(context: Context, assetName: String, destFile: File): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            // Never write through a pre-existing file or symlink.
            if (destFile.exists() && !destFile.delete()) return null
            context.assets.open(assetName).use { raw ->
                DigestInputStream(raw, digest).use { input ->
                    FileOutputStream(destFile).use { output -> input.copyTo(output) }
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e("FFmpegKit-Binary", "Failed to extract $assetName: ${e.message}")
            null
        }
    }

    /** Shell snippet that succeeds only if every binary/library still matches the APK's copy. */
    private fun integrityCheck(): String =
        expectedHashes.entries.joinToString(" && ") { (path, hash) ->
            "[ \"\$(sha256sum ${ShellUtils.quote(path)} | cut -d' ' -f1)\" = ${ShellUtils.quote(hash)} ]"
        }

    private fun buildCommand(args: List<String>, captureExitCode: Boolean): String? {
        val bin = binaryPath ?: return null
        val lib = libDir ?: return null
        val quotedArgs = args.joinToString(" ") { ShellUtils.quote(it) }
        val core = "${integrityCheck()} && export LD_LIBRARY_PATH=${ShellUtils.quote(lib)} && " +
            "${ShellUtils.quote(bin)} $quotedArgs"
        return if (captureExitCode) "$core 2>&1; echo \"EXIT_CODE:\$?\"" else core
    }

    fun execute(args: List<String>, onLine: ((String) -> Unit)? = null): FFmpegSession {
        val fullCommand = buildCommand(args, captureExitCode = true)
            ?: return FFmpegSession(-1, "FFmpeg not initialized")

        Log.d("FFmpegKit-Binary", "Executing ffmpeg with ${args.size} arguments")

        return try {
            val output = CommandExecutor.executeWithSu(fullCommand, purpose = "ffmpeg", onLine = onLine)

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

    /**
     * Executes FFmpeg in a background process without blocking the persistent root shell.
     * Ideal for non-critical background tasks like preview generation.
     */
    fun executeBackground(args: List<String>): FFmpegSession {
        val fullCommand = buildCommand(args, captureExitCode = false)
            ?: return FFmpegSession(-1, "FFmpeg not initialized")
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
