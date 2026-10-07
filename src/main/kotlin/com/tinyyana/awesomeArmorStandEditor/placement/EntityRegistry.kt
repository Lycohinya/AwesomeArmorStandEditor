package com.tinyyana.awesomeArmorStandEditor.placement

import com.tinyyana.awesomeArmorStandEditor.AaseKeys
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.entity.Entity
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks the entities this plugin owns (PDC-tagged) and derives counts for limit checks.
 *
 * ponytail: counts are in-memory over currently-indexed entities (spawned this session +
 * chunk-load indexed). Entities in never-loaded chunks aren't counted, so a determined player
 * could exceed a cap by loading fresh chunks; acceptable for P1 (no world scan on the red-line
 * path). Upgrade path: persist per-owner counts if abuse shows up.
 *
 * The index is entity UUID -> [Tag]. Lookups by placement/owner return UUIDs; callers turn them
 * into live entities with [resolve] (Server.getEntity), which also drops stale entries. Nothing
 * here iterates a world.
 */
class EntityRegistry(private val keys: AaseKeys) {

    /**
     * [placement] is null on entities placed by 1.1.0 and earlier (until `/aase edit` migrates them);
     * [sceneName] is null on those too. [emitter] marks a particle-emitter marker, which is ours but
     * not an editable element (its localId is the emitter id).
     */
    data class Tag(
        val owner: UUID,
        val sceneId: String,
        val localId: Int,
        val placement: String? = null,
        val sceneName: String? = null,
        val emitter: Boolean = false,
    )

    private val byUuid = ConcurrentHashMap<UUID, Tag>()

    fun tag(entity: Entity, owner: UUID, sceneId: String, localId: Int, placement: String, sceneName: String) {
        val pdc = entity.persistentDataContainer
        pdc.set(keys.owner, PersistentDataType.STRING, owner.toString())
        pdc.set(keys.scene, PersistentDataType.STRING, sceneId)
        pdc.set(keys.local, PersistentDataType.INTEGER, localId)
        pdc.set(keys.placement, PersistentDataType.STRING, placement)
        pdc.set(keys.sceneName, PersistentDataType.STRING, sceneName)
        byUuid[entity.uniqueId] = Tag(owner, sceneId, localId, placement, sceneName, isEmitterMarker(entity))
    }

    /** Back-fill a placement onto a legacy (1.1.0) entity when `/aase edit` binds it. */
    fun migrate(entity: Entity, placement: String, sceneName: String) {
        val pdc = entity.persistentDataContainer
        pdc.set(keys.placement, PersistentDataType.STRING, placement)
        if (!pdc.has(keys.sceneName, PersistentDataType.STRING)) {
            pdc.set(keys.sceneName, PersistentDataType.STRING, sceneName)
        }
        read(entity)?.let { byUuid[entity.uniqueId] = it }
    }

    fun read(entity: Entity): Tag? {
        val pdc = entity.persistentDataContainer
        val owner = pdc.get(keys.owner, PersistentDataType.STRING) ?: return null
        val scene = pdc.get(keys.scene, PersistentDataType.STRING) ?: return null
        val local = pdc.get(keys.local, PersistentDataType.INTEGER) ?: return null
        return try {
            Tag(
                UUID.fromString(owner), scene, local,
                placement = pdc.get(keys.placement, PersistentDataType.STRING),
                sceneName = pdc.get(keys.sceneName, PersistentDataType.STRING),
                emitter = pdc.has(keys.emitter, PersistentDataType.STRING),
            )
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

    fun byPlacement(placementId: String): List<UUID> =
        byUuid.entries.filter { it.value.placement == placementId }.map { it.key }

    fun byOwner(owner: UUID): List<UUID> =
        byUuid.entries.filter { it.value.owner == owner }.map { it.key }

    /** Live entity for an indexed UUID, or null — in which case the stale index entry is dropped. */
    fun resolve(uuid: UUID): Entity? {
        val entity = Bukkit.getEntity(uuid)
        if (entity == null || !entity.isValid) {
            byUuid.remove(uuid)
            return null
        }
        return entity
    }

    fun ownerCount(owner: UUID): Int = byUuid.values.count { it.owner == owner }

    fun total(): Int = byUuid.size

    fun countInChunk(chunk: Chunk): Int = chunk.entities.count { isOurs(it) }

    /** Index our tagged entities in a single (already-loaded) chunk. Not a world scan. */
    fun indexChunk(chunk: Chunk) {
        for (e in chunk.entities) read(e)?.let { byUuid[e.uniqueId] = it }
    }

    /** One-time startup index of entities in currently-loaded chunks. */
    fun indexLoaded() {
        for (world in Bukkit.getWorlds()) for (chunk in world.loadedChunks) indexChunk(chunk)
    }

    fun taggedInChunk(chunk: Chunk): List<Entity> = chunk.entities.filter { isOurs(it) }
}
