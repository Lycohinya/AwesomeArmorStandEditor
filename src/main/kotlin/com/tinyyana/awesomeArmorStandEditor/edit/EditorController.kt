package com.tinyyana.awesomeArmorStandEditor.edit

import com.tinyyana.awesomeArmorStandEditor.AwesomeArmorStandEditorPlugin
import com.tinyyana.awesomeArmorStandEditor.api.AaseScenePlaceEvent
import com.tinyyana.awesomeArmorStandEditor.api.AaseSceneSaveEvent
import com.tinyyana.awesomeArmorStandEditor.export.McFunctionExporter
import com.tinyyana.awesomeArmorStandEditor.export.SummonExporter
import com.tinyyana.awesomeArmorStandEditor.integration.LycoLibHook
import com.tinyyana.awesomeArmorStandEditor.model.Anchor
import com.tinyyana.awesomeArmorStandEditor.model.Animation
import com.tinyyana.awesomeArmorStandEditor.model.ArmorStandElement
import com.tinyyana.awesomeArmorStandEditor.model.DisplayElement
import com.tinyyana.awesomeArmorStandEditor.model.DisplayKind
import com.tinyyana.awesomeArmorStandEditor.model.Element
import com.tinyyana.awesomeArmorStandEditor.model.Keyframe
import com.tinyyana.awesomeArmorStandEditor.model.ParticleEmitter
import com.tinyyana.awesomeArmorStandEditor.model.Scene
import com.tinyyana.awesomeArmorStandEditor.model.Vec3
import com.tinyyana.awesomeArmorStandEditor.recall.CloseDecision
import com.tinyyana.awesomeArmorStandEditor.session.EditMode
import com.tinyyana.awesomeArmorStandEditor.session.EditSession
import com.tinyyana.awesomeArmorStandEditor.session.UndoSnapshot
import com.tinyyana.awesomeArmorStandEditor.store.ItemCodec
import com.tinyyana.awesomeArmorStandEditor.store.ShareCode
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Shared editing operations, used by both the command and the in-world tool listener so the two
 * entry points never diverge. All player messaging goes through Texts; all placement through
 * PlacementService. Runs on the main thread (called from command/event handlers).
 */
class EditorController(private val plugin: AwesomeArmorStandEditorPlugin) {

    private val armorStandModes = listOf(EditMode.POSE, EditMode.MOVE)
    private val displayModes = listOf(EditMode.TRANSLATE, EditMode.ROTATE, EditMode.SCALE, EditMode.MOVE)

    // --- creation ---

    fun addStand(player: Player) {
        val session = plugin.sessions.get(player.uniqueId) ?: return noSession(player)
        if (!checkAddAllowed(player)) return
        val origin = ensureOrigin(session, player)
        val element = ArmorStandElement(localId = session.scene.nextLocalId(), offset = offsetOf(player, origin), yaw = player.location.yaw)
        finishAdd(player, session, element, EditMode.POSE, "add.stand")
    }

    fun addDisplay(player: Player, kind: DisplayKind, payload: String) {
        val session = plugin.sessions.get(player.uniqueId) ?: return noSession(player)
        if (!checkAddAllowed(player)) return
        val origin = ensureOrigin(session, player)
        val element = DisplayElement(
            localId = session.scene.nextLocalId(), offset = offsetOf(player, origin), yaw = player.location.yaw,
            kind = kind, payload = payload,
        )
        finishAdd(player, session, element, EditMode.TRANSLATE, "add.display")
    }

    private fun finishAdd(player: Player, session: EditSession, element: Element, mode: EditMode, msgKey: String) {
        val origin = session.origin ?: return
        val entity = plugin.placement.spawn(origin, session.scene.id, element, player.uniqueId, session.placementId, session.scene.name)
        session.scene.elements += element
        session.entities[element.localId] = entity
        session.selectedLocalId = element.localId
        session.mode = mode
        session.dirty = true
        plugin.texts.send(player, msgKey, "id" to element.localId.toString())
        readout(player, session)
    }

    // --- selection ---

    fun select(player: Player, entity: Entity): Boolean {
        val session = plugin.sessions.get(player.uniqueId) ?: return false
        val tag = plugin.registry.read(entity) ?: return false
        if (tag.emitter) return false
        // Same saved scene is not enough: another placed copy shares the scene id. A tagged entity
        // belongs to this session only if it carries this session's placement.
        if (tag.sceneId != session.scene.id || tag.placement != session.placementId) {
            plugin.texts.send(player, "select.other-scene")
            return false
        }
        val element = session.scene.elements.find { it.localId == tag.localId } ?: return false
        session.selectedLocalId = element.localId
        session.entities[element.localId] = entity
        session.mode = if (element is ArmorStandElement) EditMode.POSE else EditMode.TRANSLATE
        readout(player, session)
        return true
    }

    /**
     * Selects an element by localId or next/prev cycling. Displays have no hitbox to
     * right-click and `/aase edit` binds whatever entity is nearest, so a scene mixing
     * stands and displays needs a deterministic, distance-free way to move the selection.
     */
    fun selectById(player: Player, arg: String) = withSession(player) { s ->
        val target = SelectOps.resolve(s.scene.elements.map { it.localId }, s.selectedLocalId, arg)
            ?: return@withSession plugin.texts.send(
                player, "select.bad-id",
                "ids" to s.scene.elements.joinToString(", ") { "#" + it.localId },
            )
        val element = s.scene.elements.first { it.localId == target }
        s.selectedLocalId = target
        s.mode = if (element is ArmorStandElement) EditMode.POSE else EditMode.TRANSLATE
        plugin.texts.send(player, "select.done", "id" to target.toString(), "type" to typeLabel(element))
        readout(player, s)
    }

    // --- adjustment ---

