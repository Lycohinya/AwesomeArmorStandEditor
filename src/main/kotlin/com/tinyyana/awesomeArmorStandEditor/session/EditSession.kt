package com.tinyyana.awesomeArmorStandEditor.session

import com.tinyyana.awesomeArmorStandEditor.edit.Axis
import com.tinyyana.awesomeArmorStandEditor.edit.BodyPart
import com.tinyyana.awesomeArmorStandEditor.model.Element
import com.tinyyana.awesomeArmorStandEditor.model.Scene
import org.bukkit.entity.Entity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * What the current edit tool click adjusts.
 * MOVE (element world position) applies to both types; POSE is armor-stand only;
 * TRANSLATE/ROTATE/SCALE (the display's internal transform) are Display only.
 */
enum class EditMode { MOVE, POSE, TRANSLATE, ROTATE, SCALE }

/**
 * Per-player editing state for the scene they currently have open. The [Scene] model is
 * authoritative; live entities are a view kept in sync ([entities] maps element localId -> entity UUID).
 */
class EditSession(
    val playerId: UUID,
    var scene: Scene,
    /**
     * The placed copy this session edits. Every entity it spawns (elements and emitter markers) is
     * stamped with this id, so two copies of one saved scene never answer for each other.
     */
    val placementId: String = UUID.randomUUID().toString(),
) {
    /** World anchor this scene's element offsets are relative to. Set when the scene is placed. */
    var origin: org.bukkit.Location? = null
    var selectedLocalId: Int? = null
    var part: BodyPart = BodyPart.HEAD
    var axis: Axis = Axis.Y
    var mode: EditMode = EditMode.POSE

    /** One step index per mode family; the listener maps it onto the settings' step arrays. */
    var rotStepIndex: Int = 1
    var transStepIndex: Int = 1
    var scaleStepIndex: Int = 1

    var dirty: Boolean = false

    /** Single-slot undo: the state of the last-adjusted element right before its most recent adjust. */
    var undoSnapshot: UndoSnapshot? = null

    /** Live entity view of the scene (element localId -> spawned entity). Valid while chunks loaded. */
    val entities: MutableMap<Int, Entity> = ConcurrentHashMap()

    fun selected(): Element? = selectedLocalId?.let { id -> scene.elements.find { it.localId == id } }
}

class EditSessionManager {
    private val sessions = ConcurrentHashMap<UUID, EditSession>()

    fun get(player: UUID): EditSession? = sessions[player]

    fun open(player: UUID, scene: Scene, placementId: String = UUID.randomUUID().toString()): EditSession =
        EditSession(player, scene, placementId).also { sessions[player] = it }

    fun close(player: UUID): EditSession? = sessions.remove(player)

    fun all(): Collection<EditSession> = sessions.values
}
