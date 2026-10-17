package utils

/**
 * Helpers for building commands that are executed through the root shell.
 *
 * Every value that is not a compile-time constant MUST go through [quote]
 * (or be passed as a separate argument to FFmpegKit, which quotes for you).
 */
object ShellUtils {

    /**
     * Quotes [arg] so that a POSIX shell treats it as exactly one literal word,
     * whatever it contains (spaces, quotes, `$()`, backticks, `;`, ...).
     */
    fun quote(arg: String): String {
        require(arg.none { it == '\u0000' }) { "NUL byte in shell argument" }
        // Newlines would also desynchronise the line-based shell protocol used by CommandExecutor.
        require(arg.none { it == '\n' || it == '\r' }) { "Line break in shell argument" }
        return "'" + arg.replace("'", "'\\''") + "'"
    }

    /**
     * Conservative whitelist for absolute paths that end up in the module's
     * `service.sh` (which Magisk/KernelSU/APatch run as root at every boot).
     * Real boot animation paths only use these characters.
     */
    private val SAFE_SYSTEM_PATH = Regex("^/[A-Za-z0-9_.+@/-]+$")

    fun isSafeSystemPath(path: String): Boolean =
        SAFE_SYSTEM_PATH.matches(path) &&
            path.split('/').none { it == ".." } &&
            path.endsWith(".zip", ignoreCase = true)

    /** An absolute path with no control characters and no ".." segment. */
    fun isSafeAbsolutePath(path: String): Boolean =
        path.startsWith("/") && path.none { it.isISOControl() } && path.split('/').none { it == ".." }

    /** Only digits, 1..[maxDigits] long. Used for numeric fields typed by the user. */
    fun isPositiveInt(value: String, maxDigits: Int = 5): Boolean =
        value.length in 1..maxDigits && value.all { it in '0'..'9' }
}
