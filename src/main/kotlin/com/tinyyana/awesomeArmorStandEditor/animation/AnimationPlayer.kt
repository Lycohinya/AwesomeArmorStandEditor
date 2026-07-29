package com.tinyyana.awesomeArmorStandEditor.animation

import com.tinyyana.awesomeArmorStandEditor.AwesomeArmorStandEditorPlugin
import com.tinyyana.awesomeArmorStandEditor.edit.InterpolationOps
import com.tinyyana.awesomeArmorStandEditor.model.Animation
import com.tinyyana.awesomeArmorStandEditor.session.EditSession
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Display
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Live keyframe playback over a session's placed entities. Displays get client-side interpolation
 * (cheap); armor stands are set per tick (server cost — so playback runs only for the editing
 * session that started it, and stopping restores the model state).
 */
class AnimationPlayer(private val plugin: AwesomeArmorStandEditorPlugin) {

    private val playing = ConcurrentHashMap<UUID, ScheduledTask>()

    fun isPlaying(playerId: UUID): Boolean = playing.containsKey(playerId)

    /**
     * Folia: 一個 scene 的所有實體都放在 `session.origin` 附近,所以正確的擁有者是
     * **擁有 origin 的那個 region**,不是編輯者(玩家會走開,實體不會)。origin 還沒定
     * 就沒有東西可以動,直接拒絕播放,而不是猜一個 context。
     * 另外 Folia 的 `runAtFixedRate` 初始延遲最小是 1 tick(原本是 0)。
     */
    fun play(session: EditSession): Boolean {
        val anim = session.scene.animation ?: return false
        if (anim.tracks.isEmpty() || anim.lengthTicks <= 0) return false
        val origin = session.origin ?: return false
        stop(session)
        var t = 0
        val task = plugin.server.regionScheduler.runAtFixedRate(plugin, origin, {
            // Self-cancel if the editor left — otherwise a looping animation ticks forever.
            if (plugin.server.getPlayer(session.playerId) == null) {
                stopSilently(session.playerId)
                return@runAtFixedRate
            }
            applyFrame(session, anim, t)
            t++
            if (t > anim.lengthTicks) {
                if (anim.loop) t = 0 else stop(session)
            }
        }, 1L, 1L)
        playing[session.playerId] = task
        return true
    }

    fun stop(session: EditSession) {
        val task = playing.remove(session.playerId) ?: return
        task.cancel()
        // Folia: restore 會碰 scene 的實體;stop 可能是從指令(玩家所在 region)呼叫的,
        // 不保證就是 origin 的擁有者,所以一律跳過去。
        val origin = session.origin ?: return
        plugin.server.regionScheduler.execute(plugin, origin) { restore(session) }
    }

    fun stopSilently(playerId: UUID) {
        playing.remove(playerId)?.cancel()
    }

    private fun applyFrame(session: EditSession, anim: Animation, t: Int) {
        for (track in anim.tracks) {
            val entity = session.entities[track.elementLocalId] ?: continue
            if (!entity.isValid) continue
            val kf = InterpolationOps.sample(track.sorted(), t)
            when {
                entity is ArmorStand && kf.pose != null -> plugin.placement.applyPose(entity, kf.pose)
                entity is Display && kf.transform != null -> plugin.placement.applyDisplayTransform(entity, kf.transform, 1)
            }
            val off = kf.offset
            val origin = session.origin
            // Folia: 同步 teleport 只在同 region 成立,teleportAsync 是唯一通用安全的做法
            if (off != null && origin != null) entity.teleportAsync(origin.clone().add(off.x, off.y, off.z))
        }
    }

    /** Put entities back to their saved model state after playback. */
    private fun restore(session: EditSession) {
        for ((localId, entity) in session.entities) {
            if (!entity.isValid) continue
            val element = session.scene.elements.find { it.localId == localId } ?: continue
            plugin.placement.apply(entity, element)
            session.origin?.let { entity.teleportAsync(plugin.placement.elementLocation(it, element)) }
        }
    }
}
