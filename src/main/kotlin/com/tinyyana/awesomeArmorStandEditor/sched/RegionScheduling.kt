package com.tinyyana.awesomeArmorStandEditor.sched

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer
import kotlin.math.ceil

/**
 * Paper/Folia implementation on top of the region, entity, global and async schedulers.
 *
 * NEVER reference this class from anywhere except [Scheduling.create]: it imports Paper-only types, so
 * loading it on Spigot would throw NoClassDefFoundError. On plain Paper the same calls simply run on the
 * main thread; on Folia and Lecithin they run on the thread that owns the entity or region.
 *
 * Notes from the Paper contract that shape this file:
 *  - delay and period must be >= 1 tick for every scheduler, hence [tick];
 *  - `EntityScheduler.run`/`runAtFixedRate` return null (and do not call `retired`) when the entity is
 *    already removed, so the null case calls `retired` itself;
 *  - there is no bulk cancel for entity and region tasks, so repeating ones are tracked in [live].
 */
class RegionScheduling(private val plugin: Plugin, override val regionized: Boolean) : Scheduling {

    /** Repeating tasks still running; one-shot tasks are short and need no tracking. */
    private val live: MutableSet<PaperHandle> = ConcurrentHashMap.newKeySet()

    private val server get() = plugin.server

    private fun tick(t: Long): Long = t.coerceAtLeast(1L)

    override fun runForEntity(entity: Entity, task: (Entity) -> Unit, retired: (() -> Unit)?) {
        val scheduled = entity.scheduler.run(plugin, Consumer { task(entity) }, retired?.let { Runnable { it() } })
        if (scheduled == null) retired?.invoke()
    }

    override fun runForEntityTimer(
        entity: Entity,
        delayTicks: Long,
        periodTicks: Long,
        task: (TaskHandle) -> Unit,
        retired: (() -> Unit)?,
    ): TaskHandle? {
        val handle = PaperHandle(live)
        val scheduled = entity.scheduler.runAtFixedRate(
            plugin,
            Consumer { st -> handle.run(st, task) },
            Runnable {
                live.remove(handle)
                retired?.invoke()
            },
            tick(delayTicks),
            tick(periodTicks),
        )
        if (scheduled == null) {
            retired?.invoke()
            return null
        }
        return handle.attach(scheduled)
    }

    override fun runAt(location: Location, task: () -> Unit) {
        server.regionScheduler.run(plugin, location, Consumer { task() })
    }

    override fun runAtTimer(location: Location, delayTicks: Long, periodTicks: Long, task: (TaskHandle) -> Unit): TaskHandle {
        val handle = PaperHandle(live)
        return handle.attach(
            server.regionScheduler.runAtFixedRate(
                plugin, location, Consumer { st -> handle.run(st, task) }, tick(delayTicks), tick(periodTicks),
            ),
        )
    }

    override fun runGlobal(task: () -> Unit) {
        server.globalRegionScheduler.run(plugin, Consumer { task() })
    }

    override fun runGlobalTimer(delayTicks: Long, periodTicks: Long, task: (TaskHandle) -> Unit): TaskHandle {
        val handle = PaperHandle(live)
        return handle.attach(
            server.globalRegionScheduler.runAtFixedRate(
                plugin, Consumer { st -> handle.run(st, task) }, tick(delayTicks), tick(periodTicks),
            ),
        )
    }

    override fun runAsync(task: () -> Unit) {
        server.asyncScheduler.runNow(plugin, Consumer { task() })
    }

    /**
     * Folia/Lecithin refuse a plain `teleport` (the destination may belong to another region), so they get
     * `teleportAsync`. Plain Paper keeps the synchronous teleport, so an animation still moves its
     * entities within the same tick there.
     */
    override fun teleport(entity: Entity, location: Location): CompletableFuture<Boolean> =
        if (regionized) entity.teleportAsync(location) else CompletableFuture.completedFuture(entity.teleport(location))

    override fun isOwnedHere(entity: Entity): Boolean = server.isOwnedByCurrentRegion(entity)

    override fun isOwnedHere(location: Location): Boolean = server.isOwnedByCurrentRegion(location)

    /** `isOwnedByCurrentRegion(location, n)` covers the square of chunks within n of the centre chunk. */
    override fun ownedRadius(center: Location, radius: Double): Double {
        var chunks = ceil(radius / 16.0).toInt()
        while (chunks >= 0) {
            if (server.isOwnedByCurrentRegion(center, chunks)) return minOf(radius, chunks * 16.0)
            chunks--
        }
        return -1.0
    }

    override fun cancelAll() {
        live.toList().forEach { it.cancel() }
        server.globalRegionScheduler.cancelTasks(plugin)
        server.asyncScheduler.cancelTasks(plugin)
    }

    /**
     * Wraps a `ScheduledTask` that may not exist yet when the body first runs (a timer firing before
     * `attach` returns, or cancelling itself on that first run), so cancellation is remembered.
     */
    private class PaperHandle(private val live: MutableSet<PaperHandle>) : TaskHandle {
        @Volatile private var task: ScheduledTask? = null
        @Volatile private var cancelled = false

        fun attach(t: ScheduledTask): PaperHandle {
            task = t
            if (cancelled) t.cancel() else live.add(this)
            return this
        }

        /** The timer callback: hands the task its own handle, and ends quietly once cancelled. */
        fun run(st: ScheduledTask, body: (TaskHandle) -> Unit) {
            if (task == null) task = st
            if (cancelled) {
                st.cancel()
                return
            }
            body(this)
        }

        override fun cancel() {
            cancelled = true
            live.remove(this)
            task?.cancel()
        }

        override val isCancelled: Boolean get() = cancelled
    }
}
