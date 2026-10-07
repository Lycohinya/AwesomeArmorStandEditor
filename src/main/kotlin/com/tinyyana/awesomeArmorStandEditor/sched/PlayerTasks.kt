package com.tinyyana.awesomeArmorStandEditor.sched

import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.lang.reflect.Method
import java.util.function.Consumer

/**
 * Runs a task on the thread that owns a player, for work that comes back from another thread
 * (remote downloads) or must wait one tick (closing an inventory first).
 *
 * Plain Bukkit's `runTask` is "the main thread", which on Folia-style servers (Lecithin's legacy
 * runtime included) is a lane that does not own any region: spawning an entity there throws
 * "Asynchronous entity add!". Paper and its forks expose a per-entity scheduler that runs on the
 * owning region; it is not Bukkit/Spigot API, so it is reached by reflection and used only when
 * present. Spigot keeps the plain scheduler.
 */
object PlayerTasks {

    private class EntityScheduling(val getScheduler: Method, val run: Method)

    private val entityScheduling: EntityScheduling? = runCatching {
        val schedulerType = Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler")
        EntityScheduling(
            getScheduler = Entity::class.java.getMethod("getScheduler"),
            run = schedulerType.getMethod("run", Plugin::class.java, Consumer::class.java, Runnable::class.java),
        )
    }.getOrNull()

    /** Runs [task] for [player] on its owning thread; dropped if the player has left by then. */
    fun run(plugin: Plugin, player: Player, task: (Player) -> Unit) {
        val playerId = player.uniqueId
        val body = Runnable {
            val p = plugin.server.getPlayer(playerId) ?: return@Runnable
            task(p)
        }
        val es = entityScheduling
        if (es != null) {
            val scheduled = runCatching {
                // Returns null when the entity is already retired (player gone): nothing to do.
                es.run.invoke(es.getScheduler.invoke(player), plugin, Consumer<Any?> { body.run() }, null)
            }
            if (scheduled.isSuccess) return
        }
        plugin.server.scheduler.runTask(plugin, body)
    }
}
