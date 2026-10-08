package com.tinyyana.awesomeArmorStandEditor.export

import com.tinyyana.awesomeArmorStandEditor.model.ArmorStandElement
import com.tinyyana.awesomeArmorStandEditor.model.DisplayElement
import com.tinyyana.awesomeArmorStandEditor.model.DisplayKind
import com.tinyyana.awesomeArmorStandEditor.model.Element
import com.tinyyana.awesomeArmorStandEditor.model.EulerXYZ
import com.tinyyana.awesomeArmorStandEditor.model.ItemRef
import com.tinyyana.awesomeArmorStandEditor.model.Scene
import com.tinyyana.awesomeArmorStandEditor.store.ItemCodec
import java.util.Locale

/**
 * Best-effort /summon export. Coordinates are relative (~dx ~dy ~dz) so the output is placed
 * relative to whoever runs the commands.
 *
 * NBT caveat: armor-stand Pose/flags and display transformation have been stable vanilla NBT for
 * years; items are emitted by ItemRef id + count (components / custom item data are NOT carried).
 * If a future MC changes this NBT shape, this exporter is the single isolated place to fix.
 */
object SummonExporter {

    /** [tagPrefix] (optional) adds `Tags:["<prefix><localId>"]` so frames can target each entity. */
    fun export(scene: Scene, tagPrefix: String? = null): String =
        scene.elements.joinToString("\n") { line(it, tagPrefix) }

    private fun line(el: Element, tagPrefix: String?): String {
        // Coordinates are NOT NBT floats — they must have no 'f' suffix (~0f is a parse error).
        val (dx, dy, dz) = Triple(coord(el.offset.x), coord(el.offset.y), coord(el.offset.z))
        val tag = tagPrefix?.let { "$it${el.localId}" }
        return when (el) {
            is ArmorStandElement -> "/summon minecraft:armor_stand ~$dx ~$dy ~$dz ${armorStandNbt(el, tag)}"
            is DisplayElement -> "/summon minecraft:${displayType(el.kind)} ~$dx ~$dy ~$dz ${displayNbt(el, tag)}"
        }
    }

    private fun displayType(kind: DisplayKind) = when (kind) {
        DisplayKind.ITEM -> "item_display"
        DisplayKind.BLOCK -> "block_display"
        DisplayKind.TEXT -> "text_display"
    }

    private fun armorStandNbt(el: ArmorStandElement, tag: String?): String {
        val f = el.flags
        val tags = mutableListOf(
            "Small:${b(f.small)}",
            "NoBasePlate:${b(f.noBasePlate)}",
            "ShowArms:${b(f.arms)}",
            "Marker:${b(f.marker)}",
            "Invisible:${b(f.invisible)}",
            "NoGravity:${b(f.noGravity)}",
            "Glowing:${b(f.glowing)}",
            "Rotation:[${f(el.yaw.toDouble())},0f]",
            "Pose:${poseNbt(el.pose)}",
        )
        armorItemsNbt(el)?.let { tags += it }
        el.customName?.takeIf { it.isNotBlank() }?.let { tags += "CustomName:${snbt(plain(it))}" }
        tag?.let { tags += "Tags:[\"$it\"]" }
        return "{${tags.joinToString(",")}}"
    }

    internal fun poseNbt(p: com.tinyyana.awesomeArmorStandEditor.model.Pose6): String {
        // Pose NBT is in DEGREES.
        fun part(e: EulerXYZ) = "[${deg(e.x)},${deg(e.y)},${deg(e.z)}]"
        return "{Head:${part(p.head)},Body:${part(p.body)},LeftArm:${part(p.leftArm)}," +
            "RightArm:${part(p.rightArm)},LeftLeg:${part(p.leftLeg)},RightLeg:${part(p.rightLeg)}}"
    }

    private fun armorItemsNbt(el: ArmorStandElement): String? {
        val e = el.equipment
        if (listOf(e.head, e.chest, e.legs, e.feet, e.mainHand, e.offHand).all { it == null }) return null
        // ArmorItems order: feet, legs, chest, head. HandItems: mainhand, offhand.
        val armor = listOf(e.feet, e.legs, e.chest, e.head).joinToString(",") { itemNbt(it) }
        val hands = listOf(e.mainHand, e.offHand).joinToString(",") { itemNbt(it) }
        return "ArmorItems:[$armor],HandItems:[$hands]"
    }

