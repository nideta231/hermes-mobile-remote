package io.github.nideta231.hermesremote.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Files (PDFs, documents, code, anything) sent with a message. Each is staged on the PC through
 * Hermes' `file.attach` and the message carries its `@file:` reference, as on the desktop.
 */
object FileAttachments {
    const val MAX_PER_MESSAGE = 6
    /** Base64 grows a file by a third and the bridge takes frames up to 16 MB. */
    const val MAX_BYTES = 10 * 1024 * 1024

    /** Size the picker reports for [uri], or null when it doesn't say. */
    fun size(context: Context, uri: String): Long? = runCatching {
        context.contentResolver.query(Uri.parse(uri), arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    /** "2.4 MB" / "830 KB": how big a file is, for limit messages. */
    fun sizeLabel(bytes: Long): String =
        if (bytes >= 1024 * 1024) String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0)) else "${(bytes + 1023) / 1024} KB"

    /** Why [name] can't be attached to a board card, or null when it can (same 10 MB rule as the PC bridge). */
    fun boardLimitError(name: String, size: Long?): String? =
        if (size != null && size > MAX_BYTES) "$name is ${sizeLabel(size)}; board attachments can be up to ${MAX_BYTES / (1024 * 1024)} MB" else null

    /** How a board attachment is previewed: a thumbnail, its first lines, or just the file card. */
    enum class Preview { PICTURE, TEXT, NONE }

    private val pictureExt = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
    private val textExt = setOf("txt", "md", "markdown", "csv", "tsv", "log", "json", "yaml", "yml", "toml", "ini", "xml",
        "html", "css", "js", "ts", "kt", "kts", "py", "sh", "sql", "java", "c", "cpp", "h", "go", "rs", "php")

    fun previewOf(path: String): Preview = when (path.substringAfterLast('/').substringAfterLast('.', "").lowercase()) {
        in pictureExt -> Preview.PICTURE
        in textExt -> Preview.TEXT
        else -> Preview.NONE
    }

    /** The first [maxLines] lines of a text file (at most [maxChars]), for a preview under its file card. */
    fun textPeek(text: String, maxLines: Int = 8, maxChars: Int = 800): String {
        val lines = text.take(maxChars).lines()
        val cut = lines.take(maxLines).joinToString("\n").trimEnd()
        return if (lines.size > maxLines || text.length > maxChars) "$cut\n…" else cut
    }

    fun name(context: Context, uri: String): String = runCatching {
        context.contentResolver.query(Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: Uri.parse(uri).lastPathSegment?.substringAfterLast('/') ?: "file"

    suspend fun read(context: Context, uri: String): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        val name = name(context, uri)
        val bytes = context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
                if (buf.size() > MAX_BYTES) throw IOException("$name is over the ${MAX_BYTES / (1024 * 1024)} MB limit")
            }
            buf.toByteArray()
        } ?: throw IOException("Couldn't read $name")
        bytes to name
    }

    fun dataUrl(bytes: ByteArray, mime: String?): String =
        "data:${mime ?: "application/octet-stream"};base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
}

/** The text sent to Hermes: what the user typed, then one `@file:` reference per line. */
fun withFileRefs(text: String, refs: List<String>): String =
    if (refs.isEmpty()) text else (listOf(text.trim()).filter { it.isNotEmpty() } + refs).joinToString("\n")
