package utils

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
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
        DiagnosticLogger.log("CommandExecutor: Initializing Root Session...")
        return try {
            if (suProcess != null) {
                DiagnosticLogger.log("CommandExecutor: Session already active.")
                return true
            }

            suProcess = ProcessBuilder("su").start()
            suWriter = BufferedWriter(OutputStreamWriter(suProcess!!.outputStream))
            suReader = BufferedReader(InputStreamReader(suProcess!!.inputStream))
            suErrorReader = BufferedReader(InputStreamReader(suProcess!!.errorStream))
            
            // Test simple pour vérifier que le shell est actif et root
            val result = executeWithSu("id")
            val isRoot = result.contains("uid=0")
            DiagnosticLogger.log("CommandExecutor: Root check result: $isRoot (output: $result)")
            isRoot
        } catch (e: Exception) {
            DiagnosticLogger.log("CommandExecutor: Root initialization failed: ${e.message}")
            closeRootSession()
            false
        }
    }

    fun closeRootSession() {
        DiagnosticLogger.log("CommandExecutor: Closing Root Session.")
        try {
            suWriter?.write("exit\n")
            suWriter?.flush()
            suProcess?.destroy()
        } catch (e: Exception) {
            DiagnosticLogger.log("CommandExecutor: Error closing session: ${e.message}")
        } finally {
            suProcess = null
            suWriter = null
            suReader = null
            suErrorReader = null
        }
    }

    fun executeWithSu(command: String, onLine: ((String) -> Unit)? = null, logResult: Boolean = true): String {
        // Si la session n'est pas initialisée, on tente de le faire
        if (suProcess == null || suWriter == null) {
            if (!initRootSession()) {
                // Fallback sur l'ancienne méthode si l'initialisation échoue
                return executeWithSuLegacy(command)
            }
        }

        DiagnosticLogger.log("CommandExecutor [SU]: $command")
        return try {
            val writer = suWriter!!
            val reader = suReader!!
            
            // On utilise un délimiteur unique pour savoir quand la commande est finie
            val delimiter = "END_OF_COMMAND_${System.currentTimeMillis()}"
            
            writer.write("$command\n")
            writer.write("echo $delimiter\n")
            writer.flush()

            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line == delimiter) break
                line?.let {
                    output.append(it).append("\n")
                    onLine?.invoke(it)
                }
            }
            
            val result = output.toString().trim()
            if (result.isNotEmpty() && logResult) DiagnosticLogger.log("CommandExecutor [SU Result]: $result")
            result
        } catch (e: Exception) {
            DiagnosticLogger.log("CommandExecutor [SU Error]: ${e.message}")
            closeRootSession() // On ferme en cas d'erreur pour réinitialiser plus tard
            "Error: ${e.message}"
        }
    }

    private fun executeWithSuLegacy(command: String): String {
        return try {
            val process = ProcessBuilder("su", "-c", command).start()
            val outText = process.inputStream.bufferedReader().use { it.readText() }
            val errText = process.errorStream.bufferedReader().use { it.readText() }
            process.waitFor()
            if (process.exitValue() == 0) outText.trim() else errText.trim()
        } catch (e: Exception) {
            "su Error: ${e.message}"
        }
    }

    fun executeWithShizuku(command: String, onLine: ((String) -> Unit)? = null, logResult: Boolean = true): String {
        DiagnosticLogger.log("CommandExecutor [Shizuku]: $command")
        return try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                DiagnosticLogger.log("CommandExecutor [Shizuku]: Not authorized")
                return "Shizuku not authorized"
            }
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            
            val outBuilder = StringBuilder()
            val outReader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (outReader.readLine().also { line = it } != null) {
                line?.let {
                    outBuilder.append(it).append("\n")
                    onLine?.invoke(it)
                }
            }
            
            process.waitFor()
            val result = outBuilder.toString().trim()
            if (result.isNotEmpty() && logResult) DiagnosticLogger.log("CommandExecutor [Shizuku Result]: $result")
            result
        } catch (t: Throwable) {
            DiagnosticLogger.log("CommandExecutor [Shizuku Error]: ${t.message}")
            "Shizuku Error: ${t.message}"
        }
    }
}
