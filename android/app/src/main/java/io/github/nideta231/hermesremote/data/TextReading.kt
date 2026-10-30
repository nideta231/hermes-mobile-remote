package io.github.nideta231.hermesremote.data

/** Reading a text file from the PC inside the app (the built-in reader). Pure apart from [load]. */
object TextReading {
    /** More than this is cut (the reader says so; Save keeps the whole file). */
    const val MAX_CHARS = 300_000

    data class Loaded(val text: String, val cut: Boolean, val error: String? = null)

    fun isMarkdown(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown", "mdown", "mkd")

    /** Opened in the built-in reader instead of another app: every file with a text preview. */
    fun readsInApp(name: String): Boolean = FileAttachments.previewOf(name) == FileAttachments.Preview.TEXT

    /** [raw] as shown: line endings normalised, cut at [max] characters on a line break when one is near. */
    fun prepare(raw: String, max: Int = MAX_CHARS): Loaded {
        val t = raw.removePrefix("\uFEFF").replace("\r\n", "\n")
        if (t.length <= max) return Loaded(t, false)
        val nl = t.lastIndexOf('\n', max)
        return Loaded(t.substring(0, if (nl > max - 2000) nl else max), true)
    }

    fun load(file: java.io.File): Loaded = try {
        // Not readNBytes: that is API 33 on Android, and the app runs from API 26.
        val bytes = file.inputStream().use { input ->
            val buf = ByteArray(minOf(file.length(), MAX_CHARS * 4L + 4).toInt())
            var n = 0
            while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
            buf.copyOf(n)
        }
        prepare(String(bytes, Charsets.UTF_8)).let { if (file.length() > bytes.size) it.copy(cut = true) else it }
    } catch (t: Exception) {
        Loaded("", false, "Couldn't read ${file.name}: ${t.message ?: t.javaClass.simpleName}")
    }
}
