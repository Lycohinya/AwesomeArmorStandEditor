package com.tinyyana.awesomeArmorStandEditor.particle

import com.tinyyana.awesomeArmorStandEditor.AaseKeys
import com.tinyyana.awesomeArmorStandEditor.AwesomeArmorStandEditorPlugin
import com.tinyyana.awesomeArmorStandEditor.model.ParticleEmitter
import com.tinyyana.awesomeArmorStandEditor.sched.TaskHandle
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Particle emitters are invisible marker entities carrying their params in PDC, so they persist with
 * the placed art. Each marker runs its own timer on the entity's scheduler: that is the thread that owns
 * the marker, so reading its position and the players around it is always legal, and a marker whose chunk
 * unloads simply loses its timer (it is picked up again when its entities load). No world scan: markers
 * are indexed as entities load.
 *
 * Perf: the PDC string is decoded and the [Particle] enum + dust options resolved exactly once (at
 * spawn/index time) and kept on the [Marker]. A timer tick then only counts toward the emitter's rate;
 * the player-in-range query runs only on the ticks that would emit.
 *
 * Players are found with a region-local entity query, not `World.getPlayers()` (which spans regions on
 * Folia). So on a regionized server an emitter only fires for viewers its own region owns, the same set
 * `World.spawnParticle` delivers to there.
 */
class ParticleService(private val plugin: AwesomeArmorStandEditorPlugin, private val keys: AaseKeys) {

    /** Decoded emitter + pre-resolved particle handle, so the hot path never parses. */
    private class Marker(
        val entity: Entity,
        val placement: String?,
        val emitter: ParticleEmitter,
        val particle: Particle?,
        val dust: Particle.DustOptions?,
    ) {
        @Volatile var timer: TaskHandle? = null

        /** Ticks since the last emit attempt; only touched by the marker's own timer. */
        var sinceEmit = 0
    }

    private val markers = ConcurrentHashMap<UUID, Marker>()

    /**
     * Emissions still allowed this tick. Refilled every tick by one global timer; with several region
     * threads emitting at once the cap is approximate (a few extra may slip through), which is all a
     * TPS safeguard needs.
     */
    private val budget = AtomicInteger(0)
    private var refill: TaskHandle? = null

    fun start() {
        budget.set(plugin.settings.particleBudget)
        refill = plugin.scheduling.runGlobalTimer(1L, 1L) { budget.set(plugin.settings.particleBudget) }
    }

    fun stop() {
        refill?.cancel(); refill = null
        markers.values.forEach { it.timer?.cancel() }
        markers.clear()
    }

    /**
     * Markers are stamped through the registry like elements (owner/scene/local/placement/sceneName),
     * so recall by placement finds them; the emitter key is written first so the registry indexes
     * the marker as an emitter, not an element.
     */
    fun spawnEmitter(
        origin: Location, sceneId: String, owner: UUID, emitter: ParticleEmitter,
        placementId: String, sceneName: String,
    ): Entity {
        val loc = origin.clone().add(emitter.offset.x, emitter.offset.y, emitter.offset.z)
        val world = loc.world ?: error("no world")
        val marker = world.spawn(loc, ArmorStand::class.java) {
            it.isMarker = true; it.isInvisible = true; it.isSmall = true
            it.setGravity(false); it.setBasePlate(false)
        }
        marker.persistentDataContainer.set(keys.emitter, PersistentDataType.STRING, encode(emitter))
        plugin.registry.tag(marker, owner, sceneId, emitter.id, placementId, sceneName)
        track(marker, placementId, emitter)
        return marker
    }

    /** Index the emitter markers among [entities]. Runs on the thread that owns them (it reads their PDC). */
    fun indexEntities(entities: Collection<Entity>) {
        for (e in entities) {
            if (markers.containsKey(e.uniqueId)) continue
            val pdc = e.persistentDataContainer
            val emitter = pdc.get(keys.emitter, PersistentDataType.STRING)?.let(::decode) ?: continue
            track(e, pdc.get(keys.placement, PersistentDataType.STRING), emitter)
        }
    }