    fun adjust(player: Player, direction: Int) {
        val session = plugin.sessions.get(player.uniqueId) ?: return noSession(player)
        val element = session.selected() ?: return plugin.texts.send(player, "select.none")
        val entity = session.entities[element.localId] ?: return
        val step = currentStep(session) * direction

        // Snapshot before mutating so a single undo can restore this exact state.
        session.undoSnapshot = snapshotOf(element)

        when {
            element is ArmorStandElement && session.mode == EditMode.POSE -> {
                element.pose = PoseOps.setPart(element.pose, session.part, PoseOps.nudge(PoseOps.getPart(element.pose, session.part), session.axis, step))
                plugin.placement.apply(entity, element)
            }
            session.mode == EditMode.MOVE -> {
                val origin = session.origin ?: return
                val from = plugin.placement.elementLocation(origin, element)
                val target = from.clone().add(
                    if (session.axis == Axis.X) step else 0.0,
                    if (session.axis == Axis.Y) step else 0.0,
                    if (session.axis == Axis.Z) step else 0.0,
                )
                // Only probe when the element crosses into a new block — sub-block nudges can't
                // leave a claim, and each probe fires a synthetic event other plugins may log.
                val crossed = target.blockX != from.blockX || target.blockY != from.blockY || target.blockZ != from.blockZ
                if (crossed && !checkRegion(player, listOf(target))) return
                element.moveOffset(session.axis, step)
                entity.teleport(target)
            }
            element is DisplayElement && session.mode == EditMode.TRANSLATE -> {
                element.transform = TransformOps.translate(element.transform, session.axis, step)
                plugin.placement.apply(entity, element)
            }
            element is DisplayElement && session.mode == EditMode.ROTATE -> {
                element.transform = TransformOps.rotate(element.transform, session.axis, step)
                plugin.placement.apply(entity, element)
            }
            element is DisplayElement && session.mode == EditMode.SCALE -> {
                element.transform = TransformOps.scaleAxis(element.transform, session.axis, step)
                plugin.placement.apply(entity, element)
            }
            else -> return
        }
        session.dirty = true
        sound(player, Sound.UI_BUTTON_CLICK, 0.4f, 1.0f)
        readout(player, session)
    }

    private fun Element.moveOffset(axis: Axis, delta: Double) {
        val o = offset
        val n = when (axis) {
            Axis.X -> o.copy(x = o.x + delta)
            Axis.Y -> o.copy(y = o.y + delta)
            Axis.Z -> o.copy(z = o.z + delta)
        }
        when (this) {
            is ArmorStandElement -> this.offset = n
            is DisplayElement -> this.offset = n
        }
    }

    // --- cycling ---

    fun cycleAxis(player: Player, dir: Int) = withSession(player) { s ->
        s.axis = Axis.entries[(s.axis.ordinal + dir).mod(Axis.entries.size)]
        sound(player, Sound.BLOCK_NOTE_BLOCK_HAT, 0.4f, 1.2f)
        readout(player, s)
    }

    fun cyclePart(player: Player, dir: Int) = withSession(player) { s ->
        s.part = BodyPart.entries[(s.part.ordinal + dir).mod(BodyPart.entries.size)]
        sound(player, Sound.BLOCK_NOTE_BLOCK_HAT, 0.4f, 1.0f)
        readout(player, s)
    }

    fun cycleStep(player: Player, dir: Int) = withSession(player) { s ->
        when (currentStepFamily(s)) {
            StepFamily.ROTATION -> s.rotStepIndex += dir
            StepFamily.TRANSLATE -> s.transStepIndex += dir
            StepFamily.SCALE -> s.scaleStepIndex += dir
        }
        sound(player, Sound.BLOCK_NOTE_BLOCK_HAT, 0.4f, 0.8f)
        readout(player, s)
    }

    fun cycleMode(player: Player, dir: Int) = withSession(player) { s ->
        val element = s.selected() ?: return@withSession
        val modes = if (element is ArmorStandElement) armorStandModes else displayModes
        val idx = modes.indexOf(s.mode).coerceAtLeast(0)
        s.mode = modes[(idx + dir).mod(modes.size)]
        sound(player, Sound.BLOCK_NOTE_BLOCK_HAT, 0.4f, 1.4f)
        readout(player, s)
    }

    fun deleteSelected(player: Player) {
        val session = plugin.sessions.get(player.uniqueId) ?: return noSession(player)
        val elementId = session.selectedLocalId ?: return plugin.texts.send(player, "select.none")
        deleteElement(player, elementId)
    }

    /**
     * Deletes the exact element the confirmation screen (or a chat button) named, never whatever
     * became selected later. World first: the entity is looked up by session binding, then by
     * (placement, localId) in the registry, then by (placement, localId) within 2 blocks of where
     * the origin says it stands. Never an untagged entity: that would be another 1.1.0 copy. If none is found the model still drops the
     * element, and the reply says plainly that nothing was removed from the world.
     */
    fun deleteElement(player: Player, localId: Int) = withSession(player) { s ->
        val element = s.scene.elements.firstOrNull { it.localId == localId }
            ?: return@withSession plugin.texts.send(player, "delete.changed")
        val entity = findElementEntity(s, element)
        if (entity != null) plugin.recall.removeEntity(entity, closeSessions = false)
        // A recall preview offered "delete just this one" alongside "recall all"; once one is
        // chosen the other must not still be confirmable.
        plugin.recall.dropPending(player.uniqueId)
        s.entities.remove(element.localId)
        s.scene.elements.removeIf { it.localId == element.localId }
        if (s.selectedLocalId == element.localId) s.selectedLocalId = null
        s.dirty = true
        val save = plugin.texts.button("delete.button-save", "/aase save")
        if (entity != null) {
            plugin.texts.sendWithButtons(player, "delete.ok", listOf(save), "id" to element.localId.toString())
        } else {
            plugin.texts.send(player, "delete.not-in-world", "id" to element.localId.toString())
            plugin.texts.sendButtons(
                player,
                listOf(save, plugin.texts.button("delete.button-remove-look", "/aase remove look")),
            )
        }
    }

    private fun findElementEntity(s: EditSession, element: Element): Entity? {
        s.entities[element.localId]?.takeIf { it.isValid }?.let { return it }
        for (id in plugin.registry.byPlacement(s.placementId)) {
            val e = plugin.registry.resolve(id) ?: continue
            val t = plugin.registry.read(e) ?: continue
            if (!t.emitter && t.placement == s.placementId && t.localId == element.localId) return e
        }
        val origin = s.origin ?: return null
        val expected = plugin.placement.elementLocation(origin, element)
        val world = expected.world ?: return null
        return world.getNearbyEntities(expected, 2.0, 2.0, 2.0)
            .filter { e ->
                val t = plugin.registry.read(e)
                t != null && !t.emitter && t.sceneId == s.scene.id && t.localId == element.localId &&
                    // Only this copy: every session entity carries the session's placement (new
                    // placements are stamped at spawn, 1.1.0 copies at /aase edit), so an untagged
                    // entity here can only be another legacy copy.
                    t.placement == s.placementId &&
                    e.location.distanceSquared(expected) <= 4.0
            }
            .minByOrNull { it.location.distanceSquared(expected) }
    }

