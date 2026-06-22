package utils

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object FFmpegDownloader {

    private const val BASE_URL = "https://repo1.maven.org/maven2/com/mrljdx/ffmpeg-kit-full/6.1.4/ffmpeg-kit-full-6.1.4.aar"

    fun isInstalled(context: Context): Boolean {
        val libDir = File(context.filesDir, "libs")
        return File(libDir, "libffmpegkit.so").exists() && File(libDir, "libffmpeg.so").exists()
    }

    fun initLoader(context: Context) {
        // Disabled manual loading to prevent SIGSEGV crashes on some devices
        // Modern FFmpegKit handles its own loading. Manual System.load on extracted
        // files can conflict with the library's internal initialization.
        android.util.Log.d("FFmpegDownloader", "Manual loader initialization skipped for safety")
    }

    suspend fun downloadAndInstall(context: Context, urlOverride: String? = null, onProgress: (Float, String?) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            val libDir = File(context.filesDir, "libs")
            if (!libDir.exists()) libDir.mkdirs()

            val url = URL(urlOverride ?: BASE_URL)
            android.util.Log.d("FFmpegDownloader", "Connecting to: $url")
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            
            try {
                connection.connect()
            } catch (e: Exception) {
                android.util.Log.e("FFmpegDownloader", "Connection failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    onProgress(0f, "Connection failed: ${e.javaClass.simpleName} ${e.message}")
                }
                return@withContext false
            }

            val responseCode = connection.responseCode
            android.util.Log.d("FFmpegDownloader", "Response Code: $responseCode")

            if (responseCode != HttpURLConnection.HTTP_OK) {
                if (responseCode in 300..399) {
                    val redirectUrl = connection.getHeaderField("Location")
                    if (redirectUrl != null) {
                        android.util.Log.d("FFmpegDownloader", "Redirecting to: $redirectUrl")
                        return@withContext downloadAndInstall(context, redirectUrl, onProgress)
                    }
                }
                android.util.Log.e("FFmpegDownloader", "HTTP Error: $responseCode")
                withContext(Dispatchers.Main) {
                    onProgress(0f, "HTTP Error: $responseCode ${connection.responseMessage}")
                }
                return@withContext false
            }

            val fileLength = connection.contentLengthLong
            val inputStream = connection.inputStream
            val tempAar = File(context.cacheDir, "ffmpeg.aar")
            
            var lastProgress = 0f
            FileOutputStream(tempAar).use { output ->
                val data = ByteArray(8192)
                var total = 0L
                var count: Int
                while (inputStream.read(data).also { count = it } != -1) {
                    total += count
                    if (fileLength > 0) {
                        val progress = total.toFloat() / fileLength
                        if (progress - lastProgress >= 0.01f || progress >= 1f) {
                            try {
                                withContext(Dispatchers.Main) {
                                    onProgress(progress, null)
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("FFmpegDownloader", "UI update failed", e)
                            }
                            lastProgress = progress
                        }
                    }
                    output.write(data, 0, count)
                }
            }

            // Extract .so files for current ABI
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            android.util.Log.d("FFmpegDownloader", "Extracting for ABI: $abi")
            var extractedCount = 0
            
            ZipInputStream(tempAar.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name.startsWith("jni/$abi/") && entry.name.endsWith(".so")) {
                        val fileName = entry.name.substringAfterLast("/")
                        android.util.Log.d("FFmpegDownloader", "Extracting: $fileName")
                        val outFile = File(libDir, fileName)
                        try {
                            FileOutputStream(outFile).use { out ->
                                zip.copyTo(out)
                            }
                            extractedCount++
                        } catch (e: Exception) {
                            android.util.Log.e("FFmpegDownloader", "Failed to extract $fileName", e)
                        }
                    }
                    try {
                        zip.closeEntry()
                    } catch (e: Exception) {}
                    entry = zip.nextEntry
                }
            }

            android.util.Log.d("FFmpegDownloader", "Extracted $extractedCount files")
            tempAar.delete()
            extractedCount > 0
        } catch (e: Exception) {
            android.util.Log.e("FFmpegDownloader", "Installation failed", e)
            withContext(Dispatchers.Main) {
                onProgress(0f, "Error: ${e.javaClass.simpleName} ${e.message}")
            }
            false
        }
    }
}
