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
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
    @Volatile private var tick = 0L
    private var task: ScheduledTask? = null

    /**
     * Folia: 每個 tick 的預算是跨 region 共用的,實際發射動作分散在各 marker 自己的執行緒上,
     * 所以改成 atomic 計數。這讓預算變成「近似」而非精確——對「別讓粒子吃光 tick」這個
     * 目的來說夠用,而精確化的代價是把所有 region 同步起來,那正是 Folia 要避免的事。
     */
    private val budgetLeft = AtomicInteger(0)

    /**
     * Folia: 這個時鐘本身只讀插件自己的表、再把工作派給各 marker 的 EntityScheduler,
     * 不碰任何世界狀態,所以掛在 global region 是合法的(不是把同步任務丟給 global)。
     */
    fun start() {
        task = plugin.server.globalRegionScheduler.runAtFixedRate(plugin, { run() }, 20L, 1L)
    }

    fun stop() {
        task?.cancel(); task = null
    }

    fun spawnEmitter(origin: Location, sceneId: String, owner: UUID, emitter: ParticleEmitter): Entity {
        val loc = origin.clone().add(emitter.offset.x, emitter.offset.y, emitter.offset.z)
        val world = loc.world ?: error("no world")
        val marker = world.spawn(loc, ArmorStand::class.java) {
            it.isMarker = true; it.isInvisible = true; it.isSmall = true
            it.setGravity(false); it.setBasePlate(false)
        }
        val pdc = marker.persistentDataContainer
        pdc.set(keys.owner, PersistentDataType.STRING, owner.toString())
        pdc.set(keys.scene, PersistentDataType.STRING, sceneId)
        pdc.set(keys.local, PersistentDataType.INTEGER, emitter.id)
        pdc.set(keys.emitter, PersistentDataType.STRING, encode(emitter))
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

    /**
     * One-time startup index of emitter markers in currently-loaded chunks.
     *
     * Folia: 同 [EntityRegistry.indexLoaded] —— `chunk.entities` 要在擁有者 region 上讀。
     */
    fun indexLoaded() {
        for (world in plugin.server.worlds) for (chunk in world.loadedChunks) {
            plugin.server.regionScheduler.execute(plugin, world, chunk.x, chunk.z) { indexChunk(chunk) }
        }
    }

    fun removeForScene(sceneId: String) {
        val it = markers.keys.iterator()
        while (it.hasNext()) {
            val e = it.next()
            // Folia: PDC 讀取與 remove() 都要在該實體擁有者的執行緒上做。先從表移除(這是
            // 插件自己的狀態),實際判斷與刪除派給實體;判斷不成立就把它放回表裡。
            it.remove()
            e.scheduler.run(plugin, {
                if (e.persistentDataContainer.get(keys.scene, PersistentDataType.STRING) == sceneId) {
                    if (!e.isDead) e.remove()
                } else {
                    decode(e.persistentDataContainer.get(keys.emitter, PersistentDataType.STRING) ?: "")
                        ?.let { markers[e] = build(it) }
                }
            }, null)
        }
    }

    private fun run() {
        if (markers.isEmpty()) return
        tick++
        budgetLeft.set(plugin.settings.particleBudget)
        val range = plugin.settings.particleRange.toDouble()
        for ((marker, cached) in markers) {
            val e = cached.emitter
            if (cached.particle == null || e.rateTicks <= 0 || tick % e.rateTicks != 0L) continue
            if (budgetLeft.get() <= 0) break
            // Folia: isValid / location / getNearbyPlayers / spawnParticle 全都要在 marker
            // 擁有者的執行緒上。retired callback 代表實體已消失,順手把它從表裡拿掉。
            marker.scheduler.run(plugin, { emitOnOwner(marker, cached, range) }, { markers.remove(marker) })
        }
    }

    /**
     * 只能在 [marker] 擁有者的執行緒上呼叫。
     *
     * Folia:原本的 `loc.world.players.none { p.location.distanceSquared(loc) }` 會讀到
     * **別的 region 擁有的玩家**的位置——那是 Folia README「Code running in one region under no
     * circumstance can be accessing or modifying data that is in another region」明文禁止的
     * 跨 region 讀取。改用 `World#getNearbyPlayers`,它只會看到目前 region 的實體切片。
     * 行為差異:剛好落在別的 region、但直線距離仍在 range 內的玩家不會被算進來,
     * 結果是「少放一顆粒子」——安全方向的差異,不是資料損毀。
     */
    private fun emitOnOwner(marker: Entity, cached: Cached, range: Double) {
        if (!marker.isValid) { markers.remove(marker); return }
        val loc = marker.location
        if (loc.world?.getNearbyPlayers(loc, range)?.isEmpty() != false) return
        if (budgetLeft.decrementAndGet() < 0) return
        emit(loc, cached)
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
