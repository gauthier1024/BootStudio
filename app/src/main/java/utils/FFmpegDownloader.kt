package utils

import android.content.Context
import android.os.Build
import com.arthenica.ffmpegkit.FFmpegKitConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import utils.DiagnosticLogger
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object FFmpegDownloader {

    private const val BASE_URL = "https://repo1.maven.org/maven2/com/mrljdx/ffmpeg-kit-full/6.1.4/ffmpeg-kit-full-6.1.4.aar"

    fun isInstalled(context: Context): Boolean {
        val libDir = File(context.filesDir, "libs")
        // Be more flexible: check if the directory exists and contains any shared libraries
        return libDir.exists() && libDir.listFiles { _, name -> name.endsWith(".so") }?.isNotEmpty() == true
    }

    fun initLoader(context: Context) {
        val libDir = File(context.filesDir, "libs")
        if (libDir.exists()) {
            val libs = libDir.listFiles { _, name -> name.endsWith(".so") }
            
            // Define the order in which libraries should be loaded to respect dependencies
            val loadOrder = listOf(
                "libffmpegkit_abidetect.so",
                "libavutil.so",
                "libswresample.so",
                "libavcodec.so",
                "libavformat.so",
                "libswscale.so",
                "libavfilter.so",
                "libavdevice.so",
                "libffmpegkit.so"
            )

            // First load from our ordered list if they exist
            loadOrder.forEach { libName ->
                val libFile = File(libDir, libName)
                if (libFile.exists()) {
                    try {
                        System.load(libFile.absolutePath)
                    } catch (e: UnsatisfiedLinkError) {
                        android.util.Log.w("FFmpegDownloader", "Could not load $libName yet: ${e.message}")
                    }
                }
            }

            // Then try to load any remaining libraries
            libs?.forEach { lib ->
                try {
                    System.load(lib.absolutePath)
                } catch (e: UnsatisfiedLinkError) {
                    // Ignore, might be already loaded or handled later
                }
            }
        }
    }

    suspend fun downloadAndInstall(context: Context, urlOverride: String? = null, onProgress: (Float, String?) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            val libDir = File(context.filesDir, "libs")
            if (!libDir.exists()) libDir.mkdirs()

            // ... (keep download logic)
            val url = URL(urlOverride ?: BASE_URL)
            // ... (rest of download logic until extraction)
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

            // 1. Identify all available ABIs in the AAR
            val availableAbis = mutableSetOf<String>()
            ZipInputStream(tempAar.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name.startsWith("jni/") && entry.name.endsWith(".so")) {
                        val parts = entry.name.split("/")
                        if (parts.size >= 3) availableAbis.add(parts[1])
                    }
                    entry = zip.nextEntry
                }
            }

            // 2. Select the best ABI supported by the device
            val bestAbi = Build.SUPPORTED_ABIS.firstOrNull { availableAbis.contains(it) }
            android.util.Log.d("FFmpegDownloader", "Best ABI available: $bestAbi")

            var extractedCount = 0
            if (bestAbi != null) {
                // 3. Extract libraries for the best ABI
                ZipInputStream(tempAar.inputStream()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (entry.name.startsWith("jni/$bestAbi/") && entry.name.endsWith(".so")) {
                            val fileName = entry.name.substringAfterLast("/")
                            val outFile = File(libDir, fileName)
                            try {
                                FileOutputStream(outFile).use { out -> zip.copyTo(out) }
                                extractedCount++
                            } catch (e: Exception) {
                                android.util.Log.e("FFmpegDownloader", "Extraction error: $fileName", e)
                            }
                        }
                        entry = zip.nextEntry
                    }
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