    // --- scene lifecycle ---

    fun openNew(player: Player, name: String) {
        if (blockedByUnsaved(player)) return
        val scene = Scene(id = UUID.randomUUID().toString(), owner = player.uniqueId.toString(), name = name)
        val session = reopen(player, scene)
        session.origin = player.location.clone()
        plugin.texts.send(player, "scene.new", "name" to name)
    }

    /**
     * Swap the player onto a new session. Playback bound to the old session must stop first —
     * the scheduler task holds the old session in its closure, so dropping the session without
     * stopping leaves an orphaned task posing the old entities forever.
     */
    private fun reopen(player: Player, scene: Scene, placementId: String = UUID.randomUUID().toString()): EditSession {
        plugin.sessions.get(player.uniqueId)?.let { plugin.animation.stop(it) }
        plugin.sessions.close(player.uniqueId)
        return plugin.sessions.open(player.uniqueId, scene, placementId)
    }

    fun save(player: Player) = withSession(player) { s ->
        s.origin?.let { o -> s.scene.lastAnchor = Anchor(o.world?.name ?: "", o.x, o.y, o.z) }
        plugin.store.save(s.scene)
        s.dirty = false
        LycoLibHook.audit(plugin.name, player.name, "scene.save", "name=${s.scene.name} elements=${s.scene.elements.size}")
        plugin.server.pluginManager.callEvent(AaseSceneSaveEvent(player, s.scene))
        plugin.texts.send(player, "scene.saved", "name" to s.scene.name, "count" to s.scene.elements.size.toString())
    }

    /** Places a fresh instance of a saved blueprint at the player. */
    fun loadFresh(player: Player, name: String) {
        if (blockedByUnsaved(player)) return
        val scene = plugin.store.loadByName(player.uniqueId, name)
            ?: return plugin.texts.send(player, "scene.not-found", "name" to name)
        val origin = player.location.clone()
        if (!checkLimits(player, scene.elements.size)) return
        if (!checkRegion(player, scenePoints(scene, origin))) return
        if (!firePlace(player, scene, origin)) return plugin.texts.send(player, "share.place-cancelled")
        val session = reopen(player, scene)
        plugin.placement.placeAll(session, origin, player.uniqueId)
        // Fresh sessions start unselected; pick the first element so setequip/flag/pose
        // work right after load without hunting for the edit tool first.
        session.selectedLocalId = scene.elements.firstOrNull()?.localId
        for (emitter in scene.emitters) {
            plugin.particles.spawnEmitter(origin, scene.id, player.uniqueId, emitter, session.placementId, scene.name)
        }
        plugin.texts.send(player, "scene.loaded", "name" to name, "count" to scene.elements.size.toString())
    }

    /** Fires the cancellable place event; returns false if a third-party plugin vetoed it. */
    private fun firePlace(player: Player, scene: Scene, origin: Location): Boolean =
        !AaseScenePlaceEvent(player, scene, origin).also { plugin.server.pluginManager.callEvent(it) }.isCancelled

    /**
     * Re-bind a session to one placed copy the player is standing near (no duplicate spawn).
     *
     * Binding is by placement: only entities stamped with the target's placement join the session,
     * so editing one copy never moves another copy of the same saved scene. 1.1.0 entities have no
     * placement; they are grouped by owner + scene + position ([com.tinyyana.awesomeArmorStandEditor.recall.LegacyGrouping])
     * and stamped with a fresh placement here, after which they behave like new ones.
     *
     * Orphans (no save and no open session, or a localId the save no longer lists) and emitter
     * markers are never picked as the target.
     */
    fun editFromTarget(player: Player) {
        if (blockedByUnsaved(player)) return
        val range = plugin.settings.selectRange.toDouble()
        val savedCache = HashMap<Pair<UUID, String>, com.tinyyana.awesomeArmorStandEditor.recall.SceneIds?>()
        val nearby = player.getNearbyEntities(range, range, range)
            .filter { plugin.registry.isOurs(it) && !plugin.registry.isEmitterMarker(it) }
            .mapNotNull { e -> plugin.registry.read(e)?.let { e to it } }
        if (nearby.isEmpty()) return plugin.texts.send(player, "edit.no-target")
        val bindable = nearby.filter { (_, tag) -> !plugin.recall.isOrphan(tag, savedCache) }
        if (bindable.isEmpty()) {
            return plugin.texts.sendWithButtons(
                player, "edit.only-orphans",
                listOf(plugin.texts.button("edit.button-remove-look", "/aase remove look")),
            )
        }
        val (target, tag) = bindable.minBy { it.first.location.distanceSquared(player.eyeLocation) }
        if (tag.owner != player.uniqueId && !player.hasPermission("aase.admin")) {
            return plugin.texts.send(player, "edit.not-owner")
        }
        val scene = plugin.store.load(tag.owner, tag.sceneId)
            ?: return plugin.texts.sendWithButtons(
                player, "edit.no-scene",
                listOf(plugin.texts.button("edit.button-remove-look", "/aase remove look")),
            )
        val matched = scene.elements.find { it.localId == tag.localId }
            ?: return plugin.texts.send(player, "edit.no-scene")
        val origin = target.location.clone().subtract(matched.offset.x, matched.offset.y, matched.offset.z)

        // Members of this copy: its placement, or the legacy group around the target (migrated below).
        val placementId = tag.placement ?: UUID.randomUUID().toString()
        val members: List<Entity> = if (tag.placement != null) {
            plugin.recall.gather(plugin.registry.byPlacement(placementId), target.location).values
                .filter { it.second.placement == placementId }
                .map { it.first }
        } else {
            val ids = plugin.recall.legacyGroup(target, tag, scene)
            val group = ids.mapNotNull { id -> plugin.registry.resolve(id) }
            for (e in group) plugin.registry.migrate(e, placementId, scene.name)
            group
        }

        val session = reopen(player, scene, placementId)
        session.origin = origin
        for (e in members) {
            if (plugin.registry.isEmitterMarker(e)) continue
            val t = plugin.registry.read(e) ?: continue
            val el = scene.elements.find { it.localId == t.localId } ?: continue
            // One entity per localId; if a copy somehow holds two, keep the one where the origin
            // says it should stand so anim-stop's restore doesn't teleport the wrong one.
            val expected = plugin.placement.elementLocation(origin, el)
            val current = session.entities[t.localId]
            if (current == null || e.location.distanceSquared(expected) < current.location.distanceSquared(expected)) {
                session.entities[t.localId] = e
            }
        }
        session.selectedLocalId = tag.localId
        session.mode = if (matched is ArmorStandElement) EditMode.POSE else EditMode.TRANSLATE
        plugin.texts.send(player, "edit.begin", "name" to scene.name)
        readout(player, session)
    }

