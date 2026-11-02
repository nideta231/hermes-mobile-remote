package io.github.nideta231.hermesremote.ui

import io.github.nideta231.hermesremote.data.SESSION_FIRST_PAGE
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** The accent of the chosen theme. Kept under its old name so every call site follows the picker. */
val Gold: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary

val Ok = Color(0xFF5BC27A)
val Bad = Color(0xFFE5675C)
val Warn = Color(0xFFE0A53A)

/** Brightness of the app. Every [Accent] works with every mode. */
enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
    MIDNIGHT("Midnight"),
}

/** Accent colour. [dark] is used on dark/midnight backgrounds, [light] (deeper, for contrast) on light. */
// Names of the first five (GOLD, OCEAN, FOREST, VIOLET, ROSE) are stored in prefs: never rename them.
// Order is the picker order, grouped by hue family.
enum class Accent(val label: String, val dark: Color, val light: Color) {
    // yellows / oranges
    GOLD("Gold", Color(0xFFE8B54A), Color(0xFF8A5F00)),
    AMBER("Amber", Color(0xFFFFC145), Color(0xFF9A6700)),
    LEMON("Lemon", Color(0xFFE6DA5A), Color(0xFF7A6E00)),
    ORANGE("Orange", Color(0xFFFF9F4A), Color(0xFFB45309)),
    COPPER("Copper", Color(0xFFD9895B), Color(0xFF9A4A1E)),
    // reds
    CORAL("Coral", Color(0xFFFF8A75), Color(0xFFC2412D)),
    RED("Red", Color(0xFFF26B6B), Color(0xFFB91C1C)),
    CRIMSON("Crimson", Color(0xFFE5576F), Color(0xFF9F1239)),
    MAROON("Maroon", Color(0xFFC77B85), Color(0xFF7F1D2D)),
    // pinks
    ROSE("Rose", Color(0xFFF28FA8), Color(0xFFB0305A)),
    PINK("Pink", Color(0xFFF7A1D0), Color(0xFFBE185D)),
    MAGENTA("Magenta", Color(0xFFE879E0), Color(0xFFA21CAF)),
    // purples
    LAVENDER("Lavender", Color(0xFFC9B6F7), Color(0xFF7056B8)),
    VIOLET("Violet", Color(0xFFB794F6), Color(0xFF6A3FC4)),
    PURPLE("Purple", Color(0xFFA77BEA), Color(0xFF6B21A8)),
    PLUM("Plum", Color(0xFFB98BC0), Color(0xFF6B2D6F)),
    // blues
    SKY("Sky", Color(0xFF7DD3FC), Color(0xFF0369A1)),
    OCEAN("Ocean", Color(0xFF6FA8F5), Color(0xFF1F5FB8)),
    INDIGO("Indigo", Color(0xFF8C9EFF), Color(0xFF3730A3)),
    NAVY("Navy", Color(0xFF7F95C8), Color(0xFF1E3A6E)),
    // cyans / teals
    CYAN("Cyan", Color(0xFF5FDDE8), Color(0xFF0E7490)),
    TEAL("Teal", Color(0xFF4FD1B5), Color(0xFF0F766E)),
    // greens
    MINT("Mint", Color(0xFF8EEBC0), Color(0xFF15803D)),
    FOREST("Forest", Color(0xFF6CCB8A), Color(0xFF1E7A43)),
    LIME("Lime", Color(0xFFB5E05A), Color(0xFF4D7C0F)),
    OLIVE("Olive", Color(0xFFB8BE6A), Color(0xFF5B6113)),
    EMERALD("Emerald", Color(0xFF34D399), Color(0xFF047857)),
    // neutrals
    SLATE("Slate", Color(0xFFA3B1C6), Color(0xFF475569)),
    ;

    fun color(mode: ThemeMode, systemDark: Boolean) = if (mode.isDark(systemDark)) dark else light
}

fun ThemeMode.isDark(systemDark: Boolean) = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK, ThemeMode.MIDNIGHT -> true
}

fun colorScheme(mode: ThemeMode, accent: Accent, systemDark: Boolean, customPrimary: Color? = null, customBackground: Color? = null): ColorScheme {
    val dark = mode.isDark(systemDark)
    val primary = customPrimary ?: accent.color(mode, systemDark)
    val base = baseScheme(mode, dark, primary)
    val withPrimary = if (customPrimary == null) base else base.copy(onPrimary = readableOn(customPrimary))
    return customBackground?.let { schemeOnBackground(withPrimary, it) } ?: withPrimary
}

