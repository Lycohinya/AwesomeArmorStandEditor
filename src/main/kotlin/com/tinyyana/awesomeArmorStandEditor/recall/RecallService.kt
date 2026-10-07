package com.tinyyana.awesomeArmorStandEditor.recall

import com.tinyyana.awesomeArmorStandEditor.AwesomeArmorStandEditorPlugin
import com.tinyyana.awesomeArmorStandEditor.admin.parsePurgeArgs
import com.tinyyana.awesomeArmorStandEditor.integration.LycoLibHook
import com.tinyyana.awesomeArmorStandEditor.model.Scene
import com.tinyyana.awesomeArmorStandEditor.model.Vec3
import com.tinyyana.awesomeArmorStandEditor.placement.EntityRegistry
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Recall (收回): a player taking their own placed work back out of the world. Save files are never
 * touched — the blueprint stays and can be placed again.
 *
 * Entity lookup never walks a world: the registry index (entity UUID -> tag, resolved with
 * Server.getEntity) unioned with getNearbyEntities around a seed, both filtered by our PDC.
 * Only loaded chunks are reachable, and the player is told so.
 */
class RecallService(private val plugin: AwesomeArmorStandEditorPlugin) {

    private val registry get() = plugin.registry
    private val texts get() = plugin.texts
    private val pending = ConcurrentHashMap<UUID, PendingRemove>()

    /** 3-second per-player limit on punch/right-click hints (main thread only). */
    val hints = HintThrottle(HINT_INTERVAL_MILLIS)

    // --- snapshots -------------------------------------------------------------

    fun candidate(entity: Entity, tag: EntityRegistry.Tag): Candidate {
        val l = entity.location
        return Candidate(
            id = entity.uniqueId, owner = tag.owner, sceneId = tag.sceneId, localId = tag.localId,
            placement = tag.placement, sceneName = tag.sceneName, emitter = tag.emitter,
            world = l.world?.name ?: "", pos = Vec3(l.x, l.y, l.z),
        )
    }

    /** Index hits (resolved via Server.getEntity) ∪ our entities within [radius] of [center]. */
    fun gather(indexIds: Collection<UUID>, center: Location, radius: Double = SEED_RADIUS): Map<UUID, Pair<Entity, Candidate>> {
        val out = LinkedHashMap<UUID, Pair<Entity, Candidate>>()
        for (id in indexIds) {
            val e = registry.resolve(id) ?: continue
            val tag = registry.read(e) ?: continue
            out[id] = e to candidate(e, tag)
        }
        val world = center.world
        if (world != null) {
            for (e in world.getNearbyEntities(center, radius, radius, radius)) {
                if (e.uniqueId in out || !registry.isOurs(e) || !e.isValid) continue
                val tag = registry.read(e) ?: continue
                out[e.uniqueId] = e to candidate(e, tag)
            }
        }
        return out
    }

    fun sceneIds(scene: Scene) = SceneIds(scene.elements.map { it.localId }.toSet(), scene.emitters.map { it.id }.toSet())

    /** Session (any player's) that owns this placement or scene id, if one is open. */
    private fun owningSessionScene(tag: EntityRegistry.Tag): Scene? =
        plugin.sessions.all().firstOrNull {
            (tag.placement != null && it.placementId == tag.placement) || it.scene.id == tag.sceneId
        }?.scene

    /** [savedCache] lets a caller judging many entities read each scene file once. */
    fun isOrphan(tag: EntityRegistry.Tag, savedCache: MutableMap<Pair<UUID, String>, SceneIds?>? = null): Boolean {
        val key = tag.owner to tag.sceneId
        val saved = if (savedCache != null) {
            savedCache.getOrPut(key) { plugin.store.load(tag.owner, tag.sceneId)?.let { sceneIds(it) } }
        } else {
            plugin.store.load(tag.owner, tag.sceneId)?.let { sceneIds(it) }
        }
        val session = owningSessionScene(tag)?.let { sceneIds(it) }
        return OrphanRule.isOrphan(tag.localId, tag.emitter, saved, session)
    }

    /**
     * The copy [seed] belongs to: its placement, or for a 1.1.0 entity the legacy group (see
     * [LegacyGrouping]). Returns the scope plus the UUIDs it covers right now.
     */
    fun groupOf(seed: Entity, tag: EntityRegistry.Tag): Pair<RemoveScope, Set<UUID>> {
        tag.placement?.let { pid ->
            val found = gather(registry.byPlacement(pid), seed.location).values
                .filter { it.second.placement == pid }.map { it.first.uniqueId }.toSet()
            return RemoveScope.Placement(pid) to (found + seed.uniqueId)
        }
        val ids = legacyGroup(seed, tag, plugin.store.load(tag.owner, tag.sceneId))
        return RemoveScope.Ids(ids) to ids
    }

