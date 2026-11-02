package io.github.nideta231.hermesremote.data

/** One row of the Tree view's display list: a chat row as it is, or a folded stretch of thoughts and tools. */
sealed interface FoldedItem {
    data class Item(val item: ChatItem) : FoldedItem

    data class Group(
        val key: String,
        val items: List<ChatItem>,
        val toolCount: Int,
        val thoughtCount: Int,
        val durationSec: Double,
        val failedCount: Int,
        val running: Boolean,
        val lastTurn: Boolean,
    ) : FoldedItem
}

/** A thought and the tools that followed it; [thought] is null for tools before the first thought. */
data class ToolStep(val thought: ChatItem.Thinking?, val tools: List<ChatItem.Tool>, val failedCount: Int, val running: Boolean)

private fun ChatItem.joinsStretch() =
    this is ChatItem.Thinking || (this is ChatItem.Tool && name != Delegate.TOOL)

/** Folds each stretch of 2+ thought/tool rows holding a tool into one group; everything else stays as it is. */
fun foldTools(items: List<ChatItem>, busy: Boolean): List<FoldedItem> {
    val lastUser = items.indexOfLast { it is ChatItem.User }
    val out = mutableListOf<FoldedItem>()
    var i = 0
    while (i < items.size) {
        if (!items[i].joinsStretch()) { out += FoldedItem.Item(items[i]); i++; continue }
        var end = i
        while (end < items.size && items[end].joinsStretch()) end++
        val stretch = items.subList(i, end).toList()
        val tools = stretch.filterIsInstance<ChatItem.Tool>()
        if (stretch.size >= 2 && tools.isNotEmpty()) {
            out += FoldedItem.Group(
                key = "fold-" + stretch[0].key,
                items = stretch,
                toolCount = tools.size,
                thoughtCount = stretch.size - tools.size,
                durationSec = tools.sumOf { it.durationSec ?: 0.0 },
                failedCount = tools.count { it.status == ToolStatus.FAILED },
                running = tools.any { it.status == ToolStatus.RUNNING } ||
                    stretch.any { it is ChatItem.Thinking && it.streaming } || (busy && end == items.size),
                lastTurn = i > lastUser,
            )
        } else stretch.forEach { out += FoldedItem.Item(it) }
        i = end
    }
    return out
}

/** The steps of a group's rows: each thought starts a step, the tools after it belong to it. */
fun stepsOf(items: List<ChatItem>): List<ToolStep> {
    val steps = mutableListOf<ToolStep>()
    var thought: ChatItem.Thinking? = null
    var tools = mutableListOf<ChatItem.Tool>()
    var open = false
    fun close() {
        if (open) steps += ToolStep(thought, tools, tools.count { it.status == ToolStatus.FAILED },
            tools.any { it.status == ToolStatus.RUNNING })
    }
    for (it in items) when (it) {
        is ChatItem.Thinking -> { close(); thought = it; tools = mutableListOf(); open = true }
        is ChatItem.Tool -> { open = true; tools += it }
        else -> {}
    }
    close()
    return steps
}