/** A colour scheme rebuilt around a chosen background: surfaces step away from it, text flips to stay readable. */
fun schemeOnBackground(s: ColorScheme, bg: Color): ColorScheme {
    val lightBg = luminance(bg) > 0.4f
    val ink = if (lightBg) Color(0xFF1C1B19) else Color(0xFFE6E6E9)
    return s.copy(
        background = bg, surface = bg,
        surfaceContainerLow = mix(bg, ink, 0.03f), surfaceContainer = mix(bg, ink, 0.06f),
        surfaceContainerHigh = mix(bg, ink, 0.10f), surfaceContainerHighest = mix(bg, ink, 0.14f),
        surfaceVariant = mix(bg, ink, 0.10f), onSurface = ink, onBackground = ink,
        onSurfaceVariant = mix(bg, ink, 0.68f), outline = mix(bg, ink, 0.22f), outlineVariant = mix(bg, ink, 0.14f),
    )
}

/** Relative luminance (sRGB approximation), 0 = black, 1 = white. */
fun luminance(c: Color): Float = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

fun mix(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t)

/** Text colour that reads on [c]. */
fun readableOn(c: Color) = if (luminance(c) > 0.5f) Color(0xFF15130F) else Color.White

/** Hue (0-360), saturation and value (0-1) of [c]. */
fun hsvOf(c: Color): Triple<Float, Float, Float> {
    val max = maxOf(c.red, c.green, c.blue); val min = minOf(c.red, c.green, c.blue); val d = max - min
    val h = when {
        d == 0f -> 0f
        max == c.red -> 60f * (((c.green - c.blue) / d) % 6f)
        max == c.green -> 60f * ((c.blue - c.red) / d + 2f)
        else -> 60f * ((c.red - c.green) / d + 4f)
    }.let { if (it < 0f) it + 360f else it }
    return Triple(h, if (max == 0f) 0f else d / max, max)
}

/**
 * Backgrounds that go with [primary] for people who'd rather not pick one: a neutral, the accent's own
 * hue gently tinted, its complement, and a warm and a cool option, all dark enough (or light enough
 * on a light theme) for the app's text to stay readable.
 */
fun backgroundSuggestions(primary: Color, dark: Boolean): List<Pair<String, Color>> {
    val h = hsvOf(primary).first
    val v = if (dark) 0.11f else 0.97f
    val s = if (dark) 0.28f else 0.06f
    return listOf(
        "Neutral" to Color.hsv(0f, 0f, v),
        "Accent tint" to Color.hsv(h, s, v),
        "Deep accent" to Color.hsv(h, if (dark) 0.45f else 0.10f, if (dark) 0.09f else 0.94f),
        "Complement" to Color.hsv((h + 180f) % 360f, s, v),
        "Warm" to Color.hsv(30f, s * 0.8f, v),
        "Cool" to Color.hsv(215f, s, v),
    )
}

private fun baseScheme(mode: ThemeMode, dark: Boolean, primary: Color): ColorScheme {
    return when {
        !dark -> lightColorScheme(
            primary = primary,
            onPrimary = Color.White,
            secondary = Color(0xFF3E5F8A),
            background = Color(0xFFF7F6F3),
            surface = Color(0xFFF7F6F3),
            surfaceVariant = Color(0xFFE8E6E1),
            surfaceContainerLow = Color(0xFFF2F0EC),
            surfaceContainer = Color(0xFFEFEDE8),
            surfaceContainerHigh = Color(0xFFE8E6E1),
            surfaceContainerHighest = Color(0xFFE0DDD6),
            onSurface = Color(0xFF1C1B19),
            onSurfaceVariant = Color(0xFF5A5852),
            outline = Color(0xFFC9C5BC),
            error = Color(0xFFC0392B),
        )
        mode == ThemeMode.MIDNIGHT -> darkColorScheme(
            primary = primary,
            onPrimary = Color(0xFF15130F),
            secondary = Color(0xFF9DB4D6),
            background = Color.Black,
            surface = Color.Black,
            surfaceVariant = Color(0xFF17181B),
            surfaceContainerLow = Color(0xFF070708),
            surfaceContainer = Color(0xFF0E0F11),
            surfaceContainerHigh = Color(0xFF17181B),
            surfaceContainerHighest = Color(0xFF202227),
            onSurface = Color(0xFFE6E6E9),
            onSurfaceVariant = Color(0xFFA9ABB3),
            outline = Color(0xFF30333A),
            error = Bad,
        )
        else -> darkColorScheme(
            primary = primary,
            onPrimary = Color(0xFF15130F),
            secondary = Color(0xFF9DB4D6),
            background = Color(0xFF121316),
            surface = Color(0xFF121316),
            surfaceVariant = Color(0xFF22252C),
            surfaceContainerLow = Color(0xFF16181C),
            surfaceContainer = Color(0xFF1A1C21),
            surfaceContainerHigh = Color(0xFF22252C),
            surfaceContainerHighest = Color(0xFF2A2D35),
            onSurface = Color(0xFFE6E6E9),
            onSurfaceVariant = Color(0xFFA9ABB3),
            outline = Color(0xFF3A3E47),
            error = Bad,
        )
    }
}

