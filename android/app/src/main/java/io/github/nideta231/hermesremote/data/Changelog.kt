package io.github.nideta231.hermesremote.data

/** One "## vX.Y.Z" section of CHANGELOG.md (also the GitHub release notes, which are a copy of it). */
data class ChangelogEntry(val version: String, val body: String)

private val versionHeading = Regex("""^##\s+v?(\d+(?:\.\d+)*)\s*$""")

/** The versioned sections, newest first as written. "## Earlier" and the preamble are dropped. */
fun parseChangelog(md: String): List<ChangelogEntry> {
    val out = mutableListOf<ChangelogEntry>()
    var version: String? = null
    val body = StringBuilder()
    fun flush() {
        version?.let { out += ChangelogEntry(it, unwrapContinuations(body.toString().trim())) }
        body.clear()
    }
    for (line in md.lines()) {
        val m = versionHeading.find(line)
        when {
            m != null -> { flush(); version = m.groupValues[1] }
            line.startsWith("## ") -> { flush(); version = null }
            version != null -> body.append(line).append('\n')
        }
    }
    flush()
    return out
}

/**
 * What changed after [from] up to and including [to], as markdown with one heading per version.
 * [from] null means "only [to]": the previous version is unknown (the app was updated before it
 * started remembering versions).
 */
fun changesBetween(md: String, from: String?, to: String): String =
    parseChangelog(md)
        .filter { e -> !Updater.isNewer(e.version, to) && (if (from == null) e.version == to else Updater.isNewer(e.version, from)) }
        .joinToString("\n\n") { "## v${it.version}\n${it.body}" }

/** The whole changelog as markdown, newest first. */
fun fullChangelog(md: String): String = parseChangelog(md).joinToString("\n\n") { "## v${it.version}\n${it.body}" }

/**
 * CHANGELOG.md hard-wraps bullets and paragraphs at ~100 columns. On a phone that shows as ragged
 * short lines, so join every wrapped line back onto the line it continues. Bullets, headings,
 * tables and code fences start a new line.
 */
internal fun unwrapContinuations(text: String): String {
    val out = mutableListOf<String>()
    var inFence = false
    for (line in text.lines()) {
        val t = line.trimStart()
        if (t.startsWith("```")) inFence = !inFence
        val prev = out.lastOrNull()
        val startsBlock = t.isEmpty() || inFence || t.startsWith("```") || t.startsWith("#") || t.startsWith("|") ||
            Regex("""^([-*+]|\d+\.)\s""").containsMatchIn(t)
        val joinable = prev != null && prev.isNotBlank() && !prev.trimStart().startsWith("#") && !prev.trimStart().startsWith("|")
        if (!startsBlock && joinable) out[out.size - 1] = prev!!.trimEnd() + " " + t else out += line
    }
    return out.joinToString("\n")
}
