package com.tinyyana.awesomeArmorStandEditor.recall

import com.tinyyana.awesomeArmorStandEditor.model.Vec3
import java.util.UUID

/*
 * Pure pieces of "recall" (收回): which entities belong together, which are orphans, what a remove
 * request would take, and what /aase close should do. No Bukkit here, so the rules that decide
 * whose work gets deleted are unit-tested without a server.
 */

/** A snapshot of one of our entities: PDC tag + where it stands. Built from a live entity per call. */
data class Candidate(
    val id: UUID,
    val owner: UUID,
    val sceneId: String,
    val localId: Int,
    val placement: String?,
    val sceneName: String?,
    val emitter: Boolean,
    val world: String,
    val pos: Vec3,
) {
    /** Group key: the placement, or the scene id for 1.1.0 entities that have none yet. */
    val groupKey: String get() = placement ?: "legacy:$sceneId"
}

internal fun Vec3.distSq(o: Vec3): Double {
    val dx = x - o.x
    val dy = y - o.y
    val dz = z - o.z
    return dx * dx + dy * dy + dz * dz
}

/** Ids of a scene's elements and emitters (saved file or an open session's in-memory model). */
data class SceneIds(val elements: Set<Int>, val emitters: Set<Int>) {
    fun has(localId: Int, emitter: Boolean) = localId in (if (emitter) emitters else elements)
}

/**
 * Orphan = carries our PDC but nothing can bind it: (a) its scene has no save file and no open
 * session owns that placement/scene, or (b) the save exists but no longer lists its localId.
 *
 * (b) is relaxed when an open session's in-memory scene still has the localId: an element added
 * and not yet saved is the player's work in progress, not debris.
 */
object OrphanRule {
    fun isOrphan(localId: Int, emitter: Boolean, saved: SceneIds?, session: SceneIds?): Boolean {
        if (saved == null) return session == null
        if (saved.has(localId, emitter)) return false
        return session == null || !session.has(localId, emitter)
    }
}

/**
 * Groups 1.1.0 entities (no placement) into the copy a seed entity belongs to.
 *
 * With a save: origin = seed position − seed's saved offset; for each saved element/emitter take
 * the same-scene legacy entity closest to origin + offset, within [tolerance]. The tolerance keeps
 * a missing element from being "found" in a neighbouring copy of the same scene.
 * Without a save (or the seed's localId is not in it): every same-owner, same-scene legacy entity
 * within [looseRadius] of the seed.
 */
object LegacyGrouping {
    const val DEFAULT_TOLERANCE = 2.0
    const val DEFAULT_LOOSE_RADIUS = 16.0

    data class Offsets(val elements: Map<Int, Vec3>, val emitters: Map<Int, Vec3>)

    fun select(
        seed: Candidate,
        candidates: List<Candidate>,
        offsets: Offsets?,
        tolerance: Double = DEFAULT_TOLERANCE,
        looseRadius: Double = DEFAULT_LOOSE_RADIUS,
    ): Set<UUID> {
        val pool = candidates.filter {
            it.placement == null && it.owner == seed.owner && it.sceneId == seed.sceneId && it.world == seed.world
        }
        val seedOffset = offsets?.let { if (seed.emitter) it.emitters[seed.localId] else it.elements[seed.localId] }
        if (offsets == null || seedOffset == null) {
            val r2 = looseRadius * looseRadius
            return (pool.filter { it.pos.distSq(seed.pos) <= r2 }.map { it.id } + seed.id).toSet()
        }
        val origin = Vec3(seed.pos.x - seedOffset.x, seed.pos.y - seedOffset.y, seed.pos.z - seedOffset.z)
        val tol2 = tolerance * tolerance
        val picked = linkedSetOf(seed.id)
        fun pick(map: Map<Int, Vec3>, emitter: Boolean) {
            for ((localId, off) in map) {
                if (localId == seed.localId && emitter == seed.emitter) continue
                val expected = Vec3(origin.x + off.x, origin.y + off.y, origin.z + off.z)
                pool.asSequence()
                    .filter { it.localId == localId && it.emitter == emitter && it.id !in picked }
                    .map { it to it.pos.distSq(expected) }
                    .filter { it.second <= tol2 }
                    .minByOrNull { it.second }
                    ?.let { picked += it.first.id }
            }
        }
        pick(offsets.elements, emitter = false)
        pick(offsets.emitters, emitter = true)
        return picked
    }
}

