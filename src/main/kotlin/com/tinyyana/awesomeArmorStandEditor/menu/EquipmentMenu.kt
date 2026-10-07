package com.tinyyana.awesomeArmorStandEditor.menu

import com.tinyyana.awesomeArmorStandEditor.AwesomeArmorStandEditorPlugin
import com.tinyyana.awesomeArmorStandEditor.model.ItemRef
import com.tinyyana.awesomeArmorStandEditor.store.ItemResolver
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

/**
 * Equip the selected armor stand by clicking a slot with an item on your cursor — no dragging items
 * out of your inventory, so nothing can ever be lost or duplicated. The click is always cancelled;
 * we only *copy* whatever is on the cursor into the model and re-apply live. Empty cursor = clear.
 */
class EquipmentMenu(private val plugin: AwesomeArmorStandEditorPlugin) : Listener {

    private val texts get() = plugin.texts
    private val controller get() = plugin.controller

    private class Holder : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    /** Opens the menu for the player, or tells them to select an armor stand first. */
    fun open(player: Player) {
        if (controller.equipmentSnapshot(player) == null) {
            texts.send(player, "equip-menu.only-stand")
            return
        }
        val holder = Holder()
        val inv = Bukkit.createInventory(holder, ROWS * 9, texts.legacy("equip-menu.title"))
        holder.inv = inv
        populate(player, inv)
        player.openInventory(inv)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder
        if (holder !is Holder) return
        val player = event.whoClicked as? Player ?: return
        val menu = event.view.topInventory

        if (event.clickedInventory === menu) {
            // Clicks inside the menu are ours — we never let a real item move here, only copy the cursor.
            event.isCancelled = true
            val slot = event.rawSlot
            if (slot == SLOT_BACK) { plugin.panel.open(player); return }
            val key = SLOTS[slot] ?: return
            val toSet = event.cursor?.takeIf { !it.type.isAir }?.clone()
            controller.setEquipItem(player, key, toSet)  // empty cursor clears the slot
            populate(player, menu)
            return
        }

        // In the player's own inventory: allow normal pickup so they can put an item on the cursor,
        // but block anything that would shove items into the menu (shift-move / double-click collect).
        when (event.action) {
            InventoryAction.MOVE_TO_OTHER_INVENTORY, InventoryAction.COLLECT_TO_CURSOR -> event.isCancelled = true
            else -> {}
        }
    }

    /** Cancel only drags that touch the menu; drags within the player's own inventory are fine. */
    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.inventory.holder !is Holder) return
        val menuSize = event.view.topInventory.size
        if (event.rawSlots.any { it < menuSize }) event.isCancelled = true
    }

    private fun populate(player: Player, inv: Inventory) {
        inv.clear()
        val snapshot = controller.equipmentSnapshot(player) ?: return
        inv.setItem(SLOT_INFO, icon(Material.ARMOR_STAND, texts.legacy("equip-menu.info"), listOf(texts.legacy("equip-menu.info-lore-1"), texts.legacy("equip-menu.info-lore-2"))))
        for ((slot, key) in SLOTS) {
            inv.setItem(slot, slotItem(key, snapshot[key]))
        }
        inv.setItem(SLOT_BACK, icon(Material.ARROW, texts.legacy("equip-menu.back")))
    }

    /** Show the equipped item (with a "click to change/remove" hint) or a labelled empty placeholder. */
    @Suppress("DEPRECATION")
    private fun slotItem(key: String, ref: ItemRef?): ItemStack {
        val label = texts.legacy("equip-menu.$key")
        val decoded = ref?.let { ItemResolver.resolve(it) }
        if (decoded != null && !decoded.type.isAir) {
            val item = decoded.clone()
            val meta = item.itemMeta
            if (meta != null) {
                val lore = (meta.lore ?: mutableListOf()).toMutableList()
                lore.add(0, label)
                lore.add(texts.legacy("equip-menu.filled-hint"))
                meta.lore = lore
                item.itemMeta = meta
            }
            return item
        }
        val pane = ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE)
        val meta = pane.itemMeta ?: return pane
        meta.setDisplayName(label)
        meta.lore = listOf(texts.legacy("equip-menu.empty-hint"))
        pane.itemMeta = meta
        return pane
    }

    @Suppress("DEPRECATION")
    private fun icon(material: Material, nameLegacy: String, lore: List<String> = emptyList()): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.setDisplayName(nameLegacy)
        if (lore.isNotEmpty()) meta.lore = lore
        item.itemMeta = meta
        return item
    }

    internal companion object {
        const val ROWS = 3
        private const val COLUMNS = 9

        /** Row 0: what this screen is. First content slot, left-aligned — not floating in the middle. */
        const val SLOT_INFO = 0

        /** Row 1: head, chest, legs, feet, mainhand, offhand, contiguous from column 0. */
        val SLOTS = linkedMapOf(
            9 to "head", 10 to "chest", 11 to "legs",
            12 to "feet", 13 to "mainhand", 14 to "offhand",
        )

        /**
         * `base + 0`. It used to be `base + 4` (22) — the slot that is an inert page-number
         * indicator on every paged screen in the game, so "click there, nothing happens" and
         * "click there, the screen changes" were the same slot depending on which menu you were in.
         */
        const val SLOT_BACK = (ROWS - 1) * COLUMNS
    }
}