    /** id + count from the ItemRef (components are not exported). Legacy base64-only items are decoded. */
    private fun itemNbt(ref: ItemRef?): String {
        if (ref == null) return "{}"
        val (id, count) = idAndCount(ref) ?: return "{}"
        return "{id:\"$id\",count:${count.coerceAtLeast(1)}}"
    }

    private fun idAndCount(ref: ItemRef): Pair<String, Int>? {
        ref.id?.let { return it to ref.count }
        val item = ref.bukkit?.let { ItemCodec.decode(it) } ?: return null
        return item.type.key.toString() to item.amount
    }

    private fun displayNbt(el: DisplayElement, tag: String?): String {
        val tags = mutableListOf(
            "transformation:${transformNbt(el.transform)}",
            "billboard:\"${el.billboard.lowercase()}\"",
            "view_range:${f(el.viewRange.toDouble())}",
        )
        if (el.brightnessBlock != null && el.brightnessSky != null) {
            tags += "brightness:{block:${el.brightnessBlock},sky:${el.brightnessSky}}"
        }
        el.glowColor?.let { tags += "Glowing:1b,glow_color_override:$it" }
        when (el.kind) {
            DisplayKind.ITEM -> el.item?.let { idAndCount(it) }?.let { (id, count) ->
                tags += "item:{id:\"$id\",count:${count.coerceAtLeast(1)}}"
            }
            DisplayKind.BLOCK -> tags += "block_state:{Name:\"${blockName(el.payload)}\"}"
            DisplayKind.TEXT -> tags += "text:${snbt(plain(el.payload))}"
        }
        tag?.let { tags += "Tags:[\"$it\"]" }
        return "{${tags.joinToString(",")}}"
    }

    internal fun transformNbt(t: com.tinyyana.awesomeArmorStandEditor.model.Transform): String {
        fun v3(v: com.tinyyana.awesomeArmorStandEditor.model.Vec3) = "[${f(v.x)},${f(v.y)},${f(v.z)}]"
        fun q(q: com.tinyyana.awesomeArmorStandEditor.model.Quat) = "[${f(q.x)},${f(q.y)},${f(q.z)},${f(q.w)}]"
        return "{translation:${v3(t.translation)},left_rotation:${q(t.leftRotation)}," +
            "scale:${v3(t.scale)},right_rotation:${q(t.rightRotation)}}"
    }

    // --- formatting helpers ---

    private fun b(v: Boolean) = if (v) "1b" else "0b"

    /** Trimmed plain number, e.g. 0.0 -> "0", 0.5 -> "0.5". */
    private fun num(v: Double): String =
        String.format(Locale.ROOT, "%.4f", v).let { if (it.contains('.')) it.trimEnd('0').trimEnd('.') else it }

    /** NBT float literal (needs the 'f' suffix): Pose, Rotation, transformation, view_range. */
    private fun f(v: Double): String = "${num(v)}f"

    /** Command coordinate (no 'f' suffix) — used after `~`. */
    private fun coord(v: Double): String = num(v)

    private fun deg(rad: Double) = f(Math.toDegrees(rad))

    private fun blockName(blockData: String): String =
        blockData.substringBefore('[').ifBlank { "minecraft:stone" }

    /** MiniMessage/section text -> plain text for NBT (formatting dropped in export). */
    private fun plain(s: String): String = s.replace(Regex("<[^>]*>"), "").replace(Regex("§."), "")

    /**
     * SNBT double-quoted string. In MC 26.2 text components (CustomName, text_display text) are
     * SNBT — a bare quoted string is plain text. The old JSON-string form '{"text":"..."}' is
     * stored literally (verified via /summon on 26.2), so we must NOT use it.
     *
     * Escaping (shared with Pose Pavilion, keep both in step): backslash, double quote, CR, LF and TAB
     * become `\\`, `\"`, `\r`, `\n`, `\t`; every other character is left as is. A raw line break would
     * split the command.
     */
    internal fun snbt(text: String): String {
        val sb = StringBuilder(text.length + 2).append('"')
        for (c in text) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\r' -> sb.append("\\r")
                '\n' -> sb.append("\\n")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
