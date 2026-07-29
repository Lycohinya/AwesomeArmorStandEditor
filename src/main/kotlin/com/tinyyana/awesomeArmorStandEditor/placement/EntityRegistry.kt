package com.tinyyana.awesomeArmorStandEditor.placement

import com.tinyyana.awesomeArmorStandEditor.AaseKeys
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.entity.Entity
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks the entities this plugin owns (PDC-tagged) and derives counts for limit checks.
 *
 * ponytail: counts are in-memory over currently-indexed entities (spawned this session +
 * chunk-load indexed). Entities in never-loaded chunks aren't counted, so a determined player
 * could exceed a cap by loading fresh chunks; acceptable for P1 (no world scan on the red-line
 * path). Upgrade path: persist per-owner counts if abuse shows up.
 */
class EntityRegistry(private val keys: AaseKeys) {

    data class Tag(val owner: UUID, val sceneId: String, val localId: Int)

    private val byUuid = ConcurrentHashMap<UUID, Tag>()

    fun tag(entity: Entity, owner: UUID, sceneId: String, localId: Int) {
        val pdc = entity.persistentDataContainer
        pdc.set(keys.owner, PersistentDataType.STRING, owner.toString())
        pdc.set(keys.scene, PersistentDataType.STRING, sceneId)
        pdc.set(keys.local, PersistentDataType.INTEGER, localId)
        byUuid[entity.uniqueId] = Tag(owner, sceneId, localId)
    }

    fun read(entity: Entity): Tag? {
        val pdc = entity.persistentDataContainer
        val owner = pdc.get(keys.owner, PersistentDataType.STRING) ?: return null
        val scene = pdc.get(keys.scene, PersistentDataType.STRING) ?: return null
        val local = pdc.get(keys.local, PersistentDataType.INTEGER) ?: return null
        return try {
            Tag(UUID.fromString(owner), scene, local)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun isOurs(entity: Entity): Boolean =
        entity.persistentDataContainer.has(keys.owner, PersistentDataType.STRING)

    /**
     * Emitter markers are ours too, but they are not editable elements: their emitter id shares
     * numbers with element localIds, so anything resolving a Tag to a scene element must skip them.
     */
    fun isEmitterMarker(entity: Entity): Boolean =
        entity.persistentDataContainer.has(keys.emitter, PersistentDataType.STRING)

    fun forget(uuid: UUID) {
        byUuid.remove(uuid)
    }

    fun ownerCount(owner: UUID): Int = byUuid.values.count { it.owner == owner }

    fun total(): Int = byUuid.size

    fun countInChunk(chunk: Chunk): Int = chunk.entities.count { isOurs(it) }

    /** Index our tagged entities in a single (already-loaded) chunk. Not a world scan. */
    fun indexChunk(chunk: Chunk) {
        for (e in chunk.entities) read(e)?.let { byUuid[e.uniqueId] = it }
    }

    /**
     * One-time startup index of entities in currently-loaded chunks.
     *
     * Folia: `chunk.entities` 只能在擁有該 chunk 的 region 執行緒上讀,而 onEnable 跑在
     * global region。因此每個 chunk 各自跳一次 RegionScheduler,而不是在這條執行緒上直接掃。
     */
    fun indexLoaded(plugin: Plugin) {
        for (world in Bukkit.getWorlds()) for (chunk in world.loadedChunks) {
            plugin.server.regionScheduler.execute(plugin, world, chunk.x, chunk.z) { indexChunk(chunk) }
        }
    }

    fun taggedInChunk(chunk: Chunk): List<Entity> = chunk.entities.filter { isOurs(it) }
}
