package kr.carlog.pleos

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

data class ShareCleanupResult(val removed: Int, val failed: Int)

/** Explicit user action only: restrict deletion to files created by this app for sharing. */
fun clearShareFiles(context: Context): ShareCleanupResult {
    val dir = File(context.cacheDir, "carlog-shares")
    if (!dir.exists()) return ShareCleanupResult(0, 0)
    val files = dir.listFiles() ?: throw IllegalStateException("Cannot list share directory")
    var removed = 0
    var failed = 0
    files.filter { it.isFile && ((it.name.startsWith("carlog-event-") && it.name.endsWith(".json")) ||
        (it.name.startsWith("carlog-report-") && it.name.endsWith(".txt"))) }.forEach {
        if (it.delete()) removed++ else failed++
    }
    return ShareCleanupResult(removed, failed)
}

/** Call off the main thread. Only this dedicated cache directory is exposed by FileProvider. */
fun createShareIntent(context: Context, text: String, json: Boolean): Intent {
    val dir = File(context.cacheDir, "carlog-shares")
    check(dir.isDirectory || dir.mkdirs()) { "Cannot create share directory" }
    val file = File.createTempFile(if (json) "carlog-event-" else "carlog-report-", if (json) ".json" else ".txt", dir)
    try {
        file.writeText(text, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
        return Intent(Intent.ACTION_SEND).setType(if (json) "application/json" else "text/plain").apply {
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } catch (error: Exception) {
        file.delete()
        throw error
    }
}
