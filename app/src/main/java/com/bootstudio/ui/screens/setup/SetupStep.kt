package com.bootstudio.ui.screens.setup

enum class SetupStep {
    GRANT_PERMISSION,
    SEARCHING,
    SELECT_PATH,
    ASK_DOWNLOAD_FFMPEG,
    DOWNLOAD_FFMPEG,
    DONE
}

data class ConsoleLine(val text: String, val isFound: Boolean = false)
