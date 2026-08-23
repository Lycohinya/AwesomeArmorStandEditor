package com.tinyyana.awesomeArmorStandEditor.edit

import com.tinyyana.awesomeArmorStandEditor.AwesomeArmorStandEditorPlugin
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.inventory.EquipmentSlot

/**
 * In-world editor tool. Controls (all cancel the vanilla action):
 *  - left click        : −step        | right click       : +step
 *  - scroll            : cycle step    | sneak + scroll     : cycle axis
 *  - sneak + left      : cycle mode    | sneak + right      : cycle part (armor stand)
 *  - right click entity : select that element
 */
class EditToolListener(private val plugin: AwesomeArmorStandEditorPlugin) : Listener {

    private val keys get() = plugin.keys
    private val controller get() = plugin.controller

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (!ToolItem.isTool(keys, event.item)) return
        val player = event.player
        if (plugin.sessions.get(player.uniqueId) == null) return
        event.isCancelled = true
        val sneak = player.isSneaking
        when (event.action) {
            Action.LEFT_CLICK_AIR, Action.LEFT_CLICK_BLOCK ->
                if (sneak) controller.cycleMode(player, 1) else controller.adjust(player, -1)
            Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK ->
                if (sneak) controller.cyclePart(player, 1) else controller.adjust(player, 1)
            else -> {}
        }
    }

    @EventHandler
    fun onSelect(event: PlayerInteractAtEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        if (!ToolItem.isTool(keys, player.inventory.itemInMainHand)) return
        if (plugin.sessions.get(player.uniqueId) == null) return
        event.isCancelled = true
        if (plugin.registry.isOurs(event.rightClicked)) controller.select(player, event.rightClicked)
    }

    @EventHandler
    fun onScroll(event: PlayerItemHeldEvent) {
        val player = event.player
        if (!ToolItem.isTool(keys, player.inventory.getItem(event.previousSlot))) return
        if (plugin.sessions.get(player.uniqueId) == null) return
        event.isCancelled = true
        val dir = scrollDirection(event.previousSlot, event.newSlot)
        if (dir == 0) return
        if (player.isSneaking) controller.cycleAxis(player, dir) else controller.cycleStep(player, dir)
    }

    /** +1 for scroll-up (next hotbar slot), -1 for scroll-down, handling 8<->0 wrap. */
    private fun scrollDirection(prev: Int, next: Int): Int {
        val diff = next - prev
        return when {
            diff == 8 -> -1
            diff == -8 -> 1
            else -> Integer.signum(diff)
        }
    }

    /**
     * Q (drop) while holding the editor tool = undo last adjust. The item never leaves the
     * inventory. This reuses a key players already know (Q = drop) as an undo shortcut,
     * discovered via the tool lore and guide book.
     */
    @EventHandler
    fun onDrop(event: PlayerDropItemEvent) {
        if (!ToolItem.isTool(keys, event.itemDrop.itemStack)) return
        val player = event.player
        if (plugin.sessions.get(player.uniqueId) == null) return
        event.isCancelled = true
        controller.undo(player)
    }
}
