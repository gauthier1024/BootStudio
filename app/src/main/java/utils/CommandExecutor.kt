package utils

import android.util.Log
import utils.DiagnosticLogger
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

object CommandExecutor {

    private var suProcess: Process? = null
    private var suWriter: BufferedWriter? = null
    private var suReader: BufferedReader? = null
    private var suErrorReader: BufferedReader? = null

    /**
     * Initialise une session shell root persistante.
     * À appeler au démarrage de l'application.
     */
    fun initRootSession(): Boolean {
        return try {
            if (suProcess != null) {
                return true
            }

            suProcess = ProcessBuilder("su").start()
            suWriter = BufferedWriter(OutputStreamWriter(suProcess!!.outputStream))
            suReader = BufferedReader(InputStreamReader(suProcess!!.inputStream))
            suErrorReader = BufferedReader(InputStreamReader(suProcess!!.errorStream))
            
            // Test simple pour vérifier que le shell est actif et root
            // Use a direct write to avoid recursion with executeWithSu
            val writer = suWriter!!
            val reader = suReader!!
            val delimiter = "ROOT_CHECK_DONE"
            
            writer.write("id\n")
            writer.write("echo $delimiter\n")
            writer.flush()

            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line == delimiter) break
                output.append(line).append("\n")
            }
            
            val isRoot = output.toString().contains("uid=0")
            if (!isRoot) closeRootSession()
            isRoot
        } catch (e: Exception) {
            DiagnosticLogger.log("shell", "Root Check Error", e.message ?: "Unknown error")
            closeRootSession()
            false
        }
    }

    fun closeRootSession() {
        try {
            suWriter?.write("exit\n")
            suWriter?.flush()
            suProcess?.destroy()
        } catch (_: Exception) {
            // Ignored
        } finally {
            suProcess = null
            suWriter = null
            suReader = null
            suErrorReader = null
        }
    }

    fun executeWithSu(command: String, purpose: String = "Internal", onLine: ((String) -> Unit)? = null): String {
        if (suProcess == null || suWriter == null) {
            if (!initRootSession()) {
                return executeWithSuLegacy(command)
            }
        }

        DiagnosticLogger.log("shell", purpose, command)
        return try {
            val writer = suWriter!!
            val reader = suReader!!
            
            val delimiter = "END_OF_COMMAND_${System.currentTimeMillis()}"
            
            writer.write("$command\n")
            writer.write("echo $delimiter\n")
            writer.flush()

            val output = StringBuilder()
            var line: String?
            
            // This loop ensures we don't get stuck if FFmpeg takes a long time
            while (true) {
                line = reader.readLine()
                if (line == null || line == delimiter) break
                
                output.append(line).append("\n")
                onLine?.invoke(line)
                
                // If it's an ffmpeg command, log the progress periodically
                if (purpose == "ffmpeg" && line.contains("frame=")) {
                    Log.d("FFmpeg-Progress", line)
                }
            }
            
            output.toString().trim()
        } catch (e: Exception) {
            DiagnosticLogger.log("shell", "$purpose Error", e.message ?: "Unknown error")
            closeRootSession() // On ferme en cas d'erreur pour réinitialiser plus tard
            "Error: ${e.message}"
        }
    }

    private fun executeWithSuLegacy(command: String): String {
        DiagnosticLogger.log("shell", "Internal Legacy", command)
        return try {
            val process = ProcessBuilder("su", "-c", command).start()
            val outText = process.inputStream.bufferedReader().use { it.readText() }
            val errText = process.errorStream.bufferedReader().use { it.readText() }
            process.waitFor()
            if (process.exitValue() == 0) outText.trim() else errText.trim()
        } catch (e: Exception) {
            DiagnosticLogger.log("shell", "Internal Legacy Error", e.message ?: "Unknown error")
            "su Error: ${e.message}"
        }
    }
}
