package utils

import com.bootstudio.BuildConfig

object ModuleManager {

    const val CENTRAL_ZIP = "/data/adb/modules/BootStudio/bootanimation.zip"

    private fun getServiceScriptContent(paths: List<String>): String {
        val pathsString = paths.joinToString(" ") { "\"$it\"" }
        return """
            #!/system/bin/sh
            MODDIR=${'$'}{0%/*}
            CENTRAL="${'$'}MODDIR/bootanimation.zip"
            
            [ ! -f "${'$'}CENTRAL" ] && exit 0
            
            for p in $pathsString; do
                if echo "${'$'}p" | grep -q "^/data/"; then
                    i=0
                    while [ ! -f "${'$'}p" ] && [ ${'$'}i -lt 30 ]; do
                        sleep 1
                        i=${'$'}((i+1))
                    done
                fi
                
                if [ -f "${'$'}p" ]; then
                    mount --bind "${'$'}CENTRAL" "${'$'}p"
                fi
            done
        """.trimIndent()
    }

    fun createModule(setupPaths: List<String>): String {
        val moduleRoot = "/data/adb/modules/BootStudio"

        val commands = mutableListOf(
            "mkdir -p \"$moduleRoot/original\"",
            "printf \"id=BootStudio\\nname=BootStudio\\nversion=${BuildConfig.VERSION_NAME}\\nversionCode=${BuildConfig.VERSION_CODE}\\nauthor=gauthier1024\\ndescription=Custom bootanimation overlay\\n\" > \"$moduleRoot/module.prop\"",
            "cat << 'EOF' > \"$moduleRoot/action.sh\"\n#!/system/bin/sh\nam start -n ${BuildConfig.APPLICATION_ID}/${BuildConfig.APPLICATION_ID}.MainActivity\nEOF",
            "chmod 755 \"$moduleRoot/action.sh\"",
            "chown root:root \"$moduleRoot/action.sh\"",
            "echo '#!/system/bin/sh' > \"$moduleRoot/service.sh\"",
            "chmod 755 \"$moduleRoot/service.sh\"",
            "rm -f \"$CENTRAL_ZIP\"",
            "touch \"$moduleRoot/auto_mount\"",
            "rm -f \"$moduleRoot/disable\""
        )

        setupPaths.forEach { path ->
            val backupFileName = path.trimStart('/').replace('/', '_')
            val backupFile = "$moduleRoot/original/$backupFileName"
            commands.add("if [ ! -f \"$backupFile\" ]; then cp \"$path\" \"$backupFile\"; fi")
            commands.add("umount \"$path\" 2>/dev/null")
        }

        var lastResult = ""
        for (cmd in commands) {
            lastResult = CommandExecutor.executeWithSu(cmd, purpose = "setup")
            if (lastResult.startsWith("Error:")) return lastResult
        }
        return lastResult
    }

    fun disableModule(): String {
        val moduleRoot = "/data/adb/modules/BootStudio"
        return CommandExecutor.executeWithSu("touch $moduleRoot/disable", purpose = "disabling module")
    }

    fun changeBootAnimation(zipPath: String, targetSystemPaths: List<String>): String {
        val moduleRoot = "/data/adb/modules/BootStudio"
        
        val commands = mutableListOf(
            "rm -f \"$moduleRoot/disable\"",
            "mkdir -p \"$moduleRoot\"",
            "cp \"$zipPath\" \"$CENTRAL_ZIP\"",
            "chmod 644 \"$CENTRAL_ZIP\"",
            "chown root:root \"$CENTRAL_ZIP\"",
            "cat << 'EOF' > \"$moduleRoot/service.sh\"\n${getServiceScriptContent(targetSystemPaths)}\nEOF",
            "chmod 755 \"$moduleRoot/service.sh\""
        )

        // Apply mounts immediately so they are visible without reboot
        targetSystemPaths.forEach { path ->
            commands.add("if [ -f \"$path\" ]; then umount \"$path\" 2>/dev/null; mount --bind \"$CENTRAL_ZIP\" \"$path\"; fi")
        }

        var lastResult = ""
        for (cmd in commands) {
            lastResult = CommandExecutor.executeWithSu(cmd, purpose = "changing bootanim")
            if (lastResult.startsWith("Error:")) return lastResult
        }
        return lastResult
    }

    fun setDefaultAnimation(targetSystemPaths: List<String>): String {
        val moduleRoot = "/data/adb/modules/BootStudio"
        val commands = mutableListOf(
            "rm -f \"$CENTRAL_ZIP\"",
            "echo '#!/system/bin/sh' > \"$moduleRoot/service.sh\"",
            "chmod 755 \"$moduleRoot/service.sh\""
        )
        
        // Remove mounts immediately
        targetSystemPaths.forEach { path ->
            commands.add("umount \"$path\" 2>/dev/null")
        }

        var lastResult = ""
        for (cmd in commands) {
            lastResult = CommandExecutor.executeWithSu(cmd, purpose = "reverting bootanim")
            // Ignore errors for umount if file wasn't mounted
        }
        return lastResult
    }
}
