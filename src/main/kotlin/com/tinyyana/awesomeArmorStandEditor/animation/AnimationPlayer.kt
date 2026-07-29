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
     * Folia:`RegionScheduler` 的 javadoc 明文寫「It is entirely inappropriate to use the
     * region scheduler to schedule tasks for entities…you should be using
     * `Entity#getScheduler()`, as the entity scheduler will "follow" an entity if it is
     * teleported, whereas the region task scheduler will not」
     * (`paper-api/.../scheduler/RegionScheduler.java:12-18`)。
     *
     * 所以這裡的分工是:**region scheduler 只當時鐘**(它不碰任何實體,只推進影格計數),
     * 每一格實際套用到實體的動作再各自派給那個實體自己的 [EntityScheduler]。
     * origin 還沒定就沒有東西可以動,直接拒絕播放,而不是猜一個 context。
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
        // restore() 自己會把每個實體的動作派給該實體的 EntityScheduler,所以這裡不需要
        // 也不應該再包一層 region hop(見 applyFrame 的 Folia 註解)。
        restore(session)
    }

    fun stopSilently(playerId: UUID) {
        playing.remove(playerId)?.cancel()
    }

    /** 只做取樣與派送,實際碰實體的動作跑在該實體自己的執行緒上。 */
    private fun applyFrame(session: EditSession, anim: Animation, t: Int) {
        for (track in anim.tracks) {
            val entity = session.entities[track.elementLocalId] ?: continue
            val kf = InterpolationOps.sample(track.sorted(), t)
            val origin = session.origin
            entity.scheduler.run(plugin, {
                if (!entity.isValid) return@run
                when {
                    entity is ArmorStand && kf.pose != null -> plugin.placement.applyPose(entity, kf.pose)
                    entity is Display && kf.transform != null -> plugin.placement.applyDisplayTransform(entity, kf.transform, 1)
                }
                val off = kf.offset
                // Folia: CraftEntity.teleport0() 無條件 throw
                // ("Must use teleportAsync while in region threading",
                //  paper-server/.../entity/CraftEntity.java:309-313),teleportAsync 是唯一路徑
                if (off != null && origin != null) entity.teleportAsync(origin.clone().add(off.x, off.y, off.z))
            }, null)
        }
    }

    /** Put entities back to their saved model state after playback. */
    private fun restore(session: EditSession) {
        for ((localId, entity) in session.entities) {
            val element = session.scene.elements.find { it.localId == localId } ?: continue
            val origin = session.origin
            entity.scheduler.run(plugin, {
                if (!entity.isValid) return@run
                plugin.placement.apply(entity, element)
                origin?.let { entity.teleportAsync(plugin.placement.elementLocation(it, element)) }
            }, null)
        }
    }
}
