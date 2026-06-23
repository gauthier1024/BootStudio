package utils

import java.io.File

object MagiskManager {

    private const val MODULE_PATH = "/data/adb/modules/BootStudio/system"

    private fun getModulePathForSystemFile(systemPath: String): String {
        // If systemPath is /system/media/bootanimation.zip
        // Magisk overlay path should be /data/adb/modules/BootStudio/system/media/bootanimation.zip
        // So we remove the leading "/system" from the path if it exists
        val relativePath = if (systemPath.startsWith("/system")) {
            systemPath.substring("/system".length)
        } else {
            systemPath
        }
        return "$MODULE_PATH$relativePath"
    }

    fun createMagiskModule(setupPath: String): String {
        val moduleFilePath = getModulePathForSystemFile(setupPath)
        val targetDir = File(moduleFilePath).parent ?: MODULE_PATH
        
        // Use the actual module root (parent of /system) for metadata files
        val moduleRoot = File(MODULE_PATH).parent ?: "/data/adb/modules/BootStudio"
        val backupFileName = setupPath.trimStart('/').replace('/', '_')
        val backupFile = "$moduleRoot/original/$backupFileName"

        val commands = mutableListOf(
            "mkdir -p \"$targetDir\"",
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
        val moduleZipPath = getModulePathForSystemFile(targetSystemPath)
        val targetDir = File(moduleZipPath).parent ?: MODULE_PATH
        
        val commands = listOf(
            "rm -f ${File(MODULE_PATH).parent}/disable",
            "mkdir -p \"$targetDir\"",
            "cp \"$zipPath\" \"$moduleZipPath\"",
            "chmod 644 \"$moduleZipPath\"",
            "chown root:root \"$moduleZipPath\""
        )
        return CommandExecutor.executeWithSu(commands.joinToString(" && "))
    }

    fun setDefaultAnimation(targetSystemPath: String): String {
        val moduleZipPath = getModulePathForSystemFile(targetSystemPath)
        return CommandExecutor.executeWithSu("rm -f \"$moduleZipPath\"")
    }


}
