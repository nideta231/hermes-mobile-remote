package io.github.nideta231.hermesremote.data

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

enum class GraphKind { CHAT, PROJECT, TOPIC, MODEL,
    /** Knowledge map: a memory, a client, and the "everywhere" hub global memories hang off. */
    MEMORY, CLIENT, GLOBAL }

/** One dot. [ref] is the session id for a chat, the project id for a project, else the label. */
data class GraphNode(val id: String, val kind: GraphKind, val label: String, val ref: String,
                     /** Chats: when it was last active (epoch s), for the reveal order. */
                     val at: Double = 0.0,
                     /** Knowledge map: a memory's kind (mistake, rule...), which picks its colour. */
                     val tag: String = "",
                     /** Knowledge map: a memory's severity (1-5) or a hub's memory count; sizes the dot. */
                     val weight: Int = 0)

data class GraphEdge(val a: Int, val b: Int)

data class ChatGraph(val nodes: List<GraphNode>, val edges: List<GraphEdge>) {
    /** Links per node: hubs draw bigger, like Obsidian. */
    val degree: IntArray by lazy { IntArray(nodes.size).also { d -> edges.forEach { d[it.a]++; d[it.b]++ } } }
    val neighbors: List<Set<Int>> by lazy {
        val n = List(nodes.size) { mutableSetOf<Int>() }
        edges.forEach { n[it.a] += it.b; n[it.b] += it.a }
        n
    }

    companion object { val EMPTY = ChatGraph(emptyList(), emptyList()) }
}

data class GraphOptions(val projects: Boolean = true, val topics: Boolean = true, val models: Boolean = false,
                        /** Most recent chats kept; the layout is O(n²). */
                        val maxChats: Int = 250)

/**
 * The chat map: every chat is a dot, linked to its project, to the topics its title shares with
 * other chats, and (optionally) to the model it ran on. Pure, so it is tested without a device.
 */
object ChatGraphBuilder {
    fun build(sessions: List<SessionSummary>, projects: List<ProjectRef>, o: GraphOptions = GraphOptions()): ChatGraph {
        val chats = sessions.sortedByDescending { it.lastActive ?: it.startedAt ?: 0.0 }.take(o.maxChats)
        val nodes = mutableListOf<GraphNode>()
        val edges = mutableListOf<GraphEdge>()
        val index = HashMap<String, Int>()
        fun node(n: GraphNode): Int = index.getOrPut(n.id) { nodes += n; nodes.lastIndex }
        val chatIdx = chats.associate { s -> s.id to node(GraphNode("c:${s.id}", GraphKind.CHAT, s.displayTitle, s.id,
            s.lastActive ?: s.startedAt ?: 0.0)) }

        if (o.projects) projects.filter { it.id != NO_PROJECT_ID }.forEach { p ->
            val members = p.sessionIds.mapNotNull { chatIdx[it] }
            if (members.isEmpty()) return@forEach
            val pi = node(GraphNode("p:${p.id}", GraphKind.PROJECT, p.label, p.id))
            members.forEach { edges += GraphEdge(pi, it) }
        }
        if (o.topics) {
            val words = chats.associate { it.id to topicWords(it.displayTitle) }
            val counts = HashMap<String, Int>()
            words.values.forEach { ws -> ws.forEach { counts[it] = (counts[it] ?: 0) + 1 } }
            // A topic links two or more chats; the most shared ones first, capped so the map stays readable.
            val topics = counts.filter { it.value >= 2 }.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(MAX_TOPICS).map { it.key }.toSet()
            chats.forEach { s ->
                words.getValue(s.id).filter { it in topics }.forEach { w ->
                    edges += GraphEdge(node(GraphNode("t:$w", GraphKind.TOPIC, w, w)), chatIdx.getValue(s.id))
                }
            }
        }
        if (o.models) chats.forEach { s ->
            val m = s.model?.takeIf { it.isNotBlank() } ?: return@forEach
            edges += GraphEdge(node(GraphNode("m:$m", GraphKind.MODEL, displayModelName(m), m)), chatIdx.getValue(s.id))
        }
        return ChatGraph(nodes, edges.distinct())
    }

    /** Lower-case words of a title worth linking on: no stop words, numbers or tiny words. */
    fun topicWords(title: String): Set<String> =
        Regex("[\\p{L}][\\p{L}\\p{N}+#.-]*").findAll(title.lowercase()).map { it.value.trim('.', '-') }
            .filter { it.length >= 3 && it !in STOP && it.any(Char::isLetter) }.toSet()

    private const val MAX_TOPICS = 60
    private const val NO_PROJECT_ID = "__no_project__"