    /**
     * `/aase close [save|discard]`. Unsaved changes are never dropped silently: without an argument
     * a dirty session stays open and the player picks from clickable choices.
     */
    fun close(player: Player, arg: String? = null) {
        val s = plugin.sessions.get(player.uniqueId)
        when (CloseDecision.decide(s != null, s?.dirty == true, arg)) {
            CloseDecision.Action.NO_SESSION -> noSession(player)
            CloseDecision.Action.USAGE -> plugin.texts.send(player, "usage.close")
            CloseDecision.Action.CLOSE -> endSession(player, "session.closed")
            CloseDecision.Action.ASK -> askUnsaved(player)
            CloseDecision.Action.SAVE_THEN_CLOSE -> {
                if (!player.hasPermission("aase.scene.save")) return plugin.texts.send(player, "system.no-permission")
                save(player)
                endSession(player, "session.closed")
            }
            CloseDecision.Action.DISCARD -> {
                val session = s ?: return
                plugin.animation.stop(session)
                plugin.sessions.close(player.uniqueId)
                val center = session.origin ?: player.location
                val removed = plugin.recall.discardPlacement(player, session.placementId, center)
                plugin.texts.send(player, "session.discarded", "count" to removed.toString())
                plugin.texts.send(player, "remove.loaded-only")
            }
        }
    }

    private fun askUnsaved(player: Player) {
        plugin.texts.send(player, "session.unsaved")
        plugin.texts.sendButtons(
            player,
            listOf(
                plugin.texts.button("session.button-save-close", "/aase close save"),
                plugin.texts.button("session.button-discard", "/aase close discard"),
                plugin.texts.button("session.button-continue", "/aase"),
            ),
        )
    }

    /**
     * new/load/import/edit replace the open session. Doing that silently over unsaved changes is
     * how a never-saved scene became an orphan, so it gets the same choice as /aase close.
     */
    private fun blockedByUnsaved(player: Player): Boolean {
        if (plugin.sessions.get(player.uniqueId)?.dirty != true) return false
        askUnsaved(player)
        return true
    }

    private fun endSession(player: Player, key: String) {
        plugin.sessions.get(player.uniqueId)?.let { plugin.animation.stop(it) }  // stop playback, restore entities
        plugin.sessions.close(player.uniqueId)
        plugin.texts.send(player, key)
    }

    // --- payload / name editing ---

    fun setDisplayPayload(player: Player, kind: DisplayKind, payload: String) = withSession(player) { s ->
        val el = s.selected()
        if (el !is DisplayElement || el.kind != kind) return@withSession plugin.texts.send(player, "payload.wrong-kind")
        el.payload = payload
        s.entities[el.localId]?.let { plugin.placement.apply(it, el) }
        s.dirty = true
        plugin.texts.send(player, "payload.set")
    }

    /** Sets the selected ITEM display's item to the player's off-hand item. */
    fun setDisplayItemFromOffhand(player: Player) = withSession(player) { s ->
        val el = s.selected()
        if (el !is DisplayElement || el.kind != DisplayKind.ITEM) return@withSession plugin.texts.send(player, "payload.wrong-kind")
        val off = player.inventory.itemInOffHand
        if (off.type.isAir) return@withSession plugin.texts.send(player, "payload.offhand-empty")
        el.payload = ItemCodec.encode(off)
        s.entities[el.localId]?.let { plugin.placement.apply(it, el) }
        s.dirty = true
        plugin.texts.send(player, "payload.set")
    }

    fun setName(player: Player, miniMessage: String?) = withSession(player) { s ->
        val el = s.selected()
        if (el !is ArmorStandElement) return@withSession plugin.texts.send(player, "name.only-stand")
        el.customName = miniMessage
        s.entities[el.localId]?.let { plugin.placement.apply(it, el) }
        s.dirty = true
        plugin.texts.send(player, "name.set")
    }

    /** Sets one armor-stand equipment slot from the player's off-hand (air clears it). */
    fun setEquip(player: Player, slot: String) = withSession(player) { s ->
        val el = s.selected()
        if (el !is ArmorStandElement) return@withSession plugin.texts.send(player, "equip.only-stand")
        val off = player.inventory.itemInOffHand
        val encoded = if (off.type.isAir) null else ItemCodec.encode(off)
        val eq = el.equipment
        // Empty off-hand still clears the slot (documented in MANUAL), but the reply must say
        // "cleared", not "set" — otherwise an empty off-hand reads as a silent success.
        val old = when (slot.lowercase()) {
            "head", "helmet" -> eq.head.also { eq.head = encoded }
            "chest", "chestplate" -> eq.chest.also { eq.chest = encoded }
            "legs", "leggings" -> eq.legs.also { eq.legs = encoded }
            "feet", "boots" -> eq.feet.also { eq.feet = encoded }
            "mainhand", "hand" -> eq.mainHand.also { eq.mainHand = encoded }
            "offhand" -> eq.offHand.also { eq.offHand = encoded }
            else -> return@withSession plugin.texts.send(player, "equip.bad-slot")
        }
        s.entities[el.localId]?.let { plugin.placement.apply(it, el) }
        s.dirty = true
        when {
            encoded != null -> plugin.texts.send(player, "equip.set", "slot" to slot)
            old != null -> plugin.texts.send(player, "equip.cleared", "slot" to slot)
            else -> plugin.texts.send(player, "equip.hint")
        }
    }

