package com.tinyyana.awesomeArmorStandEditor.particle

import com.tinyyana.awesomeArmorStandEditor.AaseKeys
import com.tinyyana.awesomeArmorStandEditor.AwesomeArmorStandEditorPlugin
import com.tinyyana.awesomeArmorStandEditor.model.ParticleEmitter
import org.bukkit.Chunk
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Particle emitters are invisible marker entities carrying their params in PDC, so they persist with
 * the placed art. A single global ticker emits only for markers in loaded chunks with a player in
 * range, and stops at a per-tick budget. No world scan — markers are indexed as chunks load.
 *
 * Perf: the PDC string is decoded and the [Particle] enum + dust options resolved exactly once (at
 * spawn/index time) and cached per marker. The per-tick loop then does no parsing at all — it only
 * checks the rate and player range — so a scene full of emitters stays cheap on the main thread.
 */
class ParticleService(private val plugin: AwesomeArmorStandEditorPlugin, private val keys: AaseKeys) {

    /** Decoded emitter + pre-resolved particle handle, so the hot loop never parses. */
    private class Cached(val emitter: ParticleEmitter, val particle: Particle?, val dust: Particle.DustOptions?)

    private val markers = ConcurrentHashMap<Entity, Cached>()
    private var tick = 0L
    private var task: BukkitTask? = null

    fun start() {
        task = plugin.server.scheduler.runTaskTimer(plugin, Runnable { run() }, 20L, 1L)
    }

    fun stop() {
        task?.cancel(); task = null
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
        markers[marker] = build(emitter)
        return marker
    }

    fun indexChunk(chunk: Chunk) {
        for (e in chunk.entities) {
            if (markers.containsKey(e)) continue
            val data = e.persistentDataContainer.get(keys.emitter, PersistentDataType.STRING) ?: continue
            decode(data)?.let { markers[e] = build(it) }
        }
    }

    /** One-time startup index of emitter markers in currently-loaded chunks. */
    fun indexLoaded() {
        for (world in plugin.server.worlds) for (chunk in world.loadedChunks) indexChunk(chunk)
    }

    /**
     * Removes the emitter markers of one placed copy. Not by scene id: several copies of one saved
     * scene share it, and clearing one copy's emitters must not pull the others'.
     */
    fun removeForPlacement(placementId: String): Int {
        var removed = 0
        val it = markers.keys.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.persistentDataContainer.get(keys.placement, PersistentDataType.STRING) == placementId) {
                plugin.registry.forget(e.uniqueId)
                if (!e.isDead) e.remove()
                it.remove()
                removed++
            }
        }
        return removed
    }

    /** Drop a marker that was removed by someone else (recall/admin) from the ticking set. */
    fun forget(entity: Entity) {
        markers.remove(entity)
    }

    private fun run() {
        if (markers.isEmpty()) return
        tick++
        var budget = plugin.settings.particleBudget
        val range = plugin.settings.particleRange.toDouble()
        val rangeSq = range * range
        val it = markers.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val marker = entry.key
            if (!marker.isValid) { it.remove(); continue }
            val cached = entry.value
            val e = cached.emitter
            if (cached.particle == null || e.rateTicks <= 0 || tick % e.rateTicks != 0L) continue
            val loc = marker.location
            if (loc.world?.players?.none { p -> p.location.distanceSquared(loc) <= rangeSq } != false) continue
            emit(loc, cached)
            if (--budget <= 0) break
        }
    }

    private fun emit(loc: Location, c: Cached) {
        val world = loc.world ?: return
        val particle = c.particle ?: return
        val e = c.emitter
        runCatching {
            if (c.dust != null) {
                world.spawnParticle(particle, loc, e.count, e.spread.x, e.spread.y, e.spread.z, e.speed, c.dust)
            } else {
                world.spawnParticle(particle, loc, e.count, e.spread.x, e.spread.y, e.spread.z, e.speed)
            }
        }
    }

    private fun build(e: ParticleEmitter): Cached {
        val particle = runCatching { Particle.valueOf(e.particle.uppercase()) }.getOrNull()
        val dust = if (particle?.dataType == Particle.DustOptions::class.java) {
            Particle.DustOptions(Color.fromRGB(e.dustColor and 0xFFFFFF), 1.0f)
        } else null
        return Cached(e, particle, dust)
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
