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
    }

    fun log(type: String, purpose: String, content: String) {
        val timestamp = dateFormat.format(Date())
        val pType = type.replaceFirstChar { it.uppercase() }.padEnd(6)
        val pPurpose = purpose.replaceFirstChar { it.uppercase() }.padEnd(25)
        val formattedMessage = "[$timestamp] $pType | $pPurpose | $content\n"
        
        // Print to logcat as well
        android.util.Log.d("BootStudioDebug", formattedMessage.trim())
        
        try {
            logFile?.appendText(formattedMessage)
        } catch (e: Exception) {
            android.util.Log.e("BootStudioDebug", "Failed to write to log file", e)
        }
    }

    // Keep the old one for simple messages if needed, but mark as internal/private or update it
    fun log(message: String) {
        log("Info", "General", message)
    }

    fun getLogFile(): File? = logFile

    fun clearLog() {
        try {
            logFile?.writeText("")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}