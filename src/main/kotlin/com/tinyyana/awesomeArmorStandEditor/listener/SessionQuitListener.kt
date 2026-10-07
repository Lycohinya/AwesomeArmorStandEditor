package com.tinyyana.awesomeArmorStandEditor.listener

import com.tinyyana.awesomeArmorStandEditor.AwesomeArmorStandEditorPlugin
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent

/**
 * Leaving ends the editing session: playback is stopped (entities restored to the model) and the
 * session dropped. Nothing is saved and nothing is removed — the placed copy stays in the world
 * and can be re-bound with /aase edit.
 */
class SessionQuitListener(private val plugin: AwesomeArmorStandEditorPlugin) : Listener {

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val id = event.player.uniqueId
        plugin.sessions.get(id)?.let { plugin.animation.stop(it) }
        plugin.sessions.close(id)
        plugin.recall.forgetPlayer(id)
    }
}