    fun toggleFlag(player: Player, flag: String) = withSession(player) { s ->
        val el = s.selected()
        if (el !is ArmorStandElement) return@withSession plugin.texts.send(player, "flag.only-stand")
        val f = el.flags
        when (flag.lowercase()) {
            "small" -> f.small = !f.small
            "invisible" -> f.invisible = !f.invisible
            "nobaseplate" -> f.noBasePlate = !f.noBasePlate
            "nogravity" -> f.noGravity = !f.noGravity
            "arms" -> f.arms = !f.arms
            "marker" -> f.marker = !f.marker
            "glowing" -> f.glowing = !f.glowing
            else -> return@withSession
        }
        s.entities[el.localId]?.let { plugin.placement.apply(it, el) }
        s.dirty = true
        plugin.texts.send(player, "flag.toggled", "flag" to flag)
    }

    fun selectedIsArmorStand(player: Player): Boolean =
        plugin.sessions.get(player.uniqueId)?.selected() is ArmorStandElement

    // --- export ---

    fun exportCommands(player: Player) = withSession(player) { s ->
        if (s.scene.elements.isEmpty()) return@withSession plugin.texts.send(player, "export.empty")
        val commands = SummonExporter.export(s.scene)
        val file = File(plugin.dataFolder, "exports/${sanitize(s.scene.name)}.txt")
        file.parentFile.mkdirs()
        file.writeText(commands, Charsets.UTF_8)
        val clickable = plugin.texts.component("export.click")
            .clickEvent(ClickEvent.copyToClipboard(commands))
        plugin.texts.sendComponent(player, clickable)
        plugin.texts.send(player, "export.saved", "path" to file.path)
        LycoLibHook.audit(plugin.name, player.name, "scene.export", "name=${s.scene.name}")
    }

    fun exportMcFunction(player: Player) = withSession(player) { s ->
        if (s.scene.elements.isEmpty()) return@withSession plugin.texts.send(player, "export.empty")
        val files = McFunctionExporter.export(s.scene, datapackReadme(s.scene.animation != null))
        val base = File(plugin.dataFolder, "exports/${sanitize(s.scene.name)}/datapack")
        for ((rel, content) in files) {
            val f = File(base, rel)
            f.parentFile.mkdirs()
            f.writeText(content, Charsets.UTF_8)
        }
        plugin.texts.send(player, "export.mcfunction-saved", "path" to base.path)
        LycoLibHook.audit(plugin.name, player.name, "scene.export-mcfunction", "name=${s.scene.name}")
    }

    private fun datapackReadme(animated: Boolean): String = buildString {
        fun line(key: String) = appendLine(plugin.texts.label(key).replace("{ns}", McFunctionExporter.NS))
        line("export-readme.head")
        if (animated) line("export-readme.anim")
        appendLine()
        line("export-readme.foot")
    }

    private fun sanitize(name: String) = name.replace(Regex("[^A-Za-z0-9_\\-]"), "_").ifBlank { "scene" }

    // --- share code / import (P4) ---

    /** Emits a copy-pasteable share code for the current scene. */
    fun shareCode(player: Player) = withSession(player) { s ->
        if (s.scene.elements.isEmpty() && s.scene.emitters.isEmpty()) return@withSession plugin.texts.send(player, "export.empty")
        val code = ShareCode.encode(s.scene)
        val clickable = plugin.texts.component("share.click").clickEvent(ClickEvent.copyToClipboard(code))
        plugin.texts.sendComponent(player, clickable)
        plugin.texts.send(player, "share.hint")
        LycoLibHook.audit(plugin.name, player.name, "scene.share", "name=${s.scene.name}")
    }

    /** Imports a share code as a new scene owned by the importer, placed at their feet. */
    fun importCode(player: Player, code: String, name: String?) {
        if (blockedByUnsaved(player)) return
        val decoded = ShareCode.decode(code)
            ?: return plugin.texts.send(player, "share.import-bad")
        if (!player.hasPermission("aase.bypass.limit") && decoded.elements.size > plugin.settings.limitPerPlayer) {
            return plugin.texts.send(player, "share.import-too-big", "max" to plugin.settings.limitPerPlayer.toString())
        }
        // Re-own: fresh id + importer as owner + optional rename; everything else carried over.
        val scene = decoded.copy(
            id = UUID.randomUUID().toString(),
            owner = player.uniqueId.toString(),
            name = name ?: decoded.name,
            lastAnchor = null,
        )
        val origin = player.location.clone()
        if (!checkLimits(player, scene.elements.size)) return
        if (!checkRegion(player, scenePoints(scene, origin))) return
        if (!firePlace(player, scene, origin)) return plugin.texts.send(player, "share.place-cancelled")
        val session = reopen(player, scene)
        plugin.placement.placeAll(session, origin, player.uniqueId)
        session.selectedLocalId = scene.elements.firstOrNull()?.localId
        for (emitter in scene.emitters) {
            plugin.particles.spawnEmitter(origin, scene.id, player.uniqueId, emitter, session.placementId, scene.name)
        }
        session.dirty = true
        LycoLibHook.audit(plugin.name, player.name, "scene.import", "name=${scene.name} elements=${scene.elements.size}")
        plugin.texts.send(player, "share.imported", "name" to scene.name, "count" to scene.elements.size.toString())
    }

    // --- info ---

    /** Chat summary of the current scene: counts, animation, selection, save state. */
    fun info(player: Player) = withSession(player) { s ->
        val sc = s.scene
        plugin.texts.send(player, "info.header", "name" to sc.name)
        plugin.texts.send(
            player, "info.elements",
            "count" to sc.elements.size.toString(),
            "stands" to sc.elements.count { it is ArmorStandElement }.toString(),
            "displays" to sc.elements.count { it is DisplayElement }.toString(),
        )
        plugin.texts.send(player, "info.emitters", "count" to sc.emitters.size.toString())
        sc.animation?.let { a ->
            plugin.texts.send(
                player, "info.anim",
                "len" to a.lengthTicks.toString(),
                "tracks" to a.tracks.size.toString(),
                "loop" to plugin.texts.label(if (a.loop) "label.on" else "label.off"),
            )
        }
        s.selected()?.let { sel ->
            plugin.texts.send(
                player, "info.selected",
                "id" to sel.localId.toString(),
                "type" to typeLabel(sel),
            )
        }
        plugin.texts.send(player, "info.dirty", "state" to plugin.texts.label(if (s.dirty) "label.unsaved" else "label.saved"))
    }

