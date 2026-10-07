package com.tinyyana.awesomeArmorStandEditor.model

/**
 * A saved scene ("blueprint"). Elements store offsets relative to the scene origin, so a scene
 * is portable: placing it supplies a world + origin location at load time.
 *
 * Runtime entities carry only PDC tags (owner/sceneId/localId); the JSON is the source of truth.
 * Animation is intentionally absent in P1 (added at P3 with a schemaVersion bump).
 */
data class Scene(
    val schemaVersion: Int = SCHEMA_VERSION,
    val id: String,
    val owner: String,          // owner UUID as string
    var name: String,
    var lastAnchor: Anchor? = null,   // where it was last authored/placed, for convenience re-placement
    val elements: MutableList<Element> = mutableListOf(),
    val emitters: MutableList<ParticleEmitter> = mutableListOf(),
    var animation: Animation? = null,
) {
    fun nextLocalId(): Int = (elements.maxOfOrNull { it.localId } ?: 0) + 1
    fun nextEmitterId(): Int = (emitters.maxOfOrNull { it.id } ?: 0) + 1

    companion object {
        /** Version written by default. The codec still reads 2 (legacy: radians + Bukkit base64). */
        const val SCHEMA_VERSION = 3
    }
}

/** Last-known placement anchor (world + block-ish origin). Not required for portability. */
data class Anchor(val world: String, val x: Double, val y: Double, val z: Double)

/** An element is one posable node in a scene. localId is unique within its scene. */
sealed interface Element {
    val localId: Int
    val offset: Vec3   // relative to scene origin
    val yaw: Float     // body yaw in degrees
}

data class ArmorStandElement(
    override val localId: Int,
    override var offset: Vec3 = Vec3.ZERO,
    override var yaw: Float = 0f,
    var pose: Pose6 = Pose6(),
    var equipment: Equipment = Equipment(),
    var flags: ArmorStandFlags = ArmorStandFlags(),
    var customName: String? = null,   // MiniMessage
) : Element

enum class DisplayKind { ITEM, BLOCK, TEXT }

data class DisplayElement(
    override val localId: Int,
    override var offset: Vec3 = Vec3.ZERO,
    override var yaw: Float = 0f,
    var kind: DisplayKind = DisplayKind.ITEM,
    var transform: Transform = Transform.IDENTITY,
    /** ITEM only: the item (null = empty). */
    var item: ItemRef? = null,
    /** BLOCK: block-data string; TEXT: MiniMessage string. Unused for ITEM. */
    var payload: String = "",
    var billboard: String = "FIXED",      // Display.Billboard name
    var brightnessBlock: Int? = null,     // 0..15, null = default
    var brightnessSky: Int? = null,
    var glowColor: Int? = null,           // ARGB, null = no override
    var viewRange: Float = 1.0f,
) : Element

/** Equipment slots (null = empty). Resolving an [ItemRef] to an ItemStack lives in the store layer. */
data class Equipment(
    var head: ItemRef? = null,
    var chest: ItemRef? = null,
    var legs: ItemRef? = null,
    var feet: ItemRef? = null,
    var mainHand: ItemRef? = null,
    var offHand: ItemRef? = null,
)

/**
 * An item, portable across servers and editable on the website.
 *
 * - [id] namespaced item id (`minecraft:diamond_sword`); null only for a legacy v2 item that has
 *   nothing but Bukkit base64.
 * - [components] item components in `/give` syntax (`[enchantments={sharpness:5}]`), starting with `[`.
 * - [bukkit] exact Bukkit-serialized base64 written by the plugin; when present the plugin uses it first.
 */
data class ItemRef(
    val id: String?,
    val count: Int = 1,
    val components: String? = null,
    val bukkit: String? = null,
) {
    companion object {
        /** A legacy v2 slot / payload: Bukkit base64 only. */
        fun legacy(base64: String) = ItemRef(id = null, bukkit = base64)
    }
}

data class ArmorStandFlags(
    var small: Boolean = false,
    var invisible: Boolean = false,
    var noBasePlate: Boolean = false,
    var noGravity: Boolean = true,   // editor default: don't fall
    var arms: Boolean = true,        // editor default: arms visible/posable
    var marker: Boolean = false,
    var glowing: Boolean = false,
)
