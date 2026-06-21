package utils

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object CommandExecutor {
    private fun readStream(process: Process): Pair<String, String> {
        val out = process.inputStream.bufferedReader().use { it.readText() }
        val err = process.errorStream.bufferedReader().use { it.readText() }
        return Pair(out, err)
    }

    fun executeWithSu(command: String): String {
        return try {
            val process = ProcessBuilder("su", "-c", command).start()
            val (out, err) = readStream(process)
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

    fun executeWithShizuku(command: String): String {
        return try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return "Shizuku not authorized"
            }
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val (out, err) = readStream(process)
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