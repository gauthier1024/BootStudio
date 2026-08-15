package com.bootstudio.ui.screens.setup

enum class SetupStep {
    GRANT_PERMISSION,
    READY_TO_SCAN,
    SEARCHING,
    SELECT_PATH,
    DONE
}

data class ConsoleLine(val text: String, val isFound: Boolean = false)
