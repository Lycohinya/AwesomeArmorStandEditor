package com.tinyyana.awesomeArmorStandEditor.sched

import com.tinyyana.awesomeArmorStandEditor.AwesomeArmorStandEditorPlugin
import org.bukkit.entity.Player

/**
 * Runs a task on the thread that owns a player, for work that comes back from another thread
 * (remote downloads) or must wait one tick (closing an inventory first).
 *
 * Plain Bukkit's `runTask` is "the main thread", which on Folia-style servers (Lecithin's legacy
 * runtime included) is a lane that does not own any region: spawning an entity there throws
 * "Asynchronous entity add!". The plugin's [Scheduling] hands the work to the player's owning region
 * where that exists and to the main thread on Spigot.
 */
object PlayerTasks {

    /**
     * Runs [task] for [player] on its owning thread, a tick later; dropped if the player has left by then.
     * The player is looked up again by id so a reconnect between scheduling and running still resolves.
     */
    fun run(plugin: AwesomeArmorStandEditorPlugin, player: Player, task: (Player) -> Unit) {
        val playerId = player.uniqueId
        plugin.scheduling.runForEntity(player, {
            plugin.server.getPlayer(playerId)?.let(task)
        })
    }
}
