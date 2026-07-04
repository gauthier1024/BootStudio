package utils

import android.content.Context

object FFmpegDownloader {

    fun isInstalled(context: Context): Boolean {
        return true // Local FFmpeg build is always present via JNI
    }

    fun initLoader(context: Context) {
        // No-op: Local FFmpeg is loaded via System.loadLibrary("ffmpeg-jni")
    }

    // downloadAndInstall removed as we use local static libraries
}
