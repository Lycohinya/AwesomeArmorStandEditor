package com.tinyyana.awesomeArmorStandEditor.sched

import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture

/**
 * One scheduling surface for Spigot, Paper and Folia.
 *
 * Plain Bukkit's `runTask` means "the main thread". On a region-threaded server (Folia, Lecithin) that
 * lane owns no region, so touching an entity or a block from it throws or silently misbehaves. There every
 * piece of work has to be handed to the thread that owns what it touches: an entity, a location, or the
 * global region. Spigot has none of those schedulers, so the plain BukkitScheduler stays as the fallback.
 *
 * This interface only mentions Bukkit types. The Paper/Folia API is referenced exclusively inside
 * [RegionScheduling], which is never loaded on Spigot; [create] picks the implementation.
 *
 * Timers use ticks. Region schedulers reject a delay or period below 1, so implementations clamp to 1.
 */
interface Scheduling {

    /** True only on a real region-threaded server, where several threads tick the world at once. */
    val regionized: Boolean

    /**
     * Runs [task] on the thread that owns [entity], on a later tick. If the entity is gone by then (or
     * already was), [retired] runs instead and [task] never does.
     */
    fun runForEntity(entity: Entity, task: (Entity) -> Unit, retired: (() -> Unit)? = null)

    /**
     * Repeats [task] on the thread that owns [entity]. The task receives its own handle so it can stop
     * itself. When the entity goes away the timer ends and [retired] runs once.
     * Returns null if the entity was already gone (then [retired] has run, and nothing is scheduled).
     */
    fun runForEntityTimer(
        entity: Entity,
        delayTicks: Long,
        periodTicks: Long,
        task: (TaskHandle) -> Unit,
        retired: (() -> Unit)? = null,
    ): TaskHandle?

    /** Runs [task] on the thread that owns the region around [location]. */
    fun runAt(location: Location, task: () -> Unit)

    /** Repeats [task] on the thread that owns the region around [location]. */
    fun runAtTimer(location: Location, delayTicks: Long, periodTicks: Long, task: (TaskHandle) -> Unit): TaskHandle

    /** Runs [task] on the global region (work that touches no entity or block, such as ticking a registry). */
    fun runGlobal(task: () -> Unit)

    fun runGlobalTimer(delayTicks: Long, periodTicks: Long, task: (TaskHandle) -> Unit): TaskHandle

    /** Runs [task] off the tick threads. It must not touch entities, blocks or inventories. */
    fun runAsync(task: () -> Unit)

    /**
     * Moves [entity] to [location]. Callable from any thread. A regionized server (Folia, Lecithin) teleports
     * asynchronously, handing the move to the entity's owner (a plain `teleport` would throw when the
     * destination belongs to another region); Spigot and plain Paper teleport at once. The future completes
     * with whether the move happened.
     */
    fun teleport(entity: Entity, location: Location): CompletableFuture<Boolean>

    /** True if the current thread may touch [entity] right now. On Spigot that is just the main thread. */
    fun isOwnedHere(entity: Entity): Boolean

    /** True if the current thread may touch the blocks and entities around [location] right now. */
    fun isOwnedHere(location: Location): Boolean

    /**
     * Largest radius up to [radius] around [center] whose whole box lies in chunks the current thread
     * owns (stepped in whole chunks), or -1 when not even the centre chunk is owned. Entity box queries
     * throw when the box leaves the owned region, so [nearbyEntities] sizes its query with this.
     * On Spigot there is one owner, so [radius] comes back unchanged.
     */
    fun ownedRadius(center: Location, radius: Double): Double

    /**
     * Runs [task] right away if the current thread owns [entity], otherwise hands it to the owner
     * (a tick later; dropped if the entity is gone by then).
     */
    fun onOwner(entity: Entity, task: (Entity) -> Unit) {
        if (isOwnedHere(entity)) task(entity) else runForEntity(entity, task)
    }

    /**
     * Entities within [radius] of [center] (a box, like `World.getNearbyEntities`) that pass [filter],
     * limited to what the current thread owns: on a regionized server the radius shrinks at a region
     * border instead of the query throwing, so art in a neighbouring region is not reached from here.
     */
    fun nearbyEntities(center: Location, radius: Double, filter: (Entity) -> Boolean = { true }): Collection<Entity> {
        val world = center.world ?: return emptyList()
        val r = ownedRadius(center, radius)
        return if (r < 0.0) emptyList() else world.getNearbyEntities(center, r, r, r) { filter(it) }
    }

    /**
     * The first entity passing [filter] along the look direction of [eye], like `World.rayTraceEntities`,
     * with the ray shortened to what the current thread owns (the ray's box is a `getNearbyEntities` box
     * and throws past the region border). The usual 3-6 block reach is always fully inside the player's region.
     */
    fun rayTraceEntity(eye: Location, maxDistance: Double, raySize: Double, filter: (Entity) -> Boolean): Entity? {
        val world = eye.world ?: return null
        val reach = minOf(maxDistance, ownedRadius(eye, maxDistance + raySize + 1.0))
        if (reach < 0.0) return null
        return world.rayTraceEntities(eye, eye.direction, reach, raySize) { filter(it) }?.hitEntity
    }

    /** Cancels everything this plugin scheduled. Called from `onDisable`. */
    fun cancelAll()

    companion object {
        /**
         * Picks [RegionScheduling] when the server has the Paper scheduler API (Paper, Folia and forks),
         * otherwise [BukkitScheduling]. [RegionScheduling] is only named inside the success branch, so the
         * JVM never tries to load it (and its Paper imports) on Spigot. Any linkage failure also falls back.
         */
        fun create(plugin: Plugin): Scheduling {
            val hasPaperSchedulers = runCatching {
                Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler")
                org.bukkit.Server::class.java.getMethod("getGlobalRegionScheduler")
            }.isSuccess
            if (hasPaperSchedulers) {
                val regionized = runCatching {
                    Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
                }.isSuccess
                val region = runCatching { RegionScheduling(plugin, regionized) }.getOrNull()
                if (region != null) return region
            }
            return BukkitScheduling(plugin)
        }
    }
}
