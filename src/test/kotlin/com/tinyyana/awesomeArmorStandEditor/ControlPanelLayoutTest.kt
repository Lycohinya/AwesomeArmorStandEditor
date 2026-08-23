package com.tinyyana.awesomeArmorStandEditor

import com.tinyyana.awesomeArmorStandEditor.menu.ControlPanel
import com.tinyyana.awesomeArmorStandEditor.menu.EquipmentMenu
import com.tinyyana.awesomeArmorStandEditor.menu.PageWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The control panel is hand-laid-out (it is a tool panel, not a list), so these are the rules a
 * reader cannot check by eye across 25 constants.
 */
class ControlPanelLayoutTest {

    @Test
    fun `nothing sits on the page-indicator slot`() {
        // base + 4 of a six-row window. It is the inert page number on every paged screen in the
        // game, including this plugin's own preset gallery; a player who has learned "that slot
        // does nothing" must not find a button there, least of all a destructive one.
        val pageIndicator = PageWindow.resolve((0 until 73).toList(), 1, header = true).pageSlot
        assertEquals(49, pageIndicator)
        assertFalse(pageIndicator in ControlPanel.PANEL_SLOTS, "slot 49 is the page indicator everywhere else")
        assertFalse(ControlPanel.SLOT_DELETE == pageIndicator)
        assertFalse(ControlPanel.SLOT_EXPORT == pageIndicator)

        val confirmIndicator = 4 + (ControlPanel.CONFIRM_ROWS - 1) * ControlPanel.COLUMNS
        assertEquals(22, confirmIndicator)
        assertFalse(confirmIndicator in ControlPanel.CONFIRM_SLOTS)
    }

    @Test
    fun `no slot is used twice and nothing overflows the window`() {
        assertEquals(ControlPanel.PANEL_SLOTS.size, ControlPanel.PANEL_SLOTS.distinct().size)
        assertTrue(ControlPanel.PANEL_SLOTS.all { it in 0 until ControlPanel.PANEL_ROWS * ControlPanel.COLUMNS })
        assertEquals(ControlPanel.CONFIRM_SLOTS.size, ControlPanel.CONFIRM_SLOTS.distinct().size)
        assertTrue(ControlPanel.CONFIRM_SLOTS.all { it in 0 until ControlPanel.CONFIRM_ROWS * ControlPanel.COLUMNS })
    }

    @Test
    fun `the footer row holds navigation only`() {
        val base = (ControlPanel.PANEL_ROWS - 1) * ControlPanel.COLUMNS
        val inFooter = ControlPanel.PANEL_SLOTS.filter { it >= base }
        assertEquals(listOf(base, base + 7, base + 8), inFooter.sorted(), "only status at 0, and ? and ✕ belong in the footer row")
    }

    @Test
    fun `groups are contiguous rows starting at column zero`() {
        // One row is one group: every group's cells sit in a single row, packed from column 0.
        for (group in listOf(
            listOf(
                ControlPanel.SLOT_ADD_STAND, ControlPanel.SLOT_ADD_ITEM, ControlPanel.SLOT_ADD_BLOCK,
                ControlPanel.SLOT_ADD_TEXT, ControlPanel.SLOT_EQUIP, ControlPanel.SLOT_SELECT_PREV,
                ControlPanel.SLOT_SELECT_NEXT, ControlPanel.SLOT_UNDO,
            ),
            listOf(
                ControlPanel.SLOT_MODE_MOVE, ControlPanel.SLOT_MODE_POSE, ControlPanel.SLOT_MODE_TRANSLATE,
                ControlPanel.SLOT_MODE_ROTATE, ControlPanel.SLOT_MODE_SCALE, ControlPanel.SLOT_STEP_DOWN,
                ControlPanel.SLOT_STEP_UP, ControlPanel.SLOT_NUDGE_DOWN, ControlPanel.SLOT_NUDGE_UP,
            ),
            ControlPanel.PART_SLOTS.keys.sorted() + listOf(ControlPanel.SLOT_AXIS_X, ControlPanel.SLOT_AXIS_Y, ControlPanel.SLOT_AXIS_Z),
            ControlPanel.FLAG_SLOTS.keys.sorted(),
            listOf(ControlPanel.SLOT_INFO, ControlPanel.SLOT_PRESETS, ControlPanel.SLOT_SAVE, ControlPanel.SLOT_EXPORT, ControlPanel.SLOT_DELETE),
        )) {
            val rowIndex = group.first() / ControlPanel.COLUMNS
            assertTrue(group.all { it / ControlPanel.COLUMNS == rowIndex }, "$group spans more than one row")
            assertEquals(rowIndex * ControlPanel.COLUMNS, group.first(), "$group does not start at column 0")
            assertEquals((group.first()..group.last()).toList(), group, "$group has a gap in it")
        }
    }

    @Test
    fun `the equipment screen puts back on base+0 and its slots at column zero`() {
        val base = (EquipmentMenu.ROWS - 1) * ControlPanel.COLUMNS
        assertEquals(base, EquipmentMenu.SLOT_BACK, "back is base+0, never the page-indicator slot")
        assertFalse(EquipmentMenu.SLOT_BACK == base + 4)
        assertEquals(0, EquipmentMenu.SLOT_INFO)
        val slots = EquipmentMenu.SLOTS.keys.toList()
        assertEquals(ControlPanel.COLUMNS, slots.first(), "the equipment row starts at column 0 of row 1")
        assertEquals((slots.first()..slots.last()).toList(), slots, "no gaps inside the row")
        assertTrue(slots.none { it >= base })
    }

    @Test
    fun `confirm keeps cancel and delete apart, and delete away from save`() {
        assertTrue(ControlPanel.CONFIRM_DELETE - ControlPanel.CONFIRM_CANCEL >= 4)
        assertTrue(ControlPanel.CONFIRM_CANCEL < ControlPanel.CONFIRM_DELETE, "cancel left, destructive right")
        assertTrue(ControlPanel.CONFIRM_SUMMARY < ControlPanel.COLUMNS, "the summary is the first content slot")
        // Delete is last in its row, so the cursor never lands on it while reaching for close.
        assertTrue(ControlPanel.SLOT_DELETE > ControlPanel.SLOT_SAVE)
        assertTrue(ControlPanel.SLOT_DELETE / ControlPanel.COLUMNS != ControlPanel.SLOT_CLOSE / ControlPanel.COLUMNS)
    }
}
