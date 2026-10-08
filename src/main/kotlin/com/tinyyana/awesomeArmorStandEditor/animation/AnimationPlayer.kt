package com.tinyyana.awesomeArmorStandEditor.animation

import com.tinyyana.awesomeArmorStandEditor.AwesomeArmorStandEditorPlugin
import com.tinyyana.awesomeArmorStandEditor.edit.InterpolationOps
import com.tinyyana.awesomeArmorStandEditor.model.Animation
import com.tinyyana.awesomeArmorStandEditor.sched.TaskHandle
import com.tinyyana.awesomeArmorStandEditor.session.EditSession
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Display
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Live keyframe playback over a session's placed entities. Displays get client-side interpolation
 * (cheap); armor stands are set per tick (server cost — so playback runs only for the editing
 * session that started it, and stopping restores the model state).
 *
 * Threading: the frame timer runs on the region around the scene origin, and each frame only touches
 * entities that region owns right now (a scene straddling a region border animates the part in range;
 * on Spigot everything is owned). [stop] and [restore] can be called from the player's thread, which may
 * own none of the entities, so restoring hands each entity to its owner.
 */
class AnimationPlayer(private val plugin: AwesomeArmorStandEditorPlugin) {

    private val playing = ConcurrentHashMap<UUID, TaskHandle>()

    fun isPlaying(playerId: UUID): Boolean = playing.containsKey(playerId)

    fun play(session: EditSession): Boolean {
        val anim = session.scene.animation ?: return false
        if (anim.tracks.isEmpty() || anim.lengthTicks <= 0) return false
        // The timer needs a place to run: the scene origin, else wherever its first live entity stands.
        val anchor = session.origin ?: session.entities.values.firstOrNull()?.location ?: return false
        stop(session)
        var t = 0
        val handle = plugin.scheduling.runAtTimer(anchor, 0L, 1L) {
            // Self-cancel if the editor left — otherwise a looping animation ticks forever.
            if (plugin.server.getPlayer(session.playerId) == null) {
                stopSilently(session.playerId)
                return@runAtTimer
            }
            applyFrame(session, anim, t)
            t++
            if (t > anim.lengthTicks) {
                if (anim.loop) t = 0 else stop(session)
            }
        }
        playing[session.playerId] = handle
        return true
    }

    fun stop(session: EditSession) {
        val task = playing.remove(session.playerId) ?: return
        task.cancel()
        restore(session)
    }

    fun stopSilently(playerId: UUID) {
        playing.remove(playerId)?.cancel()
    }

    private fun applyFrame(session: EditSession, anim: Animation, t: Int) {
        // A copy: the editing player (another thread on Folia) may add or drop tracks mid-playback.
        for (track in anim.tracks.toList()) {
            val entity = session.entities[track.elementLocalId] ?: continue
            if (!entity.isValid || !plugin.scheduling.isOwnedHere(entity)) continue
            val kf = InterpolationOps.sample(track.sorted(), t)
            when {
                entity is ArmorStand && kf.pose != null -> plugin.placement.applyPose(entity, kf.pose)
                entity is Display && kf.transform != null -> plugin.placement.applyDisplayTransform(entity, kf.transform, 1)
            }
            val off = kf.offset
            val origin = session.origin
            if (off != null && origin != null) plugin.scheduling.teleport(entity, origin.clone().add(off.x, off.y, off.z))
        }
    }

    /** Put entities back to their saved model state after playback. Each one is restored by its owner. */
    private fun restore(session: EditSession) {
        val elements = session.scene.elements.toList()
        for ((localId, entity) in session.entities) {
            if (!entity.isValid) continue
            val element = elements.find { it.localId == localId } ?: continue
            plugin.scheduling.onOwner(entity) {
                plugin.placement.apply(it, element)
                session.origin?.let { origin -> plugin.scheduling.teleport(it, plugin.placement.elementLocation(origin, element)) }
            }
        }
    }
}
