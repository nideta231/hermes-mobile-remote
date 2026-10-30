package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.Due
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

class DueDateTest {
    private val today = LocalDate.of(2026, 10, 30)  // a Friday

    @Test fun parsesBothShapesAndRefusesJunk() {
        assertEquals(Due(today), Due.parse("2026-10-30"))
        assertEquals(Due(today, LocalTime.of(9, 5)), Due.parse("2026-10-30 09:05"))
        assertEquals(Due(today, LocalTime.of(9, 5)), Due.parse("2026-10-30T09:05"))
        assertNull(Due.parse(""))
        assertNull(Due.parse("2026-13-40"))
        assertNull(Due.parse("2026-10-30 25:00"))
        assertEquals("2026-10-30 09:05", Due(today, LocalTime.of(9, 5)).wire())
        assertEquals("2026-10-30", Due(today).wire())
    }

    @Test fun labelsAreHuman() {
        val en = Locale.ENGLISH
        assertEquals("Today 17:00", Due(today, LocalTime.of(17, 0)).label(today, en))
        assertEquals("Tomorrow", Due(today.plusDays(1)).label(today, en))
        assertEquals("Yesterday", Due(today.minusDays(1)).label(today, en))
        assertEquals("Tue 3 Nov", Due(LocalDate.of(2026, 11, 3)).label(today, en))
        assertEquals("20 Nov", Due(LocalDate.of(2026, 11, 20)).label(today, en))
        assertEquals("12 Jan 2027", Due(LocalDate.of(2027, 1, 12)).label(today, en))
    }

    @Test fun allDayIsLateOnlyAfterTheDay() {
        assertFalse(Due(today).overdue(LocalDateTime.of(2026, 10, 30, 23, 59)))
        assertTrue(Due(today).overdue(LocalDateTime.of(2026, 10, 31, 0, 0)))
        assertTrue(Due(today, LocalTime.of(9, 0)).overdue(LocalDateTime.of(2026, 10, 30, 9, 0)))
    }

    @Test fun quickChoicesAndPickerMillisRoundTrip() {
        assertEquals(listOf("Today" to today, "Tomorrow" to today.plusDays(1), "Next week" to LocalDate.of(2026, 11, 2)), Due.quick(today))
        assertEquals(LocalDate.of(2026, 11, 2), Due.quick(LocalDate.of(2026, 10, 26)).last().second)  // a Monday: the next one
        assertEquals(today, Due.fromPickerMillis(Due(today).pickerMillis()))
    }
}