    // --- equipment menu accessors ---

    /** Current equipment (slot -> item base64 or null) for the selected stand, or null if none selected. */
    fun equipmentSnapshot(player: Player): Map<String, String?>? {
        val el = plugin.sessions.get(player.uniqueId)?.selected() as? ArmorStandElement ?: return null
        return linkedMapOf(
            "head" to el.equipment.head, "chest" to el.equipment.chest, "legs" to el.equipment.legs,
            "feet" to el.equipment.feet, "mainhand" to el.equipment.mainHand, "offhand" to el.equipment.offHand,
        )
    }

    /** Sets one equipment slot from a given item (null clears it). Used by the equipment GUI. */
    fun setEquipItem(player: Player, slot: String, item: ItemStack?): Boolean {
        val s = plugin.sessions.get(player.uniqueId) ?: return false
        val el = s.selected() as? ArmorStandElement ?: return false
        val encoded = item?.takeIf { !it.type.isAir }?.let { ItemCodec.encode(it) }
        when (slot) {
            "head" -> el.equipment.head = encoded
            "chest" -> el.equipment.chest = encoded
            "legs" -> el.equipment.legs = encoded
            "feet" -> el.equipment.feet = encoded
            "mainhand" -> el.equipment.mainHand = encoded
            "offhand" -> el.equipment.offHand = encoded
            else -> return false
        }
        s.entities[el.localId]?.let { plugin.placement.apply(it, el) }
        s.dirty = true
        return true
    }

    // --- particles (P2) ---

    fun addEmitter(player: Player, particleType: String) = withSession(player) { s ->
        val type = particleType.uppercase()
        if (runCatching { Particle.valueOf(type) }.isFailure) {
            return@withSession plugin.texts.send(player, "particle.invalid", "type" to particleType)
        }
        if (!checkAddAllowed(player)) return@withSession
        val origin = ensureOrigin(s, player)
        val emitter = ParticleEmitter(id = s.scene.nextEmitterId(), particle = type, offset = offsetOf(player, origin))
        s.scene.emitters += emitter
        plugin.particles.spawnEmitter(origin, s.scene.id, player.uniqueId, emitter, s.placementId, s.scene.name)
        s.dirty = true
        plugin.texts.send(player, "particle.added", "type" to type)
    }

    fun clearEmitters(player: Player) = withSession(player) { s ->
        plugin.particles.removeForPlacement(s.placementId)
        s.scene.emitters.clear()
        s.dirty = true
        plugin.texts.send(player, "particle.cleared")
    }

    // --- keyframe animation (P3) ---

    private fun anim(s: EditSession): Animation = s.scene.animation ?: Animation().also { s.scene.animation = it }

    fun animKey(player: Player, tick: Int) = withSession(player) { s ->
        val el = s.selected() ?: return@withSession plugin.texts.send(player, "select.none")
        val track = anim(s).track(el.localId)
        val kf = when (el) {
            is ArmorStandElement -> Keyframe(tick, pose = el.pose, offset = el.offset)
            is DisplayElement -> Keyframe(tick, transform = el.transform, offset = el.offset)
        }
        track.keyframes.removeIf { it.tick == tick }
        track.keyframes += kf
        if (tick > anim(s).lengthTicks) anim(s).lengthTicks = tick
        s.dirty = true
        plugin.texts.send(player, "anim.key", "tick" to tick.toString(), "id" to el.localId.toString())
    }

    fun animLength(player: Player, ticks: Int) = withSession(player) { s ->
        anim(s).lengthTicks = ticks.coerceAtLeast(1)
        s.dirty = true
        plugin.texts.send(player, "anim.length", "ticks" to anim(s).lengthTicks.toString())
    }

    fun animToggleLoop(player: Player) = withSession(player) { s ->
        val a = anim(s); a.loop = !a.loop; s.dirty = true
        plugin.texts.send(player, if (a.loop) "anim.loop-on" else "anim.loop-off")
    }

    fun animPlay(player: Player) = withSession(player) { s ->
        if (plugin.animation.play(s)) plugin.texts.send(player, "anim.playing")
        else plugin.texts.send(player, "anim.empty")
    }

    fun animStop(player: Player) = withSession(player) { s ->
        plugin.animation.stop(s)
        plugin.texts.send(player, "anim.stopped")
    }

    fun animClear(player: Player) = withSession(player) { s ->
        plugin.animation.stop(s)
        s.scene.animation = null
        s.dirty = true
        plugin.texts.send(player, "anim.cleared")
    }

    // --- presets (one-click, for non-artists) ---

    fun applyPose(player: Player, presetId: String) = withSession(player) { s ->
        val el = s.selected()
        if (el !is ArmorStandElement) return@withSession plugin.texts.send(player, "preset.only-stand")
        val preset = plugin.presets.pose(presetId) ?: return@withSession plugin.texts.send(player, "preset.pose-missing", "id" to presetId)
        el.pose = preset.pose
        preset.arms?.let { el.flags.arms = it }
        s.entities[el.localId]?.let { plugin.placement.apply(it, el) }
        s.dirty = true
        plugin.texts.send(player, "preset.pose-applied", "name" to plugin.texts.presetName(preset.id, preset.name))
    }

    fun applyFx(player: Player, presetId: String) = withSession(player) { s ->
        val preset = plugin.presets.fx(presetId) ?: return@withSession plugin.texts.send(player, "preset.fx-missing", "id" to presetId)
        if (!checkLimits(player, preset.emitters.size)) return@withSession
        val origin = ensureOrigin(s, player)
        // Centre the effect on the selected element if there is one, else on the player.
        val base = s.selected()?.offset ?: offsetOf(player, origin)
        // The selected element may sit far from the player, so probe where the emitters land.
        val points = preset.emitters
            .map { origin.clone().add(base.x + it.offset.x, base.y + it.offset.y, base.z + it.offset.z) }
            .distinctBy { Triple(it.blockX, it.blockY, it.blockZ) }
        if (!checkRegion(player, points)) return@withSession
        for (t in preset.emitters) {
            val emitter = ParticleEmitter(
                id = s.scene.nextEmitterId(), particle = t.particle,
                offset = Vec3(base.x + t.offset.x, base.y + t.offset.y, base.z + t.offset.z),
                count = t.count, spread = t.spread, speed = t.speed, rateTicks = t.rate,
            )
            s.scene.emitters += emitter
            plugin.particles.spawnEmitter(origin, s.scene.id, player.uniqueId, emitter, s.placementId, s.scene.name)
        }
        s.dirty = true
        plugin.texts.send(player, "preset.fx-applied", "name" to plugin.texts.presetName(preset.id, preset.name))
    }

