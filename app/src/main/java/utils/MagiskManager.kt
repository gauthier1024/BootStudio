package utils

import java.io.File

object MagiskManager {

    private const val MODULE_PATH = "/data/adb/modules/BootStudio/system"

    private fun getModulePathForSystemFile(systemPath: String): String {
        val path = systemPath.trim()
        return "/data/adb/modules/BootStudio/system$path"
    }

    fun createMagiskModule(setupPath: String): String {
        val moduleFilePath = getModulePathForSystemFile(setupPath)
        val targetDir = File(moduleFilePath).parent ?: MODULE_PATH
        
        val moduleRoot = File(MODULE_PATH).parent ?: "/data/adb/modules/BootStudio"
        val backupFileName = setupPath.trimStart('/').replace('/', '_')
        val backupFile = "$moduleRoot/original/$backupFileName"

        val commands = mutableListOf(
            "mkdir -p \"$targetDir\"",
            "mkdir -p \"$moduleRoot/original\"",
            "if [ ! -f \"$backupFile\" ]; then cp \"$setupPath\" \"$backupFile\"; fi",
            "printf \"id=bootstudio\\nname=BootStudio Bootanimation\\nversion=1.1\\nversionCode=2\\nauthor=BootStudio\\ndescription=Custom bootanimation overlay\\n\" > $moduleRoot/module.prop",
            "touch $moduleRoot/auto_mount",
            "rm -f $moduleRoot/disable"
        )

        // Special handling for /data paths using bind mount via service.sh
        if (setupPath.startsWith("/data/")) {
            val scriptContent = """
                #!/system/bin/sh
                while [ ! -f "$setupPath" ]; do
                  sleep 1
                done
                mount --bind "$moduleFilePath" "$setupPath"
            """.trimIndent()
            
            commands.add("printf '$scriptContent' > $moduleRoot/service.sh")
            commands.add("chmod 755 $moduleRoot/service.sh")
        } else {
            commands.add("rm -f $moduleRoot/service.sh")
        }

        return CommandExecutor.executeWithSu(commands.joinToString(" && "), purpose = "setup")
    }

    fun disableMagiskModule(): String {
        val moduleRoot = File(MODULE_PATH).parent ?: "/data/adb/modules/BootStudio"
        return CommandExecutor.executeWithSu("touch $moduleRoot/disable", purpose = "disabling module")
    }

    fun changeBootAnimation(zipPath: String, targetSystemPath: String): String {
        val moduleZipPath = getModulePathForSystemFile(targetSystemPath)
        val targetDir = File(moduleZipPath).parent ?: MODULE_PATH
        val moduleRoot = File(MODULE_PATH).parent ?: "/data/adb/modules/BootStudio"
        
        val commands = listOf(
            "rm -f $moduleRoot/disable",
            "mkdir -p \"$targetDir\"",
            "cp \"$zipPath\" \"$moduleZipPath\"",
            "chmod 644 \"$moduleZipPath\"",
            "chown root:root \"$moduleZipPath\""
        )
        return CommandExecutor.executeWithSu(commands.joinToString(" && "), purpose = "changing bootanim")
    }

    fun setDefaultAnimation(targetSystemPath: String): String {
        val moduleZipPath = getModulePathForSystemFile(targetSystemPath)
        return CommandExecutor.executeWithSu("rm -f \"$moduleZipPath\"", purpose = "reverting bootanim")
    }
}