    fun legacyGroup(seed: Entity, tag: EntityRegistry.Tag, scene: Scene?): Set<UUID> {
        val pool = gather(emptyList(), seed.location).values.map { it.second }
        val offsets = scene?.let { s ->
            LegacyGrouping.Offsets(
                elements = s.elements.associate { it.localId to it.offset },
                emitters = s.emitters.associate { it.id to it.offset },
            )
        }
        return LegacyGrouping.select(candidate(seed, tag), pool, offsets)
    }

    // --- previews --------------------------------------------------------------

    /** `/aase remove look`: what the player is looking at, else their nearest own element. */
    fun previewLook(player: Player) {
        val range = plugin.settings.selectRange.toDouble()
        val eye = player.eyeLocation
        val hit = player.world.rayTraceEntities(eye, eye.direction, range, RAY_SIZE) {
            it != player && registry.isOurs(it)
        }?.hitEntity
        val target = hit ?: player.getNearbyEntities(range, range, range)
            .filter { registry.isOurs(it) && !registry.isEmitterMarker(it) && registry.read(it)?.owner == player.uniqueId }
            .minByOrNull { it.location.distanceSquared(eye) }
            ?: return texts.send(player, "remove.look-none")
        previewEntity(player, target, offerDeleteOne = false)
    }

    /** Preview recalling the copy [target] belongs to. Used by `remove look` and the tool gesture. */
    fun previewEntity(player: Player, target: Entity, offerDeleteOne: Boolean) {
        val tag = registry.read(target) ?: return texts.send(player, "remove.look-none")
        if (tag.owner != player.uniqueId) {
            return texts.send(player, "remove.not-yours", "owner" to texts.escape(ownerName(tag.owner)))
        }
        val (scope, _) = groupOf(target, tag)
        val extra = mutableListOf<Component>()
        if (offerDeleteOne && !tag.emitter) {
            val session = plugin.sessions.get(player.uniqueId)
            if (session != null && tag.placement != null && session.placementId == tag.placement &&
                session.scene.elements.any { it.localId == tag.localId }
            ) {
                extra += texts.button("remove.button-delete-one", "/aase delete ${tag.localId}", "id" to tag.localId.toString())
            }
        }
        preview(player, scope, target.location, extra, noneKey = "remove.look-none")
    }

    /** `/aase remove here [radius]`. */
    fun previewHere(player: Player, radiusArg: String?) {
        val radius = if (radiusArg == null) DEFAULT_HERE_RADIUS.coerceAtMost(plugin.settings.maxPurgeRadius)
        else parsePurgeArgs(listOf(radiusArg), plugin.settings.maxPurgeRadius)?.radius
            ?: return texts.send(player, "usage.remove")
        val c = player.location
        val scope = RemoveScope.Sphere(c.world?.name ?: "", Vec3(c.x, c.y, c.z), radius.toDouble())
        preview(player, scope, c.clone(), emptyList(), noneKey = "remove.here-none", "radius" to radius.toString())
    }

    /** `/aase remove scene <name>`. */
    fun previewScene(player: Player, name: String) {
        val legacyId = plugin.store.loadByName(player.uniqueId, name)?.id
        preview(player, RemoveScope.SceneName(name, legacyId), player.location.clone(), emptyList(), noneKey = "remove.scene-none", "name" to texts.escape(name))
    }

    private fun preview(
        player: Player, scope: RemoveScope, center: Location, extra: List<Component>,
        noneKey: String, vararg nonePh: Pair<String, String>,
    ) {
        val plan = planFor(player, scope, center)
        if (plan.isEmpty) return texts.send(player, noneKey, *nonePh)
        val c = Vec3(center.x, center.y, center.z)
        pending[player.uniqueId] = PendingRemove(scope, center.world?.name ?: "", c, System.currentTimeMillis())
        texts.send(player, "remove.preview", "count" to plan.elements.toString(), "groups" to plan.groups.toString())
        if (plan.emitters > 0) texts.send(player, "remove.preview-emitters", "count" to plan.emitters.toString())
        texts.sendButtons(
            player,
            listOf(
                texts.button("remove.button-confirm", "/aase remove confirm"),
                texts.button("remove.button-cancel", "/aase remove cancel"),
            ) + extra,
        )
        texts.send(player, "remove.loaded-only")
    }

    // --- confirm / cancel ------------------------------------------------------

