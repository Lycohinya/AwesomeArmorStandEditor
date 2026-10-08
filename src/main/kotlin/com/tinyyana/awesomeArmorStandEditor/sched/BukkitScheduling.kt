package com.tinyyana.awesomeArmorStandEditor.sched

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.CompletableFuture

/**
 * The plain BukkitScheduler implementation, used on Spigot and on any server without the Paper scheduler
 * API. There is a single main thread, so "owned by this thread" always means [Bukkit.isPrimaryThread] and
 * the entity/location arguments only decide whether a task is still worth running.
 */
class BukkitScheduling(private val plugin: Plugin) : Scheduling {

    override val regionized: Boolean get() = false

    private val scheduler get() = plugin.server.scheduler

    /** A player counts as gone when offline (a dead player is still ours); anything else when invalid. */
    private fun alive(entity: Entity): Boolean = if (entity is Player) entity.isOnline else entity.isValid

    override fun runForEntity(entity: Entity, task: (Entity) -> Unit, retired: (() -> Unit)?) {
        if (!alive(entity)) {
            retired?.invoke()
            return
        }
        scheduler.runTask(plugin, Runnable {
            if (alive(entity)) task(entity) else retired?.invoke()
        })
    }

    override fun runForEntityTimer(
        entity: Entity,
        delayTicks: Long,
        periodTicks: Long,
        task: (TaskHandle) -> Unit,
        retired: (() -> Unit)?,
    ): TaskHandle? {
        if (!alive(entity)) {
            retired?.invoke()
            return null
        }
        val handle = BukkitHandle()
        handle.attach(scheduler.runTaskTimer(plugin, Runnable {
            if (handle.isCancelled) return@Runnable
            if (!alive(entity)) {
                handle.cancel()
                retired?.invoke()
                return@Runnable
            }
            task(handle)
        }, delayTicks.coerceAtLeast(0L), periodTicks.coerceAtLeast(1L)))
        return handle
    }

    override fun runAt(location: Location, task: () -> Unit) {
        scheduler.runTask(plugin, Runnable { task() })
    }

    override fun runAtTimer(location: Location, delayTicks: Long, periodTicks: Long, task: (TaskHandle) -> Unit): TaskHandle =
        runGlobalTimer(delayTicks, periodTicks, task)

    override fun runGlobal(task: () -> Unit) {
        scheduler.runTask(plugin, Runnable { task() })
    }

    override fun runGlobalTimer(delayTicks: Long, periodTicks: Long, task: (TaskHandle) -> Unit): TaskHandle {
        val handle = BukkitHandle()
        handle.attach(scheduler.runTaskTimer(plugin, Runnable {
            if (!handle.isCancelled) task(handle)
        }, delayTicks.coerceAtLeast(0L), periodTicks.coerceAtLeast(1L)))
        return handle
    }

    override fun runAsync(task: () -> Unit) {
        scheduler.runTaskAsynchronously(plugin, Runnable { task() })
    }

    override fun teleport(entity: Entity, location: Location): CompletableFuture<Boolean> =
        CompletableFuture.completedFuture(entity.teleport(location))

    override fun isOwnedHere(entity: Entity): Boolean = Bukkit.isPrimaryThread()

    override fun isOwnedHere(location: Location): Boolean = Bukkit.isPrimaryThread()

    override fun ownedRadius(center: Location, radius: Double): Double = radius

    override fun cancelAll() {
        scheduler.cancelTasks(plugin)
    }

    /** Cancel can arrive before the BukkitTask exists (a task cancelling itself on its first run). */
    private class BukkitHandle : TaskHandle {
        @Volatile private var task: BukkitTask? = null
        @Volatile private var cancelled = false

        fun attach(t: BukkitTask) {
            task = t
            if (cancelled) t.cancel()
        }

        override fun cancel() {
            cancelled = true
            task?.cancel()
        }

        override val isCancelled: Boolean get() = cancelled
    }
}
