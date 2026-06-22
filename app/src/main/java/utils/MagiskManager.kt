package utils

import java.io.File

object MagiskManager {

    private const val MODULE_PATH = "/data/adb/modules/BootStudio/system"

    fun createMagiskModule(setupPath: String): String {
        val targetDir = File(setupPath).parent ?: ""
        // Use the actual module root (parent of /system) for metadata files
        val moduleRoot = File(MODULE_PATH).parent ?: "/data/adb/modules/BootStudio"
        val backupFileName = setupPath.trimStart('/').replace('/', '_')
        val backupFile = "$moduleRoot/original/$backupFileName"

        val commands = mutableListOf(
            "mkdir -p \"$MODULE_PATH$targetDir\"",
            "mkdir -p \"$moduleRoot/original\"",
            "if [ ! -f \"$backupFile\" ]; then cp \"$setupPath\" \"$backupFile\"; fi",
            "printf \"id=bootstudio\\nname=BootStudio Bootanimation\\nversion=1.0\\nversionCode=1\\nauthor=BootStudio\\ndescription=Custom bootanimation overlay\\n\" > $moduleRoot/module.prop",
            "touch $moduleRoot/auto_mount",
            "rm -f $moduleRoot/disable"
        )

        return CommandExecutor.executeWithSu(commands.joinToString(" && "))
    }

    fun disableMagiskModule(): String {
        val moduleRoot = File(MODULE_PATH).parent ?: "/data/adb/modules/BootStudio"
        return CommandExecutor.executeWithSu("touch $moduleRoot/disable")
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

    fun setDefaultAnimation(targetSystemPath: String): String {
        val targetDir = File(targetSystemPath).parent ?: ""
        val moduleZipPath = "$MODULE_PATH$targetDir/bootanimation.zip"
        return CommandExecutor.executeWithSu("rm -f \"$moduleZipPath\"")
    }


}