/** How the chat shows thoughts and tool calls: every one as its own row, or grouped into steps like the desktop app. */
enum class ToolDisplay { CLASSIC, TREE }

/** Wide screens: the chat stretches across the whole width, or sits in a readable column in the middle. */
enum class ChatWidth { CLASSIC, CENTERED }

fun toolDisplayOf(stored: String?): ToolDisplay = ToolDisplay.entries.firstOrNull { it.name == stored } ?: ToolDisplay.CLASSIC

fun chatWidthOf(stored: String?): ChatWidth = ChatWidth.entries.firstOrNull { it.name == stored } ?: ChatWidth.CLASSIC

/** App-wide (not per PC) theme choice, persisted in its own prefs file. */
object ThemePrefs {
    var mode by mutableStateOf(ThemeMode.DARK)
        private set
    var accent by mutableStateOf(Accent.GOLD)
        private set
    private var ctx: Context? = null

    fun init(context: Context) {
        ctx = context.applicationContext
        val p = prefs(context)
        val legacy = p.getString("theme", null)  // v1.8.0 stored one combined value
        mode = ThemeMode.entries.firstOrNull { it.name == p.getString("mode", null) } ?: when (legacy) {
            null -> ThemeMode.DARK  // the app was always dark before the picker
            "LIGHT" -> ThemeMode.LIGHT
            "MIDNIGHT" -> ThemeMode.MIDNIGHT
            else -> ThemeMode.DARK
        }
        accent = Accent.entries.firstOrNull { it.name == p.getString("accent", null) }
            ?: Accent.entries.firstOrNull { it.name == legacy } ?: Accent.GOLD
        collapsedGroups = p.getStringSet("collapsed_groups", null)?.toSet() ?: emptySet()
        sessionsPerGroup = p.getInt("sessions_per_group", SESSION_FIRST_PAGE)
        queueWhileBusy = p.getBoolean("queue_while_busy", false)
        sidebarWidth = clampSidebar(p.getInt("sidebar_width", SIDEBAR_DEFAULT))
        sidebarHidden = p.getBoolean("sidebar_hidden", false)
        compact = p.getBoolean("compact", false)
        toolDisplay = toolDisplayOf(p.getString("tool_display", null))
        chatWidth = chatWidthOf(p.getString("chat_width", null))
        customPrimary = p.takeIf { it.contains("custom_primary") }?.getInt("custom_primary", 0)?.let { Color(it) }
        customBackground = p.takeIf { it.contains("custom_background") }?.getInt("custom_background", 0)?.let { Color(it) }
    }

    /** A primary colour of the user's own (null: the [accent]). */
    var customPrimary by mutableStateOf<Color?>(null)
        private set
    /** A background of the user's own (null: the [mode]'s). */
    var customBackground by mutableStateOf<Color?>(null)
        private set

    fun pickCustomPrimary(c: Color?) {
        customPrimary = c
        ctx?.let { prefs(it).edit().apply { if (c == null) remove("custom_primary") else putInt("custom_primary", c.toArgb()) }.apply() }
    }

    fun pickCustomBackground(c: Color?) {
        customBackground = c
        ctx?.let { prefs(it).edit().apply { if (c == null) remove("custom_background") else putInt("custom_background", c.toArgb()) }.apply() }
    }

