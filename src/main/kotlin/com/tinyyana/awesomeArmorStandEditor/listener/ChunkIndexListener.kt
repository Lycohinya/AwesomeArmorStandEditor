package com.tinyyana.awesomeArmorStandEditor.listener

import com.tinyyana.awesomeArmorStandEditor.AwesomeArmorStandEditorPlugin
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.event.EventHandler
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.EntitiesLoadEvent

/**
 * Keep the in-memory registry (and the particle emitters) aware of our entities as chunks load.
 *
 * Since 1.17 a chunk's entities load separately from the chunk, usually after [ChunkLoadEvent], so a
 * chunk-load pass alone can see an empty entity list. [EntitiesLoadEvent] carries the entities that just
 * arrived and is the reliable trigger; the chunk-load pass stays for entities already present then.
 * Indexing is idempotent. Both events fire on the thread that owns the chunk, which is what reading
 * the entities' PDC needs on a regionized server.
 */
class ChunkIndexListener(private val plugin: AwesomeArmorStandEditorPlugin) : org.bukkit.event.Listener {

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) = index(event.chunk.entities.asList())

    @EventHandler
    fun onEntitiesLoad(event: EntitiesLoadEvent) = index(event.entities)

    private fun index(entities: Collection<Entity>) = indexEntities(plugin, entities)

    companion object {
        private fun indexEntities(plugin: AwesomeArmorStandEditorPlugin, entities: Collection<Entity>) {
            plugin.registry.indexEntities(entities)
            plugin.particles.indexEntities(entities)
        }

        /**
         * Startup pass over chunks that were already loaded when the plugin enabled. `World.getLoadedChunks`
         * only walks a concurrent chunk table, so any thread may call it. `Chunk.getEntities` has no thread
         * check either, but it reads the owning region's unsynchronised entity slices, and the entities' PDC
         * is that region's too; the enable thread is not part of any region. So on a regionized server each
         * chunk is indexed on its own region's thread; on Spigot and plain Paper everything is on the main
         * thread and it runs inline, as before.
         */
        fun indexLoadedChunks(plugin: AwesomeArmorStandEditorPlugin) {
            for (world in plugin.server.worlds) for (chunk in world.loadedChunks) {
                val index = { indexEntities(plugin, chunk.entities.asList()) }
                if (plugin.scheduling.regionized) {
                    plugin.scheduling.runAt(Location(world, chunk.x * 16.0, 0.0, chunk.z * 16.0), index)
                } else {
                    index()
                }
            }
        }
    }
}
