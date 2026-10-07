package com.tinyyana.awesomeArmorStandEditor.edit

import com.tinyyana.awesomeArmorStandEditor.AwesomeArmorStandEditorPlugin
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import org.bukkit.attribute.Attribute
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.inventory.EquipmentSlot

/**
 * In-world editor tool. Controls (all cancel the vanilla action):
 *  - left click        : −step        | right click       : +step
 *  - scroll            : cycle step    | sneak + scroll     : cycle axis
 *  - sneak + left air  : cycle mode    | sneak + right      : cycle part (armor stand)
 *  - right click entity : select that element
 *  - sneak + left on one of your armor stands : recall preview for that copy
 *
 * Only collidable (non-marker) armor stands can be hit or ray-traced; displays and marker stands
 * have no hitbox, so none of the entity gestures reach them.
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
                if (sneak) {
                    // Hitting an entity can also fire LEFT_CLICK_AIR, before or after the damage
                    // event. If the swing is on one of our stands, the damage handler owns the
                    // gesture (recall preview), so don't also cycle the mode.
                    if (!aimingAtOurStand(player)) controller.cycleMode(player, 1)
                } else {
                    controller.adjust(player, -1)
                }
            Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK ->
                if (sneak) controller.cyclePart(player, 1) else controller.adjust(player, 1)
            else -> {}
        }
    }

    private fun aimingAtOurStand(player: Player): Boolean {
        val eye = player.eyeLocation
        // Match the player's real attack reach (3 in survival, more in creative): a longer ray would
        // swallow the mode switch for stands the swing can't actually hit, so neither would fire.
        val reach = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE)?.value ?: DEFAULT_REACH
        val hit = player.world.rayTraceEntities(eye, eye.direction, reach, 0.0) {
            it != player && it is ArmorStand && !it.isMarker && plugin.registry.isOurs(it)
        }
        return hit?.hitEntity != null
    }

    @EventHandler
    fun onSelect(event: PlayerInteractAtEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        if (!ToolItem.isTool(keys, player.inventory.itemInMainHand)) return
        val clicked = event.rightClicked
        if (plugin.sessions.get(player.uniqueId) == null) {
            if (!plugin.registry.isOurs(clicked)) return
            event.isCancelled = true
            if (plugin.recall.hints.tryAcquire(player.uniqueId, System.currentTimeMillis())) {
                plugin.texts.sendWithButtons(
                    player, "hint.tool-no-session",
                    listOf(plugin.texts.button("hint.button-edit", "/aase edit")),
                )
            }
            return
        }
        event.isCancelled = true
        if (plugin.registry.isOurs(clicked)) controller.select(player, clicked)
    }

    /**
     * Punching one of our elements. The damage itself is cancelled by EntityProtectionListener;
     * this only reacts. Not reading isCancelled: the two handlers' order is not defined.
     */
    @EventHandler
    fun onHit(event: EntityDamageByEntityEvent) {
        val player = event.damager as? Player ?: return
        val entity = event.entity
        if (!plugin.registry.isOurs(entity) || plugin.registry.isEmitterMarker(entity)) return
        val tag = plugin.registry.read(entity) ?: return
        event.isCancelled = true
        val own = tag.owner == player.uniqueId
        val tool = ToolItem.isTool(keys, player.inventory.itemInMainHand)
        if (tool && player.isSneaking) {
            if (own) {
                plugin.recall.previewEntity(player, entity, offerDeleteOne = true)
            } else {
                plugin.texts.actionbar(player, "hint.punch-other", "owner" to plugin.texts.escape(plugin.recall.ownerName(tag.owner)))
            }
            return
        }
        if (tool) return  // plain left click with the tool is the "−step" adjust, handled above
        if (!plugin.recall.hints.tryAcquire(player.uniqueId, System.currentTimeMillis())) return
        if (own) plugin.texts.actionbar(player, "hint.punch-own")
        else plugin.texts.actionbar(player, "hint.punch-other", "owner" to plugin.texts.escape(plugin.recall.ownerName(tag.owner)))
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

    private companion object {
        /** Vanilla survival entity interaction range, used if the attribute is missing. */
        const val DEFAULT_REACH = 3.0
    }
}
