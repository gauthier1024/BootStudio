package utils

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticLogger {
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun init(context: Context) {
        val logDir = File(context.cacheDir, "logs")
        if (!logDir.exists()) logDir.mkdirs()
        logFile = File(logDir, "log.txt")
        // Start fresh on each app init or keep it? User asked for EVERYTHING, so let's append but add a session header.
        log("--- DIAGNOSTIC SESSION STARTED ---")
    }

    fun log(message: String) {
        val timestamp = dateFormat.format(Date())
        val formattedMessage = "[$timestamp] $message\n"
        
        // Print to logcat as well
        android.util.Log.d("BootStudioDebug", message)
        
        try {
            logFile?.appendText(formattedMessage)
        } catch (e: Exception) {
            android.util.Log.e("BootStudioDebug", "Failed to write to log file", e)
        }
    }

    fun getLogFile(): File? = logFile

    fun clearLog() {
        try {
            logFile?.writeText("")
            log("--- LOG CLEARED ---")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}