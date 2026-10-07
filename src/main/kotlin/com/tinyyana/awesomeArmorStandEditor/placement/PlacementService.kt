package com.tinyyana.awesomeArmorStandEditor.placement

import com.tinyyana.awesomeArmorStandEditor.AaseKeys
import com.tinyyana.awesomeArmorStandEditor.model.ArmorStandElement
import com.tinyyana.awesomeArmorStandEditor.model.DisplayElement
import com.tinyyana.awesomeArmorStandEditor.model.DisplayKind
import com.tinyyana.awesomeArmorStandEditor.model.Element
import com.tinyyana.awesomeArmorStandEditor.model.EulerXYZ
import com.tinyyana.awesomeArmorStandEditor.model.Quat
import com.tinyyana.awesomeArmorStandEditor.model.Transform
import com.tinyyana.awesomeArmorStandEditor.model.Vec3
import com.tinyyana.awesomeArmorStandEditor.session.EditSession
import com.tinyyana.awesomeArmorStandEditor.model.ItemRef
import com.tinyyana.awesomeArmorStandEditor.store.ItemResolver
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.util.EulerAngle
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID

/**
 * Spawns/re-applies/despawns the entities that realise a [Scene]'s elements. The model is
 * authoritative; [apply] is idempotent so an edit = mutate model then re-apply to the live entity.
 * Bukkit-only API (runs on Spigot + Paper).
 */
class PlacementService(private val registry: EntityRegistry, private val keys: AaseKeys) {

    /** One item slot that could not be turned into an ItemStack (slot: head/chest/legs/feet/mainhand/offhand/item). */
    data class ItemFailure(val localId: Int, val slot: String, val id: String?)

    /**
     * apply() runs on every nudge, so resolved items are cached (small LRU; values are cloned on use).
     * A failed resolve is cached too, so a bad id isn't re-parsed on every click.
     */
    private val itemCache = object : LinkedHashMap<ItemRef, ItemStack?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ItemRef, ItemStack?>?) = size > 256
    }

    private fun resolve(ref: ItemRef): ItemStack? {
        synchronized(itemCache) { if (itemCache.containsKey(ref)) return itemCache[ref]?.clone() }
        val stack = ItemResolver.resolve(ref)
        synchronized(itemCache) { itemCache[ref] = stack }
        return stack?.clone()
    }

    private val mm = MiniMessage.miniMessage()
    private val legacy = LegacyComponentSerializer.legacySection()

    fun elementLocation(origin: Location, element: Element): Location =
        origin.clone().add(element.offset.x, element.offset.y, element.offset.z).apply { yaw = element.yaw }

    /** Spawn one element, tag it with ownership PDC, and return the live entity. */
    fun spawn(
        origin: Location, sceneId: String, element: Element, owner: UUID, placementId: String, sceneName: String,
        failures: MutableList<ItemFailure>? = null,
    ): Entity {
        val loc = elementLocation(origin, element)
        val world = loc.world ?: error("Location has no world")
        val sink = failures ?: mutableListOf()
        val entity: Entity = when (element) {
            is ArmorStandElement -> world.spawn(loc, ArmorStand::class.java) { sink += apply(it, element) }
            is DisplayElement -> when (element.kind) {
                DisplayKind.ITEM -> world.spawn(loc, ItemDisplay::class.java) { sink += apply(it, element) }
                DisplayKind.BLOCK -> world.spawn(loc, BlockDisplay::class.java) { apply(it, element) }
                DisplayKind.TEXT -> world.spawn(loc, TextDisplay::class.java) { apply(it, element) }
            }
        }
        registry.tag(entity, owner, sceneId, element.localId, placementId, sceneName)
        return entity
    }

    /**
     * Place every element of the session's scene at [origin], filling the live-entity map.
     * Returns the item slots that could not be resolved (unknown id, bad components) so the caller can
     * tell the player which slots stayed empty.
     */
    fun placeAll(session: EditSession, origin: Location, owner: UUID): List<ItemFailure> {
        session.origin = origin
        val failures = mutableListOf<ItemFailure>()
        for (element in session.scene.elements) {
            session.entities[element.localId] =
                spawn(origin, session.scene.id, element, owner, session.placementId, session.scene.name, failures)
        }
        return failures
    }

    fun despawnAll(session: EditSession) {
        for (entity in session.entities.values) {
            registry.forget(entity.uniqueId)
            if (!entity.isDead) entity.remove()
        }
        session.entities.clear()
    }

    fun despawn(session: EditSession, localId: Int) {
        session.entities.remove(localId)?.let {
            registry.forget(it.uniqueId)
            if (!it.isDead) it.remove()
        }
    }

    /**
     * Re-apply the model to an existing entity after an edit. No-op on type mismatch.
     * Returns the item slots that could not be resolved (left empty).
     */
    fun apply(entity: Entity, element: Element): List<ItemFailure> {
        val failures = mutableListOf<ItemFailure>()
        when {
            entity is ArmorStand && element is ArmorStandElement -> applyArmorStand(entity, element, failures)
            entity is ItemDisplay && element is DisplayElement -> applyItemDisplay(entity, element, failures)
            entity is BlockDisplay && element is DisplayElement -> applyBlockDisplay(entity, element)
            entity is TextDisplay && element is DisplayElement -> applyTextDisplay(entity, element)
        }
        return failures
    }

    /** Resolve a slot to an ItemStack, or AIR to clear it (the setters here are non-null). */
    private fun item(ref: ItemRef?, localId: Int, slot: String, failures: MutableList<ItemFailure>): ItemStack {
        ref ?: return ItemStack(Material.AIR)
        return resolve(ref) ?: ItemStack(Material.AIR).also { failures += ItemFailure(localId, slot, ref.id) }
    }

    private fun euler(e: EulerXYZ) = EulerAngle(e.x, e.y, e.z)

    /** Apply just a pose to an armor stand (shared with the animation runtime). */
    fun applyPose(s: ArmorStand, pose: com.tinyyana.awesomeArmorStandEditor.model.Pose6) {
        s.setHeadPose(euler(pose.head))
        s.setBodyPose(euler(pose.body))
        s.setLeftArmPose(euler(pose.leftArm))
        s.setRightArmPose(euler(pose.rightArm))
        s.setLeftLegPose(euler(pose.leftLeg))
        s.setRightLegPose(euler(pose.rightLeg))
    }

    /** Apply just a transform to a display, optionally with client-side interpolation over N ticks. */
    fun applyDisplayTransform(d: Display, t: Transform, interpolateTicks: Int) {
        if (interpolateTicks > 0) {
            d.interpolationDelay = 0
            d.interpolationDuration = interpolateTicks
        }
        d.transformation = t.toBukkit()
    }

    private fun applyArmorStand(s: ArmorStand, el: ArmorStandElement, failures: MutableList<ItemFailure>) {
        applyPose(s, el.pose)

        s.isSmall = el.flags.small
        s.setBasePlate(!el.flags.noBasePlate)
        s.setArms(el.flags.arms)
        s.isMarker = el.flags.marker
        s.isVisible = !el.flags.invisible
        s.setGravity(!el.flags.noGravity)
        s.isGlowing = el.flags.glowing

        val eq = s.equipment
        val id = el.localId
        eq.setHelmet(item(el.equipment.head, id, "head", failures))
        eq.setChestplate(item(el.equipment.chest, id, "chest", failures))
        eq.setLeggings(item(el.equipment.legs, id, "legs", failures))
        eq.setBoots(item(el.equipment.feet, id, "feet", failures))
        eq.setItemInMainHand(item(el.equipment.mainHand, id, "mainhand", failures))
        eq.setItemInOffHand(item(el.equipment.offHand, id, "offhand", failures))

        val name = el.customName
        if (name.isNullOrBlank()) {
            @Suppress("DEPRECATION") s.setCustomName(null)
            s.isCustomNameVisible = false
        } else {
            @Suppress("DEPRECATION") s.setCustomName(legacy.serialize(mm.deserialize(name)))
            s.isCustomNameVisible = true
        }
    }

    private fun applyItemDisplay(d: ItemDisplay, el: DisplayElement, failures: MutableList<ItemFailure>) {
        val ref = el.item
        val stack = ref?.let { resolve(it) }
        if (ref != null && stack == null) failures += ItemFailure(el.localId, "item", ref.id)
        d.setItemStack(stack)
        applyDisplayCommon(d, el)
    }

    private fun applyBlockDisplay(d: BlockDisplay, el: DisplayElement) {
        runCatching { Bukkit.createBlockData(el.payload) }.getOrNull()?.let { d.block = it }
        applyDisplayCommon(d, el)
    }

    private fun applyTextDisplay(d: TextDisplay, el: DisplayElement) {
        @Suppress("DEPRECATION") d.setText(legacy.serialize(mm.deserialize(el.payload)))
        applyDisplayCommon(d, el)
    }

    private fun applyDisplayCommon(d: Display, el: DisplayElement) {
        d.transformation = el.transform.toBukkit()
        d.billboard = runCatching { Display.Billboard.valueOf(el.billboard) }.getOrDefault(Display.Billboard.FIXED)
        d.viewRange = el.viewRange
        if (el.brightnessBlock != null && el.brightnessSky != null) {
            d.brightness = Display.Brightness(el.brightnessBlock!!.coerceIn(0, 15), el.brightnessSky!!.coerceIn(0, 15))
        }
        if (el.glowColor != null) {
            d.isGlowing = true
            d.glowColorOverride = Color.fromARGB(el.glowColor!!)
        } else {
            d.isGlowing = false
        }
    }

    private fun Transform.toBukkit() = Transformation(vec3f(translation), quatf(leftRotation), vec3f(scale), quatf(rightRotation))
    private fun vec3f(v: Vec3) = Vector3f(v.x.toFloat(), v.y.toFloat(), v.z.toFloat())
    private fun quatf(q: Quat) = Quaternionf(q.x.toFloat(), q.y.toFloat(), q.z.toFloat(), q.w.toFloat())
}
