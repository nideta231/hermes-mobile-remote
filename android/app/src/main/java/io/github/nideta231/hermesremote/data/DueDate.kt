package io.github.nideta231.hermesremote.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * A card's due date as the bridge stores it: "YYYY-MM-DD" (all day) or "YYYY-MM-DD HH:MM".
 * Pure, so the picker and the card badge are unit-tested without Compose.
 */
data class Due(val date: LocalDate, val time: LocalTime? = null) {
    /** What the bridge stores. */
    fun wire(): String = date.toString() + (time?.let { " %02d:%02d".format(it.hour, it.minute) } ?: "")

    /** The moment it is late: the time, or the end of the day for an all-day date. */
    private fun deadline(): LocalDateTime = time?.let { date.atTime(it) } ?: date.plusDays(1).atStartOfDay()

    fun overdue(now: LocalDateTime): Boolean = now >= deadline()

    /** Short, human label relative to [today]: "Today 17:00", "Tomorrow", "Mon 3 Nov", "12 Jan 2027". */
    fun label(today: LocalDate, locale: Locale = Locale.getDefault()): String {
        val day = when (date) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            today.minusDays(1) -> "Yesterday"
            else -> {
                val month = date.month.getDisplayName(TextStyle.SHORT, locale)
                if (date.year != today.year) "${date.dayOfMonth} $month ${date.year}"
                else if (date > today && date < today.plusDays(7)) "${date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)} ${date.dayOfMonth} $month"
                else "${date.dayOfMonth} $month"
            }
        }
        return day + (time?.let { " " + it.format(DateTimeFormatter.ofPattern("HH:mm")) } ?: "")
    }

    /** UTC midnight millis of [date], what Material's DatePicker uses. */
    fun pickerMillis(): Long = date.toEpochDay() * DAY_MS

    companion object {
        private const val DAY_MS = 86_400_000L
        private val WIRE = Regex("""(\d{4}-\d{2}-\d{2})(?:[ T](\d{2}):(\d{2}))?""")

        /** null when [s] is empty or not a due date the bridge would accept. */
        fun parse(s: String?): Due? {
            val m = WIRE.matchEntire(s?.trim().orEmpty()) ?: return null
            return try {
                val d = LocalDate.parse(m.groupValues[1])
                val t = if (m.groupValues[2].isNotEmpty()) LocalTime.of(m.groupValues[2].toInt(), m.groupValues[3].toInt()) else null
                Due(d, t)
            } catch (_: Exception) { null }
        }

        /** The date Material's DatePicker selected (UTC midnight millis). */
        fun fromPickerMillis(ms: Long): LocalDate = LocalDate.ofEpochDay(Math.floorDiv(ms, DAY_MS))

        /** One-tap choices in the picker: label to date, relative to [today]. "Next week" = next Monday. */
        fun quick(today: LocalDate): List<Pair<String, LocalDate>> = listOf(
            "Today" to today,
            "Tomorrow" to today.plusDays(1),
            "Next week" to today.plusDays((8 - today.dayOfWeek.value).toLong()),
        )
    }
}