    /** Width of the chat list beside the chat on wide screens (dp); drag its edge to change it. */
    var sidebarWidth by mutableStateOf(SIDEBAR_DEFAULT)
        private set
    /** Wide screens: chat list hidden, the chat takes the full width (menu button brings it back). */
    var sidebarHidden by mutableStateOf(false)
        private set
    /** Compact layout: everything about 12% smaller, so more fits on screen. */
    var compact by mutableStateOf(false)
        private set

    /** Thoughts and tool calls: Classic rows or Tree steps. */
    var toolDisplay by mutableStateOf(ToolDisplay.CLASSIC)
        private set
    /** Wide screens: Classic stretches the chat, Centered keeps it in a column. */
    var chatWidth by mutableStateOf(ChatWidth.CLASSIC)
        private set

    fun pickToolDisplay(value: ToolDisplay) {
        toolDisplay = value
        ctx?.let { prefs(it).edit().putString("tool_display", value.name).apply() }
    }

    fun pickChatWidth(value: ChatWidth) {
        chatWidth = value
        ctx?.let { prefs(it).edit().putString("chat_width", value.name).apply() }
    }

    /** While dragging: [save] false only updates the screen; the release saves. */
    fun pickSidebarWidth(dp: Int, save: Boolean = true) {
        sidebarWidth = clampSidebar(dp)
        if (save) ctx?.let { prefs(it).edit().putInt("sidebar_width", sidebarWidth).apply() }
    }

    fun pickSidebarHidden(value: Boolean) {
        sidebarHidden = value
        ctx?.let { prefs(it).edit().putBoolean("sidebar_hidden", value).apply() }
    }

    fun pickCompact(value: Boolean) {
        compact = value
        ctx?.let { prefs(it).edit().putBoolean("compact", value).apply() }
    }

    /** A message typed while the agent works: false steers the running turn, true queues it as the next turn. */
    var queueWhileBusy by mutableStateOf(false)
        private set

    fun pickQueueWhileBusy(value: Boolean) {
        queueWhileBusy = value
        ctx?.let { prefs(it).edit().putBoolean("queue_while_busy", value).apply() }
    }

    /** Chats each drawer group shows before "Show more"; 0 shows all of them. */
    var sessionsPerGroup by mutableStateOf(SESSION_FIRST_PAGE)
        private set

    fun pickSessionsPerGroup(value: Int) {
        sessionsPerGroup = value
        ctx?.let { prefs(it).edit().putInt("sessions_per_group", value).apply() }
    }

    fun pickMode(value: ThemeMode) {
        mode = value
        ctx?.let { prefs(it).edit().putString("mode", value.name).remove("theme").apply() }
    }

    fun pickAccent(value: Accent) {
        accent = value
        ctx?.let { prefs(it).edit().putString("accent", value.name).remove("theme").apply() }
    }

    /** Drawer groups the user collapsed ("GROUPING/key"), kept across app restarts. */
    var collapsedGroups by mutableStateOf<Set<String>>(emptySet())
        private set

    fun toggleCollapsed(key: String) {
        collapsedGroups = if (key in collapsedGroups) collapsedGroups - key else collapsedGroups + key
        ctx?.let { prefs(it).edit().putStringSet("collapsed_groups", HashSet(collapsedGroups)).apply() }
    }

    private fun prefs(c: Context) = c.getSharedPreferences("ui", Context.MODE_PRIVATE)
}

@Composable
fun HermesTheme(content: @Composable () -> Unit) {
    val base = androidx.compose.ui.platform.LocalDensity.current
    // Compact: a smaller density shrinks every dp and sp alike, so the whole UI fits more.
    val density = if (ThemePrefs.compact) androidx.compose.ui.unit.Density(base.density * COMPACT_SCALE, base.fontScale) else base
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides density) {
        MaterialTheme(colorScheme = colorScheme(ThemePrefs.mode, ThemePrefs.accent, isSystemInDarkTheme(), ThemePrefs.customPrimary, ThemePrefs.customBackground), content = content)
    }
}

const val SIDEBAR_DEFAULT = 320
const val SIDEBAR_MIN = 240
const val SIDEBAR_MAX = 480
const val COMPACT_SCALE = 0.88f
fun clampSidebar(dp: Int) = dp.coerceIn(SIDEBAR_MIN, SIDEBAR_MAX)
