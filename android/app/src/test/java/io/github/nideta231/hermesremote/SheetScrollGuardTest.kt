package io.github.nideta231.hermesremote

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import io.github.nideta231.hermesremote.ui.SheetScrollGuard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SheetScrollGuardTest {
    @Test fun scrollingBackUpNeverReachesTheSheet() = runBlocking<Unit> {
        var at = 500
        val g = SheetScrollGuard { at }
        g.onPreScroll(Offset(0f, 40f), NestedScrollSource.UserInput)  // gesture starts scrolled down
        at = 0
        // the card hit the top: the rest of the swipe and the fling stay with the card
        assertEquals(Offset(0f, 120f), g.onPostScroll(Offset.Zero, Offset(0f, 120f), NestedScrollSource.UserInput))
        assertEquals(Velocity(0f, 900f), g.onPostFling(Velocity.Zero, Velocity(0f, 900f)))
    }

    @Test fun swipingDownFromTheTopStillClosesTheSheet() = runBlocking<Unit> {
        val g = SheetScrollGuard { 0 }
        g.onPreScroll(Offset(0f, 40f), NestedScrollSource.UserInput)
        assertEquals(Offset.Zero, g.onPostScroll(Offset.Zero, Offset(0f, 120f), NestedScrollSource.UserInput))
        assertEquals(Velocity.Zero, g.onPostFling(Velocity.Zero, Velocity(0f, 900f)))
    }

    @Test fun eachGestureIsJudgedOnItsOwn() = runBlocking<Unit> {
        var at = 300
        val g = SheetScrollGuard { at }
        g.onPreScroll(Offset(0f, 10f), NestedScrollSource.UserInput)
        g.onPostFling(Velocity.Zero, Velocity.Zero)  // gesture over
        at = 0
        g.onPreScroll(Offset(0f, 10f), NestedScrollSource.UserInput)  // new one from the top
        assertEquals(Offset.Zero, g.onPostScroll(Offset.Zero, Offset(0f, 50f), NestedScrollSource.UserInput))
    }
}
