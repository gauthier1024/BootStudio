package utils

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Turns externally supplied names (content-provider display names, titles from
 * the community list, user input) into names that are safe to use as a single
 * path component inside an app directory.
 */
object FileNames {

    private const val MAX_BASE_LENGTH = 80
    private val UNSAFE_CHARS = Regex("[^\\p{L}\\p{N} ._()+\\-]")

    /** Strips any directory part and replaces unexpected characters. Never returns an empty string. */
    fun sanitize(raw: String?, fallback: String): String {
        var s = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        s = UNSAFE_CHARS.replace(s, "_").trim().trim('.', ' ')
        if (s.length > MAX_BASE_LENGTH) s = s.take(MAX_BASE_LENGTH).trim().trim('.', ' ')
        return s.ifEmpty { fallback }
    }

    /** Same as [sanitize] but always ends with ".zip". */
    fun zipFileName(raw: String?, fallbackBase: String): String {
        var base = sanitize(raw, "")
        if (base.endsWith(".zip", ignoreCase = true)) base = base.dropLast(4).trim().trim('.', ' ')
        return base.ifEmpty { fallbackBase } + ".zip"
    }

    /** True if [file] resolves (after symlinks and "..") to a location strictly inside [dir]. */
    fun isInside(dir: File, file: File): Boolean {
        val base = dir.canonicalFile
        val target = file.canonicalFile
        return target.path.startsWith(base.path + File.separator)
    }

    /** Returns `File(dir, name)` only if it is a direct child of [dir]; null otherwise. */
    fun resolveChild(dir: File, name: String): File? {
        val f = File(dir, name)
        return if (isInside(dir, f) && f.canonicalFile.parentFile == dir.canonicalFile) f else null
    }

    /** Archive entry names must be relative, free of ".." segments and control characters. */
    fun isSafeEntryName(name: String): Boolean {
        val n = name.replace('\\', '/')
        return n.isNotEmpty() &&
            !n.startsWith("/") &&
            n.none { it.isISOControl() } &&
            n.split('/').none { it == ".." }
    }

    fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? =
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (_: Exception) {
            null
        }

    /** Target file in the library directory for an imported animation, always a direct child of [libraryDir]. */
    fun libraryFile(libraryDir: File, rawName: String?): File {
        val fallback = "imported_${System.currentTimeMillis()}"
        return resolveChild(libraryDir, zipFileName(rawName, fallback))
            ?: File(libraryDir, "$fallback.zip")
    }
}
