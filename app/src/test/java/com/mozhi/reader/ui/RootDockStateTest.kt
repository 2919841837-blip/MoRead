package com.mozhi.reader.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootDockStateTest {
    private val step = RootDockState.COLLAPSE_TRAVEL_PX / 3f

    @Test fun scrollingTowardTheEndCollapsesOnlyAfterEnoughTravel() {
        val dock = RootDockState()
        dock.onContentScrolled(-step)
        dock.onContentScrolled(-step)
        assertFalse(dock.collapsed)
        dock.onContentScrolled(-step)
        assertTrue(dock.collapsed)
    }

    @Test fun reversingDirectionRestartsTheTravelAndExpands() {
        val dock = RootDockState()
        repeat(3) { dock.onContentScrolled(-step) }
        assertTrue(dock.collapsed)
        // A small fling tail in the other direction must not flip the dock back.
        dock.onContentScrolled(step)
        assertTrue(dock.collapsed)
        repeat(2) { dock.onContentScrolled(step) }
        assertFalse(dock.collapsed)
    }

    @Test fun openSearchKeepsTheDockCollapsedWhileScrolling() {
        val dock = RootDockState()
        dock.openSearch()
        assertTrue(dock.collapsed && dock.searchOpen)
        repeat(10) { dock.onContentScrolled(step) }
        assertTrue(dock.collapsed)
        dock.closeSearch()
        assertFalse(dock.collapsed || dock.searchOpen)
    }

    @Test fun dockTabsShrinkSoTheSearchButtonNeverOverlaps() {
        val reserved = RootDockHeight + RootDockGap
        // 411dp 手机扣掉左右 18dp 页边后约 375dp：四个页签加搜索钮必须放得下。
        val wide = dockTabWidth(375.dp, reserved, 4)
        assertTrue(wide * 4 + 10.dp + reserved <= 375.dp)
        val narrow = dockTabWidth(324.dp, reserved, 4)
        assertTrue(narrow * 4 + 10.dp + reserved <= 324.dp)
        assertEquals(68.dp, dockTabWidth(600.dp, reserved, 4))
    }

    @Test fun resetExpandsWithoutTouchingSearch() {
        val dock = RootDockState()
        repeat(3) { dock.onContentScrolled(-step) }
        dock.reset()
        assertFalse(dock.collapsed)
    }
}