    private val STOP = setOf(
        // English
        "the", "and", "for", "with", "from", "into", "onto", "this", "that", "these", "those", "what", "when", "where", "which",
        "who", "why", "how", "are", "was", "were", "you", "your", "our", "can", "could", "should", "would", "will", "not",
        "but", "all", "any", "new", "use", "using", "make", "get", "set", "add", "fix", "help", "about", "via", "out", "its",
        "has", "have", "had", "need", "needs", "want", "does", "did", "done", "just", "also", "more", "some", "one", "two",
        "untitled", "session", "chat", "please", "check", "work", "working", "issue", "question", "thing", "things", "then",
        // Indonesian
        "dan", "yang", "untuk", "dengan", "dari", "ini", "itu", "ada", "bisa", "tidak", "gak", "nggak", "apa", "kenapa",
        "gimana", "bagaimana", "saya", "aku", "kamu", "buat", "jadi", "sudah", "udah", "belum", "lagi", "juga", "tolong",
        "cek", "coba", "mau", "harus", "kalau", "kalo", "atau", "pada", "akan", "masih", "sama", "biar", "aja", "dong",
    )
}

/**
 * Force layout like Obsidian's graph: every dot pushes the others away, links pull like springs,
 * a weak pull keeps everything near the centre, and velocity decays so the map settles.
 * Positions are in layout units around (0, 0).
 */
class GraphSim(val graph: ChatGraph, seed: Int = 7) {
    val n = graph.nodes.size
    val x = FloatArray(n); val y = FloatArray(n)
    private val vx = FloatArray(n); private val vy = FloatArray(n)
    /** Cooling: 1 at the start, falls toward [MIN_ALPHA]; dragging a dot warms it again. */
    var alpha = 1f
        private set
    /** A dot held by a finger stays where it is put. */
    var pinned = -1

    init {
        val r = Random(seed)
        // Start on a small disc: the dots burst out from the centre as the forces kick in.
        for (i in 0 until n) {
            val a = r.nextDouble(0.0, Math.PI * 2); val d = 4 + r.nextDouble() * 18
            x[i] = (Math.cos(a) * d).toFloat(); y[i] = (Math.sin(a) * d).toFloat()
        }
    }

    val settled get() = alpha <= MIN_ALPHA

    fun reheat(to: Float = 0.5f) { alpha = max(alpha, to) }

    fun step() {
        if (n == 0) return
        val fx = FloatArray(n); val fy = FloatArray(n)
        // Repulsion (every pair; the graph is capped at a few hundred dots).
        for (i in 0 until n) for (j in i + 1 until n) {
            var dx = x[i] - x[j]; var dy = y[i] - y[j]
            var d2 = dx * dx + dy * dy
            if (d2 < 0.01f) { dx = 0.1f * (i - j); dy = 0.1f; d2 = dx * dx + dy * dy }
            if (d2 > MAX_REPULSE2) continue
            val f = REPULSE / d2
            val d = sqrt(d2)
            fx[i] += dx / d * f; fy[i] += dy / d * f
            fx[j] -= dx / d * f; fy[j] -= dy / d * f
        }
        // Springs.
        val deg = graph.degree
        for (e in graph.edges) {
            val dx = x[e.b] - x[e.a]; val dy = y[e.b] - y[e.a]
            val d = max(sqrt(dx * dx + dy * dy), 0.01f)
            val f = (d - LINK_LENGTH) * SPRING
            // Hubs move less, leaves more (the d3 bias), so clusters form around projects and topics.
            val bias = deg[e.a].toFloat() / (deg[e.a] + deg[e.b]).coerceAtLeast(1)
            fx[e.a] += dx / d * f * (1 - bias); fy[e.a] += dy / d * f * (1 - bias)
            fx[e.b] -= dx / d * f * bias; fy[e.b] -= dy / d * f * bias
        }
        for (i in 0 until n) {
            fx[i] -= x[i] * GRAVITY; fy[i] -= y[i] * GRAVITY
            if (i == pinned) { vx[i] = 0f; vy[i] = 0f; continue }
            vx[i] = (vx[i] + fx[i] * alpha) * DAMPING; vy[i] = (vy[i] + fy[i] * alpha) * DAMPING
            val sp = sqrt(vx[i] * vx[i] + vy[i] * vy[i])
            if (sp > MAX_SPEED) { vx[i] *= MAX_SPEED / sp; vy[i] *= MAX_SPEED / sp }
            x[i] += vx[i]; y[i] += vy[i]
        }
        alpha = max(MIN_ALPHA, alpha * COOLING)
    }

    fun move(i: Int, nx: Float, ny: Float) { x[i] = nx; y[i] = ny; vx[i] = 0f; vy[i] = 0f }

    /** The dot nearest (px, py) within [radius], or -1. */
    fun hit(px: Float, py: Float, radius: Float): Int {
        var best = -1; var bestD = radius * radius
        for (i in 0 until n) {
            val dx = x[i] - px; val dy = y[i] - py; val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    fun bounds(): FloatArray {
        if (n == 0) return floatArrayOf(-1f, -1f, 1f, 1f)
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in 0 until n) { l = min(l, x[i]); t = min(t, y[i]); r = max(r, x[i]); b = max(b, y[i]) }
        return floatArrayOf(l, t, r, b)
    }

    companion object {
        const val REPULSE = 900f
        const val MAX_REPULSE2 = 400f * 400f
        const val LINK_LENGTH = 46f
        const val SPRING = 0.06f
        const val GRAVITY = 0.012f
        const val DAMPING = 0.6f
        const val COOLING = 0.985f
        const val MIN_ALPHA = 0.02f
        const val MAX_SPEED = 30f
    }
}
