package com.bootstudio.ui.screens.setup

enum class SetupStep {
    GRANT_PERMISSION,
    SEARCHING,
    SELECT_PATH,
    DONE
}

data class ConsoleLine(val text: String, val isFound: Boolean = false)