    /** Make the pose symmetric by mirroring the left arm/leg onto the right (or vice versa). */
    fun mirrorPose(player: Player) = withSession(player) { s ->
        val el = s.selected()
        if (el !is ArmorStandElement) return@withSession plugin.texts.send(player, "preset.only-stand")
        val p = el.pose
        el.pose = p.copy(
            rightArm = p.leftArm.copy(y = -p.leftArm.y, z = -p.leftArm.z),
            rightLeg = p.leftLeg.copy(y = -p.leftLeg.y, z = -p.leftLeg.z),
        )
        s.entities[el.localId]?.let { plugin.placement.apply(it, el) }
        s.dirty = true
        plugin.texts.send(player, "preset.mirrored")
    }

    /** Save the selected armor stand's current pose into presets.yml so it can be reused. */
    fun savePose(player: Player, id: String, name: String?) = withSession(player) { s ->
        val el = s.selected()
        if (el !is ArmorStandElement) return@withSession plugin.texts.send(player, "preset.only-stand")
        val file = File(plugin.dataFolder, "presets.yml")
        val cfg = YamlConfiguration.loadConfiguration(file)
        val list = cfg.getMapList("poses").toMutableList()
        list.removeIf { it["id"]?.toString() == id }
        fun deg(e: com.tinyyana.awesomeArmorStandEditor.model.EulerXYZ) = listOf(
            round1(Math.toDegrees(e.x)), round1(Math.toDegrees(e.y)), round1(Math.toDegrees(e.z)),
        )
        list += linkedMapOf(
            "id" to id, "name" to (name ?: id), "icon" to "ARMOR_STAND", "arms" to el.flags.arms,
            "head" to deg(el.pose.head), "body" to deg(el.pose.body),
            "left-arm" to deg(el.pose.leftArm), "right-arm" to deg(el.pose.rightArm),
            "left-leg" to deg(el.pose.leftLeg), "right-leg" to deg(el.pose.rightLeg),
        )
        cfg.set("poses", list)
        cfg.save(file)
        plugin.presets.reload(plugin)
        plugin.texts.send(player, "preset.pose-saved", "id" to id)
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0

    // --- readout ---

    fun readout(player: Player, session: EditSession) {
        val element = session.selected()
        if (element == null) {
            plugin.texts.actionbarRaw(player, plugin.texts.label("readout.none"))
            return
        }
        val stepStr = formatStep(session)
        val detail = valueReadout(session, element)
        plugin.texts.actionbarRaw(
            player,
            "<gray>${typeLabel(element)}<white>#${element.localId} <dark_gray>| <aqua>${modeLabel(session.mode)}" +
                partLabel(session, element) +
                " <dark_gray>| ${plugin.texts.label("label.axis")} <yellow>${session.axis.name}" +
                " <dark_gray>| ${plugin.texts.label("label.step")} <green>$stepStr" +
                (if (detail.isNotEmpty()) " <dark_gray>| <gray>$detail" else ""),
        )
    }

    private fun typeLabel(element: Element): String =
        plugin.texts.label(if (element is ArmorStandElement) "label.armorstand" else "label.display")

    private fun modeLabel(mode: EditMode) = plugin.texts.label("mode.${mode.name.lowercase()}")

    private fun partLabel(session: EditSession, element: Element): String =
        if (element is ArmorStandElement && session.mode == EditMode.POSE) {
            " <white>${plugin.texts.label("part.${session.part.name.lowercase()}")}"
        } else {
            ""
        }

    private fun valueReadout(session: EditSession, element: Element): String = when {
        element is ArmorStandElement && session.mode == EditMode.POSE -> {
            val e = PoseOps.getPart(element.pose, session.part)
            "%.0f°".format(Locale.ROOT, PoseOps.axisValueDeg(e, session.axis))
        }
        session.mode == EditMode.MOVE -> {
            val o = element.offset
            "off %.2f/%.2f/%.2f".format(Locale.ROOT, o.x, o.y, o.z)
        }
        element is DisplayElement && session.mode == EditMode.SCALE -> {
            val s = element.transform.scale
            "scale %.2f/%.2f/%.2f".format(Locale.ROOT, s.x, s.y, s.z)
        }
        element is DisplayElement && session.mode == EditMode.TRANSLATE -> {
            val t = element.transform.translation
            "t %.2f/%.2f/%.2f".format(Locale.ROOT, t.x, t.y, t.z)
        }
        else -> ""
    }

    // --- helpers ---

    private enum class StepFamily { ROTATION, TRANSLATE, SCALE }

    private fun currentStepFamily(session: EditSession): StepFamily = when (session.mode) {
        EditMode.POSE, EditMode.ROTATE -> StepFamily.ROTATION
        EditMode.MOVE, EditMode.TRANSLATE -> StepFamily.TRANSLATE
        EditMode.SCALE -> StepFamily.SCALE
    }

    private fun currentStep(session: EditSession): Double {
        val s = plugin.settings
        return when (currentStepFamily(session)) {
            StepFamily.ROTATION -> pick(s.rotationStepsDeg, session.rotStepIndex)
            StepFamily.TRANSLATE -> pick(s.translateSteps, session.transStepIndex)
            StepFamily.SCALE -> pick(s.scaleSteps, session.scaleStepIndex)
        }
    }

    private fun formatStep(session: EditSession): String {
        val v = currentStep(session)
        return if (currentStepFamily(session) == StepFamily.ROTATION) "%.0f°".format(Locale.ROOT, v)
        else "%.2f".format(Locale.ROOT, v)
    }

    private fun pick(list: List<Double>, index: Int): Double = list[index.mod(list.size)]

    /** Quantity caps. [adding] is how many new entities the operation would spawn. */
    private fun checkLimits(player: Player, adding: Int): Boolean {
        if (player.hasPermission("aase.bypass.limit")) return true
        val s = plugin.settings
        if (plugin.registry.ownerCount(player.uniqueId) + adding > s.limitPerPlayer) {
            plugin.texts.send(player, "limit.per-player", "max" to s.limitPerPlayer.toString()); return false
        }
        if (plugin.registry.total() + adding > s.limitGlobal) {
            plugin.texts.send(player, "limit.global"); return false
        }
        if (plugin.registry.countInChunk(player.location.chunk) + adding > s.limitPerChunk) {
            plugin.texts.send(player, "limit.per-chunk", "max" to s.limitPerChunk.toString()); return false
        }
        return true
    }

    /**
     * Land-protection gate: every block our entities would occupy must be buildable by [player].
     *
     * We spawn entities with world.spawn(), which fires no vanilla placement event — a land plugin
     * never sees our writes and cannot veto them on its own. This guard is the only thing standing
     * between a player and someone else's claim, so it must run at *every* site that spawns or
     * teleports an element.
     */
    private fun checkRegion(player: Player, points: Collection<Location>): Boolean {
        if (player.hasPermission("aase.bypass.region")) return true
        if (points.all { plugin.guard.canBuild(player, it) }) return true
        plugin.texts.send(player, "region.denied")
        return false
    }

    /**
     * One probe per distinct block the scene reaches. See [ScenePoints] for what counts.
     *
     * ponytail: each probe reads Location.block, which loads that chunk if it isn't already. Real
     * scenes sit around the player so this is a no-op; a scene with a keyframe hundreds of blocks
     * out would sync-load a chunk on an explicit /aase load. Batch by chunk if that ever bites.
     */
    private fun scenePoints(scene: Scene, origin: Location): List<Location> =
        ScenePoints.offsets(scene)
            .map { origin.clone().add(it.x, it.y, it.z) }
            .distinctBy { Triple(it.blockX, it.blockY, it.blockZ) }

    /** Gate for adding one element at the player's own feet. */
    private fun checkAddAllowed(player: Player): Boolean =
        checkLimits(player, 1) && checkRegion(player, listOf(player.location))

    private fun ensureOrigin(session: EditSession, player: Player) =
        session.origin ?: player.location.clone().also { session.origin = it }

    private fun offsetOf(player: Player, origin: org.bukkit.Location): Vec3 {
        val l = player.location
        return Vec3(l.x - origin.x, l.y - origin.y, l.z - origin.z)
    }

    private inline fun withSession(player: Player, block: (EditSession) -> Unit) {
        val s = plugin.sessions.get(player.uniqueId) ?: return noSession(player)
        block(s)
    }

    private fun noSession(player: Player) = plugin.texts.send(player, "session.none")

    // --- undo ---

    /** Restore the last-adjusted element to its pre-adjust state. One slot only: consumed on use. */
    fun undo(player: Player) = withSession(player) { s ->
        val snap = s.undoSnapshot ?: return@withSession run {
            sound(player, Sound.ENTITY_VILLAGER_NO, 0.5f, 1.0f)
            plugin.texts.send(player, "undo.none")
        }
        s.undoSnapshot = null
        val element = s.scene.elements.find { it.localId == snap.localId }
            ?: return@withSession plugin.texts.send(player, "undo.none")
        when (element) {
            is ArmorStandElement -> {
                snap.pose?.let { element.pose = it }
                element.offset = snap.offset
            }
            is DisplayElement -> {
                snap.transform?.let { element.transform = it }
                element.offset = snap.offset
            }
        }
        val entity = s.entities[element.localId]
        if (entity != null) {
            plugin.placement.apply(entity, element)
            s.origin?.let { entity.teleport(plugin.placement.elementLocation(it, element)) }
        }
        s.dirty = true
        sound(player, Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 1.2f)
        plugin.texts.send(player, "undo.ok")
        readout(player, s)
    }

    fun hasUndo(player: Player): Boolean =
        plugin.sessions.get(player.uniqueId)?.undoSnapshot != null

    private fun snapshotOf(element: Element): UndoSnapshot = when (element) {
        is ArmorStandElement -> UndoSnapshot(element.localId, element.pose, null, element.offset)
        is DisplayElement -> UndoSnapshot(element.localId, null, element.transform, element.offset)
    }

    // --- select next/prev (for GUI and tool) ---

    fun selectNext(player: Player) = withSession(player) { s ->
        val ids = s.scene.elements.map { it.localId }
        if (ids.isEmpty()) return@withSession plugin.texts.send(player, "select.none")
        val curIdx = ids.indexOf(s.selectedLocalId)
        val target = ids[(curIdx + 1).mod(ids.size)]
        val element = s.scene.elements.first { it.localId == target }
        s.selectedLocalId = target
        s.mode = if (element is ArmorStandElement) EditMode.POSE else EditMode.TRANSLATE
        sound(player, Sound.UI_BUTTON_CLICK, 0.4f, 1.2f)
        plugin.texts.send(player, "select.done", "id" to target.toString(), "type" to typeLabel(element))
        readout(player, s)
    }

    fun selectPrev(player: Player) = withSession(player) { s ->
        val ids = s.scene.elements.map { it.localId }
        if (ids.isEmpty()) return@withSession plugin.texts.send(player, "select.none")
        val curIdx = ids.indexOf(s.selectedLocalId)
        val target = ids[(curIdx - 1).mod(ids.size)]
        val element = s.scene.elements.first { it.localId == target }
        s.selectedLocalId = target
        s.mode = if (element is ArmorStandElement) EditMode.POSE else EditMode.TRANSLATE
        sound(player, Sound.UI_BUTTON_CLICK, 0.4f, 0.8f)
        plugin.texts.send(player, "select.done", "id" to target.toString(), "type" to typeLabel(element))
        readout(player, s)
    }

    // --- GUI state queries (used by ControlPanel to populate dynamic lore) ---

    fun currentStepFormatted(player: Player): String? {
        val s = plugin.sessions.get(player.uniqueId) ?: return null
        return formatStep(s)
    }

    fun currentAxisFormatted(player: Player): String? {
        val s = plugin.sessions.get(player.uniqueId) ?: return null
        return s.axis.name
    }

    // --- sound helper ---

    private fun sound(player: Player, sound: Sound, volume: Float, pitch: Float) {
        if (plugin.settings.soundEnabled) player.playSound(player.location, sound, volume, pitch)
    }
}