    fun confirm(player: Player) {
        val p = pending.remove(player.uniqueId) ?: return texts.send(player, "remove.none-pending")
        if (p.isExpired(System.currentTimeMillis())) return texts.send(player, "remove.expired")
        val world = plugin.server.getWorld(p.world) ?: return texts.send(player, "remove.gone")
        // Re-resolve now: entities may have gone or chunks unloaded since the preview.
        val center = Location(world, p.center.x, p.center.y, p.center.z)
        val (found, plan) = resolve(player.uniqueId, p.scope, center)
        if (plan.isEmpty) return texts.send(player, "remove.gone")
        val removed = execute(found, plan)
        texts.send(player, "remove.done", "count" to removed.toString())
        texts.send(player, "remove.loaded-only")
        LycoLibHook.audit(
            plugin.name, player.name, "scene.remove",
            "count=$removed groups=${plan.groups} scope=${p.scope::class.simpleName} " +
                "at=${world.name}:${center.blockX},${center.blockY},${center.blockZ}",
        )
    }

    fun cancel(player: Player) {
        if (pending.remove(player.uniqueId) != null) texts.send(player, "remove.cancelled")
        else texts.send(player, "remove.none-pending")
    }

    /**
     * `/aase close discard`: take the session's own copy out of the world without a preview (the
     * player already chose "discard" from the unsaved-changes prompt). Returns entities removed.
     */
    fun discardPlacement(player: Player, placementId: String, center: Location): Int {
        val (found, plan) = resolve(player.uniqueId, RemoveScope.Placement(placementId), center)
        val removed = if (plan.isEmpty) 0 else execute(found, plan)
        // Markers that only the particle ticker still knew about (e.g. index dropped them).
        return removed + plugin.particles.removeForPlacement(placementId)
    }

    fun dropPending(player: UUID) {
        pending.remove(player)
    }

    fun forgetPlayer(player: UUID) {
        pending.remove(player)
        hints.forget(player)
    }

    // --- internals -------------------------------------------------------------

    private fun planFor(player: Player, scope: RemoveScope, center: Location): RemovePlan.Plan =
        resolve(player.uniqueId, scope, center).second

    private fun resolve(requester: UUID, scope: RemoveScope, center: Location): Pair<Map<UUID, Entity>, RemovePlan.Plan> {
        val indexIds = when (scope) {
            is RemoveScope.Placement -> registry.byPlacement(scope.placementId)
            is RemoveScope.Ids -> scope.ids.toList()
            is RemoveScope.Sphere, is RemoveScope.SceneName -> registry.byOwner(requester)
        }
        val radius = if (scope is RemoveScope.Sphere) scope.radius else SEED_RADIUS
        val found = gather(indexIds, center, radius)
        val plan = RemovePlan.plan(found.values.map { it.second }, requester, scope)
        return found.mapValues { it.value.first } to plan
    }

    /** The one removal path (remove confirm, close discard). Returns entities removed. */
    private fun execute(found: Map<UUID, Entity>, plan: RemovePlan.Plan): Int {
        var removed = 0
        for (id in plan.ids) {
            val e = found[id] ?: continue
            if (!e.isValid) continue
            removeEntity(e)
            removed++
        }
        return removed
    }

    /**
     * Forget, close any session editing this copy, drop from the particle ticker, remove.
     * Shared by recall and the admin tools. [closeSessions] is false only for deleting one element
     * from inside the session that owns it — that session stays open and drops the element itself.
     */
    fun removeEntity(entity: Entity, closeSessions: Boolean = true) {
        if (closeSessions) closeSessionsTouching(entity)
        registry.forget(entity.uniqueId)
        for (session in plugin.sessions.all()) {
            session.entities.entries.removeIf { it.value.uniqueId == entity.uniqueId }
        }
        plugin.particles.forget(entity)
        entity.remove()
    }

    /**
     * Anyone's open session on the copy [entity] belongs to is stopped and closed — not just the
     * requester's. Left open, a later save would write the removed elements back into the file.
     */
    private fun closeSessionsTouching(entity: Entity) {
        val placement = registry.read(entity)?.placement
        for (session in plugin.sessions.all().toList()) {
            val hit = (placement != null && session.placementId == placement) ||
                session.entities.values.any { it.uniqueId == entity.uniqueId }
            if (!hit) continue
            plugin.animation.stop(session)
            plugin.sessions.close(session.playerId)
            plugin.server.getPlayer(session.playerId)?.let { texts.send(it, "remove.session-closed") }
        }
    }

    /** Local name lookup only (usercache); never a blocking profile fetch. */
    fun ownerName(uuid: UUID): String =
        plugin.server.getPlayer(uuid)?.name
            ?: plugin.server.getOfflinePlayer(uuid).name
            ?: uuid.toString().take(8)

    companion object {
        /** Seed-neighbourhood search radius for grouping (contract: 48). */
        const val SEED_RADIUS = 48.0
        const val DEFAULT_HERE_RADIUS = 8
        /** A little slack so aiming at a thin armor stand still hits. */
        const val RAY_SIZE = 0.2
        const val HINT_INTERVAL_MILLIS = 3_000L
    }
}
