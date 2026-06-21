package utils

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

object CommandExecutor {
    
    private fun readStreamWithCallback(process: Process, onLine: (String) -> Unit): Pair<String, String> {
        val outBuilder = StringBuilder()
        val errBuilder = StringBuilder()
        
        val outReader = BufferedReader(InputStreamReader(process.inputStream))
        val errReader = BufferedReader(InputStreamReader(process.errorStream))
        
        var line: String?
        while (outReader.readLine().also { line = it } != null) {
            line?.let {
                outBuilder.append(it).append("\n")
                onLine(it)
            }
        }
        
        while (errReader.readLine().also { line = it } != null) {
            line?.let { errBuilder.append(it).append("\n") }
        }
        
        return Pair(outBuilder.toString(), errBuilder.toString())
    }

    fun executeWithSu(command: String, onLine: ((String) -> Unit)? = null): String {
        return try {
            val process = ProcessBuilder("su", "-c", command).start()
            val (out, err) = if (onLine != null) {
                readStreamWithCallback(process, onLine)
            } else {
                val outText = process.inputStream.bufferedReader().use { it.readText() }
                val errText = process.errorStream.bufferedReader().use { it.readText() }
                Pair(outText, errText)
            }
            process.waitFor()
            val exit = process.exitValue()
            when {
                exit == 0 && out.isNotBlank() -> out
                err.isNotBlank() -> "su Error (exit=$exit):\n$err"
                out.isNotBlank() -> out
                else -> "Command finished (exit=$exit) — no output"
            }
        } catch (e: Exception) {
            "Could not execute su: ${e.message}"
        }
    }

    fun executeWithShizuku(command: String, onLine: ((String) -> Unit)? = null): String {
        return try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return "Shizuku not authorized"
            }
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val (out, err) = if (onLine != null) {
                readStreamWithCallback(process, onLine)
            } else {
                val outText = process.inputStream.bufferedReader().use { it.readText() }
                val errText = process.errorStream.bufferedReader().use { it.readText() }
                Pair(outText, errText)
            }
            process.waitFor()
            val exit = process.exitValue()
            when {
                exit == 0 && out.isNotBlank() -> out
                err.isNotBlank() -> "Shizuku Error (exit=$exit):\n$err"
                out.isNotBlank() -> out
                else -> "Command finished via Shizuku (exit=$exit) — no output"
            }
        } catch (t: Throwable) {
            "Could not execute via Shizuku: ${t.message}"
        }
    }
}