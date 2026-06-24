package utils

import java.io.File

object MagiskManager {

    private const val MODULE_PATH = "/data/adb/modules/BootStudio/system"

    private fun getModulePathForSystemFile(systemPath: String): String {
        val path = systemPath.trim()
        // Magisk modules usually expect all system overlays to be under the /system folder of the module,
        // mirroring the full path from the root.
        return "/data/adb/modules/BootStudio/system$path"
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
        val path = targetSystemPath.trim()
        
        // Handle /data paths directly (Magisk cannot overlay /data)
        if (path.startsWith("/data/")) {
            val targetDir = File(path).parent ?: "/data/misc/bootanim"
            val commands = listOf(
                "mkdir -p \"$targetDir\"",
                "cp \"$zipPath\" \"$path\"",
                "chmod 644 \"$path\"",
                "chown root:root \"$path\""
            )
            return CommandExecutor.executeWithSu(commands.joinToString(" && "))
        }

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
        val path = targetSystemPath.trim()
        if (path.startsWith("/data/")) {
            // For /data paths, we should restore from backup if we have one, 
            // but for now we just remove it to revert to system default
            return CommandExecutor.executeWithSu("rm -f \"$path\"")
        }
        val moduleZipPath = getModulePathForSystemFile(targetSystemPath)
        return CommandExecutor.executeWithSu("rm -f \"$moduleZipPath\"")
    }


}
