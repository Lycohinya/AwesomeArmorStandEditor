package com.tinyyana.awesomeArmorStandEditor.command

import com.tinyyana.awesomeArmorStandEditor.AwesomeArmorStandEditorPlugin
import com.tinyyana.awesomeArmorStandEditor.edit.ToolItem
import com.tinyyana.awesomeArmorStandEditor.model.DisplayKind
import com.tinyyana.awesomeArmorStandEditor.store.ItemCodec
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

class AaseCommand(private val plugin: AwesomeArmorStandEditorPlugin) : TabExecutor {

    private val texts get() = plugin.texts
    private val controller get() = plugin.controller

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val sub = args.firstOrNull()?.lowercase() ?: ""

        // Console can only reload.
        if (sender !is Player) {
            if (sub == "reload" && sender.hasPermission("aase.admin")) {
                plugin.reloadAll(); texts.send(sender, "reload.ok")
            } else {
                texts.send(sender, "system.player-only")
            }
            return true
        }

        when (sub) {
            "" -> require(sender, "aase.use") { plugin.panel.open(sender) }
            "tool" -> require(sender, "aase.use") { giveTool(sender) }
            "new" -> require(sender, "aase.use") {
                val name = args.getOrNull(1)
                if (name == null) deny(sender, "usage.new") else controller.openNew(sender, name)
            }
            "addstand" -> require(sender, "aase.create.armorstand") { controller.addStand(sender) }
            "adddisplay" -> require(sender, "aase.create.display") { addDisplay(sender, args.getOrNull(1)) }
            "setblock" -> require(sender, "aase.use") {
                controller.setDisplayPayload(sender, DisplayKind.BLOCK, args.drop(1).joinToString(" ").ifBlank { "minecraft:stone" })
            }
            "settext" -> require(sender, "aase.use") {
                controller.setDisplayPayload(sender, DisplayKind.TEXT, args.drop(1).joinToString(" "))
            }
            "setitem" -> require(sender, "aase.use") { controller.setDisplayItemFromOffhand(sender) }
            "setequip" -> require(sender, "aase.use") {
                val slot = args.getOrNull(1)
                if (slot == null) deny(sender, "usage.setequip") else controller.setEquip(sender, slot)
            }
            "flag" -> require(sender, "aase.use") {
                val flag = args.getOrNull(1)
                if (flag == null) deny(sender, "usage.flag") else controller.toggleFlag(sender, flag)
            }
            "setname" -> require(sender, "aase.use") {
                controller.setName(sender, args.drop(1).joinToString(" ").ifBlank { null })
            }
            "save" -> require(sender, "aase.scene.save") { controller.save(sender) }
            "load" -> require(sender, "aase.use") {
                val name = args.getOrNull(1)
                if (name == null) deny(sender, "usage.load") else controller.loadFresh(sender, name)
            }
            "edit" -> require(sender, "aase.use") { controller.editFromTarget(sender) }
            "select" -> require(sender, "aase.use") {
                val arg = args.getOrNull(1)
                if (arg == null) deny(sender, "usage.select") else controller.selectById(sender, arg)
            }
            "list" -> require(sender, "aase.use") { listScenes(sender) }
            "info" -> require(sender, "aase.use") { controller.info(sender) }
            "equip" -> require(sender, "aase.use") { plugin.equipmentMenu.open(sender) }
            "delete" -> require(sender, "aase.use") {
                // Optional element number: chat buttons name the exact element, never "whatever is selected".
                val id = args.getOrNull(1)
                when {
                    id == null -> controller.deleteSelected(sender)
                    id.toIntOrNull() != null -> controller.deleteElement(sender, id.toInt())
                    else -> deny(sender, "usage.delete")
                }
            }
            // Recall your own placed work. Owner-only even for admins (they have /aase admin).
            "remove" -> require(sender, "aase.use") {
                when (args.getOrNull(1)?.lowercase()) {
                    "look" -> plugin.recall.previewLook(sender)
                    "here" -> plugin.recall.previewHere(sender, args.getOrNull(2))
                    "scene" -> {
                        val name = args.drop(2).joinToString(" ")
                        if (name.isBlank()) deny(sender, "usage.remove") else plugin.recall.previewScene(sender, name)
                    }
                    "confirm" -> plugin.recall.confirm(sender)
                    "cancel" -> plugin.recall.cancel(sender)
                    else -> deny(sender, "usage.remove")
                }
            }
            "share" -> require(sender, "aase.scene.share") { controller.shareCode(sender) }
            "import" -> require(sender, "aase.scene.share") {
                val code = args.getOrNull(1)
                if (code == null) deny(sender, "usage.import")
                else controller.importCode(sender, code, args.drop(2).joinToString(" ").ifBlank { null })
            }
            "export" -> require(sender, "aase.export.command") {
                when (args.getOrNull(1)?.lowercase()) {
                    "command" -> controller.exportCommands(sender)
                    "function", "mcfunction" -> controller.exportMcFunction(sender)
                    else -> deny(sender, "usage.export")
                }
            }
            "particle" -> require(sender, "aase.use") {
                when (args.getOrNull(1)?.lowercase()) {
                    "add" -> args.getOrNull(2)?.let { controller.addEmitter(sender, it) } ?: deny(sender, "usage.particle")
                    "clear" -> controller.clearEmitters(sender)
                    else -> deny(sender, "usage.particle")
                }
            }
            "anim" -> require(sender, "aase.animate") {
                when (args.getOrNull(1)?.lowercase()) {
                    "key" -> controller.animKey(sender, args.getOrNull(2)?.toIntOrNull() ?: 0)
                    "length" -> args.getOrNull(2)?.toIntOrNull()?.let { controller.animLength(sender, it) } ?: deny(sender, "usage.anim")
                    "loop" -> controller.animToggleLoop(sender)
                    "play" -> controller.animPlay(sender)
                    "stop" -> controller.animStop(sender)
                    "clear" -> controller.animClear(sender)
                    else -> deny(sender, "usage.anim")
                }
            }
            "guide" -> require(sender, "aase.use") {
                if (sender is Player) plugin.guideBook.open(sender) else texts.send(sender, "system.player-only")
            }
            "presets", "gallery" -> require(sender, "aase.use") { plugin.gallery.open(sender) }
            "pose" -> when (val a = args.getOrNull(1)?.lowercase()) {
                null -> require(sender, "aase.use") { deny(sender, "usage.pose") }
                // Writing to the shared presets.yml is a builder/admin action, not for every player.
                "save" -> require(sender, "aase.preset.save") {
                    args.getOrNull(2)?.let { controller.savePose(sender, it, args.drop(3).joinToString(" ").ifBlank { null }) }
                        ?: deny(sender, "usage.pose")
                }
                else -> require(sender, "aase.use") { controller.applyPose(sender, a) }
            }
            "fx" -> require(sender, "aase.use") {
                args.getOrNull(1)?.let { controller.applyFx(sender, it) } ?: deny(sender, "usage.fx")
            }
            "mirror" -> require(sender, "aase.use") { controller.mirrorPose(sender) }
            "close" -> require(sender, "aase.use") { controller.close(sender, args.getOrNull(1)) }
            // Not admin-gated: it can only clear other people's elements off ground you may build on.
            "undo" -> require(sender, "aase.use") { controller.undo(sender) }
            "clear" -> require(sender, "aase.clear") { plugin.adminTools.clearIntruders(sender, args.drop(1)) }
            // Moderation: whois/remove/purge act on OTHER people's art, so they all sit behind aase.admin.
            "admin" -> require(sender, "aase.admin") {
                when (args.getOrNull(1)?.lowercase()) {
                    "whois" -> plugin.adminTools.whois(sender)
                    "remove" -> plugin.adminTools.removeTargeted(sender)
                    "purge" -> plugin.adminTools.previewPurge(sender, args.drop(2))
                    "confirm" -> plugin.adminTools.confirmPurge(sender)
                    else -> deny(sender, "usage.admin")
                }
            }
            "reload" -> require(sender, "aase.admin") { plugin.reloadAll(); texts.send(sender, "reload.ok") }
            else -> help(sender)
        }
        return true
    }

    private fun addDisplay(player: Player, kindArg: String?) {
        val kind = when (kindArg?.lowercase()) {
            "item" -> DisplayKind.ITEM
            "block" -> DisplayKind.BLOCK
            "text" -> DisplayKind.TEXT
            else -> return deny(player, "usage.adddisplay")
        }
        val payload = when (kind) {
            DisplayKind.ITEM -> {
                val off = player.inventory.itemInOffHand
                ItemCodec.encode(if (off.type.isAir) ItemStack(Material.STONE) else off)
            }
            DisplayKind.BLOCK -> "minecraft:stone"
            DisplayKind.TEXT -> texts.label("display.default-text")
        }
        controller.addDisplay(player, kind, payload)
    }

    private fun giveTool(player: Player) {
        val lore = listOf("tool.lore1", "tool.lore2", "tool.lore3", "tool.lore4", "tool.lore5")
            .filter { texts.raw(it) != null }
            .map { texts.legacy(it) }
        val tool = ToolItem.create(plugin.keys, plugin.settings.toolMaterial, texts.legacy("tool.name"), lore)
        player.inventory.addItem(tool)
        texts.send(player, "tool.given")
    }

    private fun listScenes(player: Player) {
        val scenes = plugin.store.list(player.uniqueId)
        if (scenes.isEmpty()) return texts.send(player, "list.empty")
        texts.send(player, "list.header", "count" to scenes.size.toString())
        for (s in scenes) texts.send(player, "list.entry", "name" to s.name, "count" to s.elements.size.toString())
    }

    private fun help(sender: CommandSender) {
        // Players get the full flip-through book; console gets the quick chat list.
        if (sender is Player) {
            plugin.guideBook.open(sender)
            texts.send(sender, "help.opened-book")
            return
        }
        texts.send(sender, "help.header")
        for (line in listOf(
            "help.new", "help.tool", "help.presets", "help.addstand", "help.adddisplay", "help.controls",
            "help.equip", "help.save", "help.load", "help.edit", "help.list", "help.info",
            "help.export", "help.share", "help.import", "help.remove", "help.close",
        )) if (texts.raw(line) != null) texts.send(sender, line)
    }

    private inline fun require(sender: CommandSender, permission: String, block: () -> Unit) {
        if (!sender.hasPermission(permission)) texts.send(sender, "system.no-permission") else block()
    }

    private fun deny(sender: CommandSender, usageKey: String) = texts.send(sender, usageKey)

    private val subcommands = listOf(
        "guide", "tool", "new", "presets", "pose", "fx", "mirror", "addstand", "adddisplay", "setblock", "settext",
        "setitem", "setname", "setequip", "equip", "flag", "particle", "anim", "save", "load", "edit", "select", "list", "info",
        "undo", "delete", "remove", "export", "share", "import", "close", "clear", "admin", "reload",
    )

    /** Hidden from tab-complete for players who can't use them. */
    private val adminSubcommands = setOf("admin", "reload")
    private val adminActions = listOf("whois", "remove", "purge", "confirm")

    private val equipSlots = listOf("head", "chest", "legs", "feet", "mainhand", "offhand")
    private val flagNames = listOf("small", "invisible", "nobaseplate", "nogravity", "arms", "marker", "glowing")
    private val commonParticles = listOf("HAPPY_VILLAGER", "HEART", "FLAME", "SOUL_FIRE_FLAME", "END_ROD", "CHERRY_LEAVES", "DUST", "CRIT", "ENCHANT", "PORTAL")

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> subcommands
                .filter { it !in adminSubcommands || sender.hasPermission("aase.admin") }
                .filter { it.startsWith(args[0].lowercase()) }
            2 -> when (args[0].lowercase()) {
                "admin" -> if (sender.hasPermission("aase.admin")) adminActions.filter { it.startsWith(args[1].lowercase()) } else emptyList()
                "adddisplay" -> listOf("item", "block", "text").filter { it.startsWith(args[1].lowercase()) }
                "export" -> listOf("command", "function").filter { it.startsWith(args[1].lowercase()) }
                "setequip" -> equipSlots.filter { it.startsWith(args[1].lowercase()) }
                "flag" -> flagNames.filter { it.startsWith(args[1].lowercase()) }
                "particle" -> listOf("add", "clear").filter { it.startsWith(args[1].lowercase()) }
                "anim" -> listOf("key", "length", "loop", "play", "stop", "clear").filter { it.startsWith(args[1].lowercase()) }
                "pose" -> (listOf("save") + plugin.presets.poses.map { it.id }).filter { it.startsWith(args[1].lowercase()) }
                "fx" -> plugin.presets.fx.map { it.id }.filter { it.startsWith(args[1].lowercase()) }
                "load" -> if (sender is Player) plugin.store.list(sender.uniqueId).map { it.name }.filter { it.startsWith(args[1]) } else emptyList()
                "select" -> {
                    val ids = (sender as? Player)?.let { plugin.sessions.get(it.uniqueId) }
                        ?.scene?.elements?.map { it.localId.toString() } ?: emptyList()
                    (listOf("next", "prev") + ids).filter { it.startsWith(args[1].lowercase()) }
                }
                "clear" -> listOf("8", "16", "32").filter { it.startsWith(args[1]) }
                "remove" -> listOf("look", "here", "scene", "confirm", "cancel").filter { it.startsWith(args[1].lowercase()) }
                "close" -> listOf("save", "discard").filter { it.startsWith(args[1].lowercase()) }
                "delete" -> ((sender as? Player)?.let { plugin.sessions.get(it.uniqueId) }
                    ?.scene?.elements?.map { it.localId.toString() } ?: emptyList()).filter { it.startsWith(args[1]) }
                else -> emptyList()
            }
            3 -> when (args[0].lowercase()) {
                "particle" -> if (args[1].equals("add", true)) commonParticles.filter { it.startsWith(args[2].uppercase()) } else emptyList()
                "remove" -> when (args[1].lowercase()) {
                    "here" -> listOf("8", "16", "32").filter { it.startsWith(args[2]) }
                    "scene" -> if (sender is Player) {
                        plugin.store.list(sender.uniqueId).map { it.name }.filter { it.startsWith(args[2], ignoreCase = true) }
                    } else emptyList()
                    else -> emptyList()
                }
                "admin" -> if (args[1].equals("purge", true) && sender.hasPermission("aase.admin")) {
                    listOf("8", "16", "32", "64").filter { it.startsWith(args[2]) }
                } else emptyList()
                else -> emptyList()
            }
            4 -> if (args[0].equals("admin", true) && args[1].equals("purge", true) && sender.hasPermission("aase.admin")) {
                plugin.server.onlinePlayers.map { it.name }.filter { it.startsWith(args[3], ignoreCase = true) }
            } else emptyList()
            else -> emptyList()
        }
    }
}