/** What a remove request covers. Holds parameters only — never live entities. */
sealed interface RemoveScope {
    /** One placed copy (remove look on a migrated/new entity). */
    data class Placement(val placementId: String) : RemoveScope

    /** An explicit set (a legacy copy grouped by [LegacyGrouping]). */
    data class Ids(val ids: Set<UUID>) : RemoveScope

    /** `/aase remove here`: a sphere around the player. */
    data class Sphere(val world: String, val center: Vec3, val radius: Double) : RemoveScope

    /** `/aase remove scene <name>`: PDC scene name, or (1.1.0 entities) the saved scene's id. */
    data class SceneName(val name: String, val legacySceneId: String?) : RemoveScope
}

/**
 * Turns candidates into the list to delete. Only ever the requester's own entities —
 * `aase.admin` does not widen this; moderators use `/aase admin`.
 */
object RemovePlan {
    data class Plan(val ids: List<UUID>, val elements: Int, val emitters: Int, val groups: Int, val placements: Set<String>) {
        val isEmpty: Boolean get() = ids.isEmpty()
    }

    fun matches(c: Candidate, scope: RemoveScope): Boolean = when (scope) {
        is RemoveScope.Placement -> c.placement == scope.placementId
        is RemoveScope.Ids -> c.id in scope.ids
        is RemoveScope.Sphere -> c.world == scope.world && c.pos.distSq(scope.center) <= scope.radius * scope.radius
        is RemoveScope.SceneName ->
            (c.sceneName != null && c.sceneName.equals(scope.name, ignoreCase = true)) ||
                (scope.legacySceneId != null && c.sceneId == scope.legacySceneId)
    }

    fun plan(candidates: List<Candidate>, requester: UUID, scope: RemoveScope): Plan {
        val chosen = candidates.distinctBy { it.id }.filter { it.owner == requester && matches(it, scope) }
        return Plan(
            ids = chosen.map { it.id },
            elements = chosen.count { !it.emitter },
            emitters = chosen.count { it.emitter },
            groups = chosen.map { it.groupKey }.distinct().size,
            placements = chosen.mapNotNull { it.placement }.toSet(),
        )
    }
}

/** A previewed remove awaiting `/aase remove confirm`. Expires so a stale token can't fire later. */
data class PendingRemove(val scope: RemoveScope, val world: String, val center: Vec3, val createdAtMillis: Long) {
    fun isExpired(nowMillis: Long): Boolean = nowMillis - createdAtMillis > TTL_MILLIS

    companion object {
        const val TTL_MILLIS: Long = 30_000
    }
}

/** `/aase close [save|discard]`. */
object CloseDecision {
    enum class Action { NO_SESSION, CLOSE, ASK, SAVE_THEN_CLOSE, DISCARD, USAGE }

    fun decide(hasSession: Boolean, dirty: Boolean, arg: String?): Action {
        if (!hasSession) return Action.NO_SESSION
        return when (arg?.lowercase()) {
            null, "" -> if (dirty) Action.ASK else Action.CLOSE
            "save" -> Action.SAVE_THEN_CLOSE
            "discard" -> Action.DISCARD
            else -> Action.USAGE
        }
    }
}

/** Per-player cooldown for hint messages (punching an element repeatedly must not spam). */
class HintThrottle(private val intervalMillis: Long) {
    private val last = HashMap<UUID, Long>()

    fun tryAcquire(player: UUID, nowMillis: Long): Boolean {
        val prev = last[player]
        if (prev != null && nowMillis - prev < intervalMillis) return false
        last[player] = nowMillis
        return true
    }

    fun forget(player: UUID) {
        last.remove(player)
    }
}
