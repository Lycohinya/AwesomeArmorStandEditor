package com.tinyyana.awesomeArmorStandEditor.menu

import com.tinyyana.awesomeArmorStandEditor.AwesomeArmorStandEditorPlugin
import com.tinyyana.awesomeArmorStandEditor.model.ArmorStandElement
import com.tinyyana.awesomeArmorStandEditor.model.DisplayKind
import com.tinyyana.awesomeArmorStandEditor.sched.PlayerTasks
import com.tinyyana.awesomeArmorStandEditor.session.EditMode
import com.tinyyana.awesomeArmorStandEditor.edit.Axis
import com.tinyyana.awesomeArmorStandEditor.edit.BodyPart
import com.tinyyana.awesomeArmorStandEditor.model.ItemRef
import com.tinyyana.awesomeArmorStandEditor.store.ItemResolver
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

/**
 * The control panel GUI. Mirrors the in-world tool (mode/part/axis/nudge) and adds flags, equipment
 * hint, save/export/delete. Holder+dispatch pattern; all clicks cancelled. Cross-platform ItemMeta.
 */
class ControlPanel(private val plugin: AwesomeArmorStandEditorPlugin) : Listener {

    private val texts get() = plugin.texts
    private val controller get() = plugin.controller

    private sealed class Holder : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }
    private class PanelHolder : Holder()
    private class DeleteHolder(val localId: Int) : Holder()

    fun open(player: Player) {
        val holder = PanelHolder()
        val inv = Bukkit.createInventory(holder, PANEL_ROWS * COLUMNS, texts.legacy("panel.title"))
        holder.inv = inv
        populate(player, inv)
        player.openInventory(inv)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder
        if (holder !is Holder) return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (event.clickedInventory !== event.inventory) return

        if (holder is DeleteHolder) {
            when (event.rawSlot) {
                CONFIRM_CANCEL, CONFIRM_BACK -> open(player)
                CONFIRM_DELETE -> {
                    controller.deleteElement(player, holder.localId)
                    open(player)
                }
                CONFIRM_CLOSE -> player.closeInventory()
            }
            return
        }

        when (event.rawSlot) {
            // The GUI must enforce the same permissions the command path does — a button that calls
            // the controller directly would otherwise bypass gated actions (e.g. disk-writing export).
            SLOT_ADD_STAND -> guarded(player, "aase.create.armorstand") { controller.addStand(player) }
            SLOT_ADD_ITEM -> guarded(player, "aase.create.display") { controller.addDisplay(player, DisplayKind.ITEM, "", offhandItem(player)) }
            SLOT_ADD_BLOCK -> guarded(player, "aase.create.display") { controller.addDisplay(player, DisplayKind.BLOCK, "minecraft:stone") }
            SLOT_ADD_TEXT -> guarded(player, "aase.create.display") { controller.addDisplay(player, DisplayKind.TEXT, texts.label("display.default-text")) }

            SLOT_MODE_MOVE -> setMode(player, EditMode.MOVE)
            SLOT_MODE_POSE -> setMode(player, EditMode.POSE)
            SLOT_MODE_TRANSLATE -> setMode(player, EditMode.TRANSLATE)
            SLOT_MODE_ROTATE -> setMode(player, EditMode.ROTATE)
            SLOT_MODE_SCALE -> setMode(player, EditMode.SCALE)

            SLOT_AXIS_X -> setAxis(player, Axis.X)
            SLOT_AXIS_Y -> setAxis(player, Axis.Y)
            SLOT_AXIS_Z -> setAxis(player, Axis.Z)

            SLOT_STEP_DOWN -> controller.cycleStep(player, -1)
            SLOT_STEP_UP -> controller.cycleStep(player, 1)
            SLOT_NUDGE_DOWN -> controller.adjust(player, -1)
            SLOT_NUDGE_UP -> controller.adjust(player, 1)

            SLOT_SELECT_PREV -> controller.selectPrev(player)
            SLOT_SELECT_NEXT -> controller.selectNext(player)
            SLOT_UNDO -> controller.undo(player)

            SLOT_EQUIP -> { plugin.equipmentMenu.open(player); return }
            SLOT_GUIDE -> {
                player.closeInventory()
                // Defer a tick so the book opens cleanly after the inventory closes.
                PlayerTasks.run(plugin, player) { plugin.guideBook.open(it) }
                return
            }
            SLOT_PRESETS -> { plugin.gallery.open(player); return }
            SLOT_SAVE -> guarded(player, "aase.scene.save") { controller.save(player) }
            SLOT_EXPORT -> guarded(player, "aase.export.command") { controller.exportCommands(player) }
            SLOT_DELETE -> {
                openDeleteConfirmation(player)
                return
            }
            SLOT_RECALL -> {
                // Preview only (nothing is deleted here). Close first so the chat buttons are clickable.
                player.closeInventory()
                guarded(player, "aase.use") { plugin.recall.previewHere(player, null) }
                return
            }
            SLOT_CLOSE -> { player.closeInventory(); return }

            in PART_SLOTS.keys -> setPart(player, PART_SLOTS.getValue(event.rawSlot))
            in FLAG_SLOTS.keys -> controller.toggleFlag(player, FLAG_SLOTS.getValue(event.rawSlot))
            else -> return
        }
        // Repopulate in place so highlights/values refresh.
        populate(player, event.inventory)
    }

    /**
     * Confirm screen: summary in the first content slot, then cancel and delete far apart with
     * different icons *and* different colours. Cancel is on the left and the destructive one on
     * the right, matching every other confirm screen on the server.
     */
    private fun openDeleteConfirmation(player: Player) {
        val selected = plugin.sessions.get(player.uniqueId)?.selected()
            ?: return texts.send(player, "select.none")
        val holder = DeleteHolder(selected.localId)
        val inv = Bukkit.createInventory(holder, CONFIRM_ROWS * COLUMNS, texts.legacy("panel.confirm-title"))
        holder.inv = inv
        val type = texts.label(if (selected is ArmorStandElement) "label.armorstand" else "label.display")
        inv.setItem(CONFIRM_SUMMARY, icon(Material.TNT, "panel.confirm-summary", "type" to type, "id" to selected.localId.toString()))
        inv.setItem(CONFIRM_CANCEL, icon(Material.LIME_DYE, "panel.confirm-cancel"))
        inv.setItem(CONFIRM_DELETE, icon(Material.LAVA_BUCKET, "panel.confirm-delete", "id" to selected.localId.toString()))
        inv.setItem(CONFIRM_BACK, icon(Material.ARROW, "panel.confirm-back"))
        inv.setItem(CONFIRM_CLOSE, icon(Material.BARRIER, "panel.close"))
        player.openInventory(inv)
    }

    private inline fun guarded(player: Player, permission: String, block: () -> Unit) {
        if (player.hasPermission(permission)) block() else texts.send(player, "system.no-permission")
    }

    private fun setMode(player: Player, mode: EditMode) {
        plugin.sessions.get(player.uniqueId)?.let { it.mode = mode; controller.readout(player, it) }
    }

    private fun setAxis(player: Player, axis: Axis) {
        plugin.sessions.get(player.uniqueId)?.let { it.axis = axis; controller.readout(player, it) }
    }

    private fun setPart(player: Player, part: BodyPart) {
        plugin.sessions.get(player.uniqueId)?.let { it.part = part; controller.readout(player, it) }
    }

    private fun offhandItem(player: Player): ItemRef {
        val off = player.inventory.itemInOffHand
        return ItemResolver.toRef(if (off.type.isAir) ItemStack(Material.STONE) else off)
    }

    private fun populate(player: Player, inv: Inventory) {
        inv.clear()
        val session = plugin.sessions.get(player.uniqueId)
        val selected = session?.selected()
        val isStand = selected is ArmorStandElement
        val hasSession = session != null

        // --- Row 0: elements + select prev/next + undo ---
        inv.setItem(SLOT_ADD_STAND, icon(Material.ARMOR_STAND, "panel.add-stand"))
        inv.setItem(SLOT_ADD_ITEM, icon(Material.ITEM_FRAME, "panel.add-item"))
        inv.setItem(SLOT_ADD_BLOCK, icon(Material.GRASS_BLOCK, "panel.add-block"))
        inv.setItem(SLOT_ADD_TEXT, icon(Material.OAK_SIGN, "panel.add-text"))
        inv.setItem(SLOT_EQUIP, icon(Material.CHEST, "panel.equip"))

        val elementCount = session?.scene?.elements?.size ?: 0
        inv.setItem(SLOT_SELECT_PREV, icon(
            if (elementCount > 1) Material.ARROW else Material.GRAY_DYE,
            if (elementCount > 1) "panel.select-prev" else "panel.select-prev-disabled",
        ))
        inv.setItem(SLOT_SELECT_NEXT, icon(
            if (elementCount > 1) Material.ARROW else Material.GRAY_DYE,
            if (elementCount > 1) "panel.select-next" else "panel.select-next-disabled",
        ))
        val hasUndo = controller.hasUndo(player)
        inv.setItem(SLOT_UNDO, icon(
            if (hasUndo) Material.HONEYCOMB else Material.GRAY_DYE,
            if (hasUndo) "panel.undo" else "panel.undo-empty",
        ))

        // --- Row 1: modes + step/nudge ---
        inv.setItem(SLOT_MODE_MOVE, modeIcon(Material.ENDER_PEARL, "panel.mode-move", session?.mode, EditMode.MOVE))
        inv.setItem(SLOT_MODE_POSE, modeIcon(Material.ARMOR_STAND, "panel.mode-pose", session?.mode, EditMode.POSE))
        inv.setItem(SLOT_MODE_TRANSLATE, modeIcon(Material.PISTON, "panel.mode-translate", session?.mode, EditMode.TRANSLATE))
        inv.setItem(SLOT_MODE_ROTATE, modeIcon(Material.CLOCK, "panel.mode-rotate", session?.mode, EditMode.ROTATE))
        inv.setItem(SLOT_MODE_SCALE, modeIcon(Material.SLIME_BALL, "panel.mode-scale", session?.mode, EditMode.SCALE))

        // Step and nudge buttons show current values in their lore.
        val stepStr = controller.currentStepFormatted(player) ?: "-"
        val axisStr = controller.currentAxisFormatted(player) ?: "-"
        inv.setItem(SLOT_STEP_DOWN, iconWithDynamicLore(Material.RED_DYE, "panel.step-down", "step" to stepStr))
        inv.setItem(SLOT_STEP_UP, iconWithDynamicLore(Material.LIME_DYE, "panel.step-up", "step" to stepStr))
        inv.setItem(SLOT_NUDGE_DOWN, iconWithDynamicLore(Material.RED_CONCRETE, "panel.nudge-down", "axis" to axisStr, "step" to stepStr))
        inv.setItem(SLOT_NUDGE_UP, iconWithDynamicLore(Material.LIME_CONCRETE, "panel.nudge-up", "axis" to axisStr, "step" to stepStr))

        // --- Row 2: body parts + axes ---
        for ((slot, part) in PART_SLOTS) {
            if (isStand) {
                val on = session?.part == part
                inv.setItem(slot, toggleIcon(partMaterial(part), "panel.part-${part.name.lowercase()}", on))
            } else {
                // Display entity selected or nothing selected: grey out body-part slots.
                inv.setItem(slot, icon(Material.GRAY_STAINED_GLASS_PANE, "panel.part-disabled"))
            }
        }

        for ((axis, slot) in mapOf(Axis.X to SLOT_AXIS_X, Axis.Y to SLOT_AXIS_Y, Axis.Z to SLOT_AXIS_Z)) {
            val on = session?.axis == axis
            inv.setItem(slot, toggleIcon(axisMaterial(axis, on), "panel.axis-${axis.name.lowercase()}", on))
        }

        // --- Row 3: appearance flags ---
        for ((slot, flag) in FLAG_SLOTS) {
            if (isStand) {
                val on = (selected as ArmorStandElement).flagValue(flag)
                inv.setItem(slot, toggleIcon(if (on) Material.LIME_DYE else Material.GRAY_DYE, "panel.flag-$flag", on))
            } else {
                inv.setItem(slot, icon(Material.GRAY_STAINED_GLASS_PANE, "panel.flag-disabled"))
            }
        }

        // --- Row 4: scene management ---
        inv.setItem(SLOT_INFO, icon(Material.NAME_TAG, "panel.info",
            "name" to plugin.texts.escape(session?.scene?.name ?: "-"),
            "sel" to (selected?.let { "#${it.localId}" } ?: "-")))
        inv.setItem(SLOT_PRESETS, icon(Material.PAINTING, "panel.presets"))
        inv.setItem(SLOT_SAVE, icon(Material.WRITABLE_BOOK, "panel.save"))
        // Export writes to the server folder. Players without the permission still see the cell,
        // in the locked style with the reason — one "you cannot use this" look, and no hole in the
        // middle of a left-aligned row. IRON_BARS, not GRAY_DYE: grey dye already means "this
        // toggle is off" two rows up, and "off" and "you may not touch this" must not look alike.
        inv.setItem(
            SLOT_EXPORT,
            if (player.hasPermission("aase.export.command")) icon(Material.COMMAND_BLOCK, "panel.export")
            else icon(Material.IRON_BARS, "panel.export-locked"),
        )
        // Delete and close must not share an icon. They used to both be BARRIER, in the same
        // bottom row — the only thing telling them apart was the hover text, and the one you
        // misread destroys the player's work. LAVA_BUCKET is what the delete confirmation
        // dialog below already uses for "確定刪除", so the two now read as the same action.
        inv.setItem(SLOT_DELETE, icon(Material.LAVA_BUCKET, "panel.delete"))
        // Appended after delete so delete keeps the cell players learned. HOPPER = "collect back";
        // not LAVA_BUCKET/BARRIER, which already mean delete and close.
        inv.setItem(SLOT_RECALL, icon(Material.HOPPER, "panel.recall"))

        // --- Footer ---
        // No-session guidance: when a player opens /aase without having started a scene, place
        // a hint in the footer area so the empty panel isn't just dead silence.
        if (!hasSession) {
            inv.setItem(SLOT_NO_SESSION_HINT, icon(Material.KNOWLEDGE_BOOK, "panel.no-session"))
        }
        inv.setItem(SLOT_GUIDE, icon(Material.WRITTEN_BOOK, "panel.guide"))
        inv.setItem(SLOT_CLOSE, icon(Material.BARRIER, "panel.close"))
    }

    private fun ArmorStandElement.flagValue(flag: String) = when (flag) {
        "small" -> flags.small; "invisible" -> flags.invisible; "nobaseplate" -> flags.noBasePlate
        "nogravity" -> flags.noGravity; "arms" -> flags.arms; "marker" -> flags.marker
        "glowing" -> flags.glowing; else -> false
    }

    /**
     * The body-part row is a **radio group**, not six independent switches, so the material has to
     * carry which part it is — the same rule [axisMaterial] already follows. It used to be
     * LIME_DYE/GRAY_DYE like the flag row below, which rendered as six identical grey dots whenever
     * nothing was selected (`docs/ux/evidence/screens-after/aase-panel.png`): the only thing telling
     * them apart was hover text, and the whole point of a control panel is not having to hover.
     * State stays on [toggleIcon]'s `»` marker plus the glint.
     */
    private fun partMaterial(part: BodyPart): Material = when (part) {
        BodyPart.HEAD -> Material.IRON_HELMET
        BodyPart.BODY -> Material.IRON_CHESTPLATE
        BodyPart.LEFT_ARM -> Material.SHIELD
        BodyPart.RIGHT_ARM -> Material.WOODEN_SWORD
        BodyPart.LEFT_LEG -> Material.IRON_BOOTS
        BodyPart.RIGHT_LEG -> Material.GOLDEN_BOOTS
    }

    private fun axisMaterial(axis: Axis, on: Boolean): Material = when (axis) {
        Axis.X -> if (on) Material.RED_CONCRETE else Material.RED_STAINED_GLASS
        Axis.Y -> if (on) Material.LIME_CONCRETE else Material.GREEN_STAINED_GLASS
        Axis.Z -> if (on) Material.LIGHT_BLUE_CONCRETE else Material.BLUE_STAINED_GLASS
    }

    private fun modeIcon(material: Material, key: String, current: EditMode?, mode: EditMode) =
        toggleIcon(material, key, current == mode)

    @Suppress("DEPRECATION")
    private fun icon(material: Material, nameKey: String, vararg ph: Pair<String, String>): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.setDisplayName(texts.legacy(nameKey, *ph))
        texts.raw("$nameKey-lore")?.let { meta.lore = listOf(texts.legacy("$nameKey-lore", *ph)) }
        item.itemMeta = meta
        return item
    }

    /** Icon whose lore uses dynamic placeholders (step, axis) that change per-open. */
    @Suppress("DEPRECATION")
    private fun iconWithDynamicLore(material: Material, nameKey: String, vararg ph: Pair<String, String>): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.setDisplayName(texts.legacy(nameKey, *ph))
        texts.raw("$nameKey-lore")?.let { meta.lore = listOf(texts.legacy("$nameKey-lore", *ph)) }
        item.itemMeta = meta
        return item
    }

    @Suppress("DEPRECATION")
    private fun toggleIcon(material: Material, nameKey: String, on: Boolean): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        val marker = if (on) "» " else ""
        meta.setDisplayName(marker + texts.legacy(nameKey))
        texts.raw("$nameKey-lore")?.let { meta.lore = listOf(texts.legacy("$nameKey-lore")) }
        item.itemMeta = meta
        return item
    }

    /**
     * Every slot on this screen, derived from the shared band rules instead of ~30 hand-picked
     * numbers (`docs/ux/CHEST_UI_DESIGN_SYSTEM.md` §4): **one row is one group, the cells in a
     * group are contiguous from column 0, and rows — not gaps inside a row — separate groups.**
     *
     * The old numbering was the "periodic table" layout this revamp exists to kill: modes started
     * at column 0 but axes at column 0 of another row with a hole at 30 and 33, save/delete/export/
     * presets/close were sprinkled across the *footer* row, and the top row read
     * `info · add add add add · · guide`.
     *
     * Two things this fixes beyond looks:
     * - **Nothing sits on `base + 4` (49) any more.** That slot is the inert page-number indicator
     *   on every paged screen in the game, including this plugin's own preset gallery. A player who
     *   has learned "slot 49 does nothing" must not find "delete my work" — or anything at all —
     *   there. Delete now lives in the scene row with a distinct icon, and the footer row holds
     *   only navigation.
     * - Delete and close no longer share the bottom row, and never shared an icon.
     */
    internal companion object {
        const val COLUMNS = 9
        const val PANEL_ROWS = 6
        const val CONFIRM_ROWS = 3

        private fun row(index: Int, count: Int): List<Int> = (0 until count).map { index * COLUMNS + it }

        /** Row 0 — elements: the four "add" buttons, equipment sub-menu, then select prev/next + undo. */
        private val ELEMENT = row(0, 8)
        val SLOT_ADD_STAND = ELEMENT[0]
        val SLOT_ADD_ITEM = ELEMENT[1]
        val SLOT_ADD_BLOCK = ELEMENT[2]
        val SLOT_ADD_TEXT = ELEMENT[3]
        val SLOT_EQUIP = ELEMENT[4]
        val SLOT_SELECT_PREV = ELEMENT[5]
        val SLOT_SELECT_NEXT = ELEMENT[6]
        val SLOT_UNDO = ELEMENT[7]

        /** Row 1 — how to adjust: the five edit modes, then how far one click moves. */
        private val ADJUST = row(1, 9)
        val SLOT_MODE_MOVE = ADJUST[0]
        val SLOT_MODE_POSE = ADJUST[1]
        val SLOT_MODE_TRANSLATE = ADJUST[2]
        val SLOT_MODE_ROTATE = ADJUST[3]
        val SLOT_MODE_SCALE = ADJUST[4]
        val SLOT_STEP_DOWN = ADJUST[5]
        val SLOT_STEP_UP = ADJUST[6]
        val SLOT_NUDGE_DOWN = ADJUST[7]
        val SLOT_NUDGE_UP = ADJUST[8]

        /** Row 2 — what to adjust: the six body parts, then the three axes. */
        private val TARGET = row(2, 9)
        val SLOT_AXIS_X = TARGET[6]
        val SLOT_AXIS_Y = TARGET[7]
        val SLOT_AXIS_Z = TARGET[8]

        val PART_SLOTS: Map<Int, BodyPart> = listOf(
            BodyPart.HEAD, BodyPart.BODY, BodyPart.LEFT_ARM,
            BodyPart.RIGHT_ARM, BodyPart.LEFT_LEG, BodyPart.RIGHT_LEG,
        ).withIndex().associate { (index, part) -> TARGET[index] to part }

        /** Row 3 — appearance toggles: the seven armour-stand flags. */
        val FLAG_SLOTS: Map<Int, String> = listOf(
            "small", "invisible", "nobaseplate", "nogravity", "arms", "marker", "glowing",
        ).withIndex().associate { (index, flag) -> row(3, 7)[index] to flag }

        /**
         * Row 4 — the scene: state card, presets, save, export, delete, recall.
         *
         * Export keeps its cell even when the player lacks `aase.export.command`; it renders in the
         * "locked" style with the reason instead of disappearing. A left-aligned group has no holes
         * in it, so a vanishing button in the middle of a row would read as "something failed to
         * load", and delete would silently shift under the cursor of anyone who had learned its
         * position. Recall (1.2.0) was appended after delete for the same reason, so delete is no
         * longer last in the row; recall only opens a preview, it never deletes on click.
         */
        private val SCENE = row(4, 6)
        val SLOT_INFO = SCENE[0]
        val SLOT_PRESETS = SCENE[1]
        val SLOT_SAVE = SCENE[2]
        val SLOT_EXPORT = SCENE[3]
        val SLOT_DELETE = SCENE[4]
        val SLOT_RECALL = SCENE[5]

        /** Footer: `base = (rows - 1) * 9`, then `?` at `base + 7` and `✕` at `base + 8`. */
        private const val PANEL_FOOTER_BASE = (PANEL_ROWS - 1) * COLUMNS
        /** No-session hint goes at footer base + 0 (the left edge) when there is no active session. */
        const val SLOT_NO_SESSION_HINT = PANEL_FOOTER_BASE
        const val SLOT_GUIDE = PANEL_FOOTER_BASE + 7
        const val SLOT_CLOSE = PANEL_FOOTER_BASE + 8

        private const val CONFIRM_FOOTER_BASE = (CONFIRM_ROWS - 1) * COLUMNS
        const val CONFIRM_SUMMARY = 0
        const val CONFIRM_CANCEL = 10
        const val CONFIRM_DELETE = 16
        const val CONFIRM_BACK = CONFIRM_FOOTER_BASE
        /** `base + 8`, not `base + 4` — the middle of the footer is the page indicator everywhere else. */
        const val CONFIRM_CLOSE = CONFIRM_FOOTER_BASE + 8

        /** Every slot [populate] can write to, derived from the constants above so a test can check them. */
        val PANEL_SLOTS: List<Int> =
            ELEMENT + ADJUST + TARGET + FLAG_SLOTS.keys.sorted() + SCENE +
                listOf(SLOT_NO_SESSION_HINT, SLOT_GUIDE, SLOT_CLOSE)

        val CONFIRM_SLOTS: List<Int> =
            listOf(CONFIRM_SUMMARY, CONFIRM_CANCEL, CONFIRM_DELETE, CONFIRM_BACK, CONFIRM_CLOSE)
    }
}
