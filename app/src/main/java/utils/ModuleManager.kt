package utils

import com.bootstudio.BuildConfig

object ModuleManager {

    private const val MODULE_ROOT = "/data/adb/modules/BootStudio"
    const val CENTRAL_ZIP = "$MODULE_ROOT/bootanimation.zip"

    private const val ERR_UNSAFE_PATH = "Error: refusing unsafe path"

    /**
     * Builds the module's service.sh. It is executed as root at every boot by the root
     * manager, so every path is validated by the callers and single-quoted here.
     */
    private fun getServiceScriptContent(paths: List<String>): String {
        val pathsString = paths.joinToString(" ") { ShellUtils.quote(it) }
        return """
            #!/system/bin/sh
            MODDIR=${'$'}{0%/*}
            CENTRAL="${'$'}MODDIR/bootanimation.zip"

            [ ! -f "${'$'}CENTRAL" ] && exit 0

            for p in $pathsString; do
                case "${'$'}p" in
                    /data/*)
                        i=0
                        while [ ! -f "${'$'}p" ] && [ ${'$'}i -lt 30 ]; do
                            sleep 1
                            i=${'$'}((i+1))
                        done
                        ;;
                esac

                if [ -f "${'$'}p" ]; then
                    mount --bind "${'$'}CENTRAL" "${'$'}p"
                fi
            done
        """.trimIndent()
    }

    /** Only paths that look like real boot animation locations may reach the root shell or service.sh. */
    private fun allSafe(paths: List<String>) = paths.all { ShellUtils.isSafeSystemPath(it) }

    fun createModule(setupPaths: List<String>): String {
        if (!allSafe(setupPaths)) return ERR_UNSAFE_PATH

        val commands = mutableListOf(
            "mkdir -p ${ShellUtils.quote("$MODULE_ROOT/original")}",
            "printf 'id=BootStudio\\nname=BootStudio\\nversion=${BuildConfig.VERSION_NAME}\\nversionCode=${BuildConfig.VERSION_CODE}\\nauthor=gauthier1024\\ndescription=Custom bootanimation overlay\\n' > ${ShellUtils.quote("$MODULE_ROOT/module.prop")}",
            "cat << 'EOF' > ${ShellUtils.quote("$MODULE_ROOT/action.sh")}\n#!/system/bin/sh\nam start -n ${BuildConfig.APPLICATION_ID}/${BuildConfig.APPLICATION_ID}.MainActivity\nEOF",
            "chmod 755 ${ShellUtils.quote("$MODULE_ROOT/action.sh")}",
            "chown root:root ${ShellUtils.quote("$MODULE_ROOT/action.sh")}",
            "echo '#!/system/bin/sh' > ${ShellUtils.quote("$MODULE_ROOT/service.sh")}",
            "chmod 755 ${ShellUtils.quote("$MODULE_ROOT/service.sh")}",
            "rm -f ${ShellUtils.quote(CENTRAL_ZIP)}",
            "touch ${ShellUtils.quote("$MODULE_ROOT/auto_mount")}",
            "rm -f ${ShellUtils.quote("$MODULE_ROOT/disable")}"
        )

        setupPaths.forEach { path ->
            val backupFileName = path.trimStart('/').replace('/', '_')
            val backupFile = ShellUtils.quote("$MODULE_ROOT/original/$backupFileName")
            val qPath = ShellUtils.quote(path)
            commands.add("if [ ! -f $backupFile ]; then cp $qPath $backupFile; fi")
            commands.add("umount $qPath 2>/dev/null")
        }

        var lastResult = ""
        for (cmd in commands) {
            lastResult = CommandExecutor.executeWithSu(cmd, purpose = "setup")
            if (lastResult.startsWith("Error:")) return lastResult
        }
        return lastResult
    }

    fun disableModule(): String =
        CommandExecutor.executeWithSu("touch ${ShellUtils.quote("$MODULE_ROOT/disable")}", purpose = "disabling module")

    /**
     * [zipPath] is the source animation (app-private file); [targetSystemPaths] the
     * system locations the module bind-mounts it over.
     */
    fun changeBootAnimation(zipPath: String, targetSystemPaths: List<String>): String {
        if (!allSafe(targetSystemPaths) || !ShellUtils.isSafeAbsolutePath(zipPath)) return ERR_UNSAFE_PATH
        val qCentral = ShellUtils.quote(CENTRAL_ZIP)
        val qService = ShellUtils.quote("$MODULE_ROOT/service.sh")

        val commands = mutableListOf(
            "rm -f ${ShellUtils.quote("$MODULE_ROOT/disable")}",
            "mkdir -p ${ShellUtils.quote(MODULE_ROOT)}",
            "cp ${ShellUtils.quote(zipPath)} $qCentral",
            "chmod 644 $qCentral",
            "chown root:root $qCentral",
            "cat << 'EOF' > $qService\n${getServiceScriptContent(targetSystemPaths)}\nEOF",
            "chmod 755 $qService"
        )

        // Apply mounts immediately so they are visible without reboot
        targetSystemPaths.forEach { path ->
            val qPath = ShellUtils.quote(path)
            commands.add("if [ -f $qPath ]; then umount $qPath 2>/dev/null; mount --bind $qCentral $qPath; fi")
        }

        var lastResult = ""
        for (cmd in commands) {
            lastResult = CommandExecutor.executeWithSu(cmd, purpose = "changing bootanim")
            if (lastResult.startsWith("Error:")) return lastResult
        }
        return lastResult
    }

    fun setDefaultAnimation(targetSystemPaths: List<String>): String {
        if (!allSafe(targetSystemPaths)) return ERR_UNSAFE_PATH
        val qService = ShellUtils.quote("$MODULE_ROOT/service.sh")
        val commands = mutableListOf(
            "rm -f ${ShellUtils.quote(CENTRAL_ZIP)}",
            "echo '#!/system/bin/sh' > $qService",
            "chmod 755 $qService"
        )

        // Remove mounts immediately
        targetSystemPaths.forEach { path ->
            commands.add("umount ${ShellUtils.quote(path)} 2>/dev/null")
        }

        var lastResult = ""
        for (cmd in commands) {
            lastResult = CommandExecutor.executeWithSu(cmd, purpose = "reverting bootanim")
            // Ignore errors for umount if file wasn't mounted
        }
        return lastResult
    }
}