    /**
     * Start the marker's own timer. When the entity goes away (removed, or its chunk unloads) the retired
     * callback drops it from [markers]; it removes by (uuid, marker) so a reloaded marker with the same
     * UUID, already re-tracked, is not dropped by its predecessor's late callback.
     */
    private fun track(entity: Entity, placement: String?, emitter: ParticleEmitter) {
        val marker = build(entity, placement, emitter)
        val uuid = entity.uniqueId
        if (markers.putIfAbsent(uuid, marker) != null) return
        marker.timer = plugin.scheduling.runForEntityTimer(
            entity, 1L, 1L,
            task = { tickMarker(marker) },
            retired = { markers.remove(uuid, marker) },
        )
    }

    /**
     * Removes the emitter markers of one placed copy. Not by scene id: several copies of one saved
     * scene share it, and clearing one copy's emitters must not pull the others'. The placement comes
     * from the cached marker, so no entity owned elsewhere is read; the removal itself runs on its owner.
     */
    fun removeForPlacement(placementId: String): Int {
        var removed = 0
        for ((uuid, marker) in markers) {
            if (marker.placement != placementId || !markers.remove(uuid, marker)) continue
            marker.timer?.cancel()
            plugin.registry.forget(uuid)
            plugin.scheduling.onOwner(marker.entity) { if (!it.isDead) it.remove() }
            removed++
        }
        return removed
    }

    /** Drop a marker that was removed by someone else (recall/admin) from the ticking set. */
    fun forget(entity: Entity) {
        markers.remove(entity.uniqueId)?.timer?.cancel()
    }

    /** Runs on the marker's owning thread, every tick. */
    private fun tickMarker(m: Marker) {
        val e = m.emitter
        if (m.particle == null || e.rateTicks <= 0 || ++m.sinceEmit < e.rateTicks) return
        m.sinceEmit = 0
        if (budget.get() <= 0) return
        val loc = m.entity.location
        val range = plugin.settings.particleRange.toDouble()
        val rangeSq = range * range
        // Single-threaded servers keep the cheap player-list scan; a regionized one may only look at what
        // this region owns, which costs a box query.
        val watched = if (plugin.scheduling.regionized) {
            plugin.scheduling.nearbyEntities(loc, range) { it is Player && it.location.distanceSquared(loc) <= rangeSq }.isNotEmpty()
        } else {
            loc.world?.players?.any { it.location.distanceSquared(loc) <= rangeSq } == true
        }
        if (!watched) return
        budget.decrementAndGet()
        emit(loc, m)
    }

    private fun emit(loc: Location, m: Marker) {
        val world = loc.world ?: return
        val particle = m.particle ?: return
        val e = m.emitter
        runCatching {
            if (m.dust != null) {
                world.spawnParticle(particle, loc, e.count, e.spread.x, e.spread.y, e.spread.z, e.speed, m.dust)
            } else {
                world.spawnParticle(particle, loc, e.count, e.spread.x, e.spread.y, e.spread.z, e.speed)
            }
        }
    }

    private fun build(entity: Entity, placement: String?, e: ParticleEmitter): Marker {
        val particle = runCatching { Particle.valueOf(e.particle.uppercase()) }.getOrNull()
        val dust = if (particle?.dataType == Particle.DustOptions::class.java) {
            Particle.DustOptions(Color.fromRGB(e.dustColor and 0xFFFFFF), 1.0f)
        } else null
        return Marker(entity, placement, e, particle, dust)
    }

    private fun encode(e: ParticleEmitter): String =
        listOf(e.particle, e.count, e.speed, e.spread.x, e.spread.y, e.spread.z, e.rateTicks, e.dustColor).joinToString(";")

    private fun decode(s: String): ParticleEmitter? = runCatching {
        val p = s.split(";")
        ParticleEmitter(
            id = 0, particle = p[0], offset = com.tinyyana.awesomeArmorStandEditor.model.Vec3.ZERO,
            count = p[1].toInt(), speed = p[2].toDouble(),
            spread = com.tinyyana.awesomeArmorStandEditor.model.Vec3(p[3].toDouble(), p[4].toDouble(), p[5].toDouble()),
            rateTicks = p[6].toInt(), dustColor = p[7].toInt(),
        )
    }.getOrNull()
}
