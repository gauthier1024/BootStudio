package utils

import java.io.File

object MagiskManager {

    private const val MODULE_PATH = "/data/adb/modules/BootStudio"

    fun createMagiskModule(setupPath: String): String {
        val targetDir = File(setupPath).parent ?: ""
        val backupFileName = setupPath.trimStart('/').replace('/', '_')
        val backupFile = "$MODULE_PATH/original/$backupFileName"

        val commands = mutableListOf(
            "mkdir -p \"$MODULE_PATH$targetDir\"",
            "mkdir -p \"$MODULE_PATH/original\"",
            "if [ ! -f \"$backupFile\" ]; then cp \"$setupPath\" \"$backupFile\"; fi",
            "printf \"id=bootstudio\\nname=BootStudio Bootanimation\\nversion=1.0\\nversionCode=1\\nauthor=BootStudio\\ndescription=Custom bootanimation overlay\\n\" > $MODULE_PATH/module.prop",
            "touch $MODULE_PATH/auto_mount",
            "rm -f $MODULE_PATH/disable"
        )

        return CommandExecutor.executeWithSu(commands.joinToString(" && "))
    }

    fun disableMagiskModule(): String {
        return CommandExecutor.executeWithSu("touch $MODULE_PATH/disable")
    }

    fun changeBootAnimation(zipPath: String, targetSystemPath: String): String {
        val targetDir = File(targetSystemPath).parent ?: ""
        val moduleZipPath = "$MODULE_PATH$targetDir/bootanimation.zip"
        val commands = listOf(
            "rm -f $MODULE_PATH/disable",
            "mkdir -p \"$MODULE_PATH$targetDir\"",
            "cp \"$zipPath\" \"$moduleZipPath\"",
            "chmod 644 \"$moduleZipPath\"",
            "chown root:root \"$moduleZipPath\""
        )
        return CommandExecutor.executeWithSu(commands.joinToString(" && "))
    }
}
