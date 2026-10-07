package com.tinyyana.awesomeArmorStandEditor.store

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.tinyyana.awesomeArmorStandEditor.model.Anchor
import com.tinyyana.awesomeArmorStandEditor.model.Animation
import com.tinyyana.awesomeArmorStandEditor.model.ArmorStandElement
import com.tinyyana.awesomeArmorStandEditor.model.ArmorStandFlags
import com.tinyyana.awesomeArmorStandEditor.model.DisplayElement
import com.tinyyana.awesomeArmorStandEditor.model.DisplayKind
import com.tinyyana.awesomeArmorStandEditor.model.Element
import com.tinyyana.awesomeArmorStandEditor.model.Equipment
import com.tinyyana.awesomeArmorStandEditor.model.EulerXYZ
import com.tinyyana.awesomeArmorStandEditor.model.ItemRef
import com.tinyyana.awesomeArmorStandEditor.model.Keyframe
import com.tinyyana.awesomeArmorStandEditor.model.ParticleEmitter
import com.tinyyana.awesomeArmorStandEditor.model.Pose6
import com.tinyyana.awesomeArmorStandEditor.model.Quat
import com.tinyyana.awesomeArmorStandEditor.model.Scene
import com.tinyyana.awesomeArmorStandEditor.model.Track
import com.tinyyana.awesomeArmorStandEditor.model.Transform
import com.tinyyana.awesomeArmorStandEditor.model.Vec3
import org.joml.Quaterniond
import java.util.Locale
import java.util.UUID

/** Thrown by [SceneCodec.fromJson] when a document fails validation; [errors] are in document order. */
class SceneValidationException(val errors: List<SceneError>) :
    IllegalArgumentException(errors.take(3).joinToString("; ") { "${it.pointer.ifEmpty { "/" }}: ${it.message}" })

/**
 * Explicit Scene <-> JSON codec. The JSON tree is built by hand (not reflective Gson mapping) so schema
 * evolution and the polymorphic [Element] `type` discriminator stay under our control. Gson is only the
 * tree + IO layer. Pure: no Bukkit runtime (JOML is plain math).
 *
 * Reading (trust boundary):
 *  - untrusted input (the default: `/aase import` AASE1 codes, remote short codes, any outside JSON) is
 *    always strict, whatever its `schemaVersion`: [SceneValidator] first (nesting depth, finite numbers,
 *    the v3 schema — which also describes the v2 shapes — and the semantic checks; errors are
 *    `{pointer, message}`), then normalisation;
 *  - `trusted = true` is only for the plugin's own save files ([SceneStore]): `schemaVersion` absent or 2
 *    goes through the legacy v2 reader, lenient as in 1.0–1.2, so an old save never stops loading;
 *  - normalisation — poseDeg → radians, rotationDeg → quaternion (JOML `Quaterniond.rotationXYZ`),
 *    missing fields → defaults, legacy `pose`/`payload`/base64 slots kept.
 *
 * Writing: v3 by default (poseDeg rounded to 4 decimals, transform as raw quaternions, items as
 * ItemRef), or v2 via [WriteOptions.schema].
 */
object SceneCodec {

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /** Must agree with the schema's `block` pattern; a block string that doesn't match is written as `payload`. */
    private val BLOCK_PATTERN = Regex("^(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+(?:\\[[^\\]]*])?\\z")
    private const val MAX_NAME = 64
    private const val MAX_CUSTOM_NAME = 256
    private const val MAX_TEXT = 1024

    sealed interface DecodeResult {
        data class Ok(val scene: Scene) : DecodeResult
        data class Invalid(val errors: List<SceneError>) : DecodeResult
    }

    /**
     * @param schema 3 (default) or 2.
     * @param includeIdentity false strips `id`, `owner` and `lastAnchor` (sharing to the website).
     * @param legacyItemEncoder v2 only: turns an ItemRef without `bukkit` into Bukkit base64 (needs the
     *   server; null in pure code). A slot it can't encode is left out and reported to [onWarning].
     */
    data class WriteOptions(
        val schema: Int = Scene.SCHEMA_VERSION,
        val includeIdentity: Boolean = true,
        val legacyItemEncoder: ((ItemRef) -> String?)? = null,
        val onWarning: ((String) -> Unit)? = null,
    )

    // --- public API ---

    fun toJson(scene: Scene, options: WriteOptions = WriteOptions()): String = gson.toJson(toTree(scene, options))

    fun toTree(scene: Scene, options: WriteOptions = WriteOptions()): JsonObject {
        if (options.schema == 2) return sceneToV2(scene, options)
        val v3 = sceneToV3(scene, options)
        // A scene this plugin wrote must always read back. If something can't be expressed in v3
        // (e.g. more than 200 elements via the limit bypass), keep the data and fall back to v2.
        val errors = SceneValidator.validate(v3)
        if (errors.isEmpty()) return v3
        options.onWarning?.invoke("scene '${scene.name}' can't be written as v3 (${errors.first().pointer}: ${errors.first().message}); writing v2")
        return sceneToV2(scene, options)
    }

    /** @param trusted true only for the plugin's own save files; see the class comment. */
    fun decode(text: String, trusted: Boolean = false): DecodeResult {
        val doc = try {
            SceneValidator.parseStrict(text)
        } catch (e: JsonTooDeepException) {
            return DecodeResult.Invalid(listOf(SceneError("", SceneValidator.TOO_DEEP)))
        } catch (e: Exception) {
            return DecodeResult.Invalid(listOf(SceneError("", "invalid JSON: ${e.message}")))
        }
        return decodeTree(doc, trusted)
    }

    fun decodeTree(doc: JsonElement, trusted: Boolean = false): DecodeResult {
        if (trusted && doc is JsonObject && isLegacy(doc)) {
            return try {
                DecodeResult.Ok(v2ToScene(doc))
            } catch (e: Exception) {
                DecodeResult.Invalid(listOf(SceneError("", "invalid v2 scene: ${e.message}")))
            }
        }
        val errors = SceneValidator.validate(doc)
        if (errors.isNotEmpty()) return DecodeResult.Invalid(errors)
        return DecodeResult.Ok(v3ToScene(doc.asJsonObject))
    }

    /** Throws [SceneValidationException] on an invalid document. */
    fun fromJson(text: String, trusted: Boolean = false): Scene = when (val r = decode(text, trusted)) {
        is DecodeResult.Ok -> r.scene
        is DecodeResult.Invalid -> throw SceneValidationException(r.errors)
    }

    private fun isLegacy(o: JsonObject): Boolean {
        val v = o.get("schemaVersion") ?: return true
        return v is JsonPrimitive && v.isNumber && v.asBigDecimal.compareTo(java.math.BigDecimal(2)) == 0
    }

    // =============================================================================================
    // v3 read
    // =============================================================================================

    private fun v3ToScene(o: JsonObject): Scene = Scene(
        schemaVersion = Scene.SCHEMA_VERSION,
        id = o.get("id")?.asString ?: UUID.randomUUID().toString(),
        owner = o.get("owner")?.asString ?: "",
        name = o.get("name").asString,
        lastAnchor = o.getAsJsonObject("lastAnchor")?.let { objectToAnchor(it) },
        elements = o.getAsJsonArray("elements").map { v3Element(it.asJsonObject) }.toMutableList(),
        emitters = o.getAsJsonArray("emitters")?.map { objectToEmitter(it.asJsonObject) }?.toMutableList() ?: mutableListOf(),
        animation = o.getAsJsonObject("animation")?.let { objectToAnimation(it) },
    )

    private fun v3Element(o: JsonObject): Element {
        val localId = o.get("localId").asInt
        val offset = vec3(o.getAsJsonArray("offset"))
        val yaw = o.get("yaw")?.asFloat ?: 0f
        return when (o.get("type").asString) {
            "armor_stand" -> ArmorStandElement(
                localId = localId, offset = offset, yaw = yaw,
                pose = o.getAsJsonObject("poseDeg")?.let { poseFromDegrees(it) }
                    ?: o.getAsJsonObject("pose")?.let { partialPose(it) }
                    ?: Pose6(),
                equipment = o.getAsJsonObject("equipment")?.let { v3Equipment(it) } ?: Equipment(),
                flags = o.getAsJsonObject("flags")?.let { objectToFlags(it) } ?: ArmorStandFlags(),
                customName = o.get("customName")?.asString,
            )
            else -> {
                val kind = DisplayKind.valueOf(o.get("kind").asString)
                val legacyPayload = o.get("payload")?.asString
                DisplayElement(
                    localId = localId, offset = offset, yaw = yaw, kind = kind,
                    transform = o.getAsJsonObject("transform")?.let { v3Transform(it) } ?: Transform.IDENTITY,
                    item = if (kind == DisplayKind.ITEM) {
                        o.getAsJsonObject("item")?.let { itemRef(it) }
                            ?: legacyPayload?.takeIf { it.isNotBlank() }?.let { ItemRef.legacy(it) }
                    } else null,
                    payload = when (kind) {
                        DisplayKind.ITEM -> ""
                        DisplayKind.BLOCK -> o.get("block")?.asString ?: legacyPayload ?: ""
                        DisplayKind.TEXT -> o.get("text")?.asString ?: legacyPayload ?: ""
                    },
                    billboard = o.get("billboard")?.asString ?: "FIXED",
                    brightnessBlock = o.get("brightnessBlock")?.asInt,
                    brightnessSky = o.get("brightnessSky")?.asInt,
                    glowColor = o.get("glowColor")?.asInt,
                    viewRange = o.get("viewRange")?.asFloat ?: 1.0f,
                )
            }
        }
    }

    private fun v3Equipment(o: JsonObject): Equipment {
        fun slot(key: String): ItemRef? = when (val v = o.get(key)) {
            null -> null
            is JsonObject -> itemRef(v)
            else -> ItemRef.legacy(v.asString)
        }
        return Equipment(
            head = slot("head"), chest = slot("chest"), legs = slot("legs"),
            feet = slot("feet"), mainHand = slot("mainHand"), offHand = slot("offHand"),
        )
    }

    private fun itemRef(o: JsonObject) = ItemRef(
        id = o.get("id").asString,
        count = o.get("count")?.asInt ?: 1,
        components = o.get("components")?.asString,
        bukkit = o.get("bukkit")?.asString,
    )

    private fun v3Transform(o: JsonObject): Transform = Transform(
        translation = o.getAsJsonArray("translation")?.let { vec3(it) } ?: Vec3.ZERO,
        leftRotation = o.getAsJsonArray("rotationDeg")?.let { quatFromEulerXYZDeg(vec3(it)) }
            ?: o.getAsJsonArray("leftRotation")?.let { quat(it) }
            ?: Quat.IDENTITY,
        scale = o.getAsJsonArray("scale")?.let { vec3(it) } ?: Vec3.ONE,
        rightRotation = o.getAsJsonArray("rightRotation")?.let { quat(it) } ?: Quat.IDENTITY,
    )

    /** Euler degrees (order XYZ) → quaternion qx·qy·qz, computed in double like the website. */
    fun quatFromEulerXYZDeg(deg: Vec3): Quat {
        val q = Quaterniond().rotationXYZ(Math.toRadians(deg.x), Math.toRadians(deg.y), Math.toRadians(deg.z))
        return Quat(q.x, q.y, q.z, q.w)
    }

    private fun poseFromDegrees(o: JsonObject): Pose6 {
        fun part(key: String) = o.getAsJsonArray(key)?.let {
            EulerXYZ(Math.toRadians(it[0].asDouble), Math.toRadians(it[1].asDouble), Math.toRadians(it[2].asDouble))
        } ?: EulerXYZ.ZERO
        return Pose6(part("head"), part("body"), part("leftArm"), part("rightArm"), part("leftLeg"), part("rightLeg"))
    }

    private fun partialPose(o: JsonObject): Pose6 {
        fun part(key: String) = o.getAsJsonArray(key)?.let { euler(it) } ?: EulerXYZ.ZERO
        return Pose6(part("head"), part("body"), part("leftArm"), part("rightArm"), part("leftLeg"), part("rightLeg"))
    }

    // =============================================================================================
    // v3 write
    // =============================================================================================

    private fun sceneToV3(scene: Scene, options: WriteOptions): JsonObject = JsonObject().apply {
        addProperty("schemaVersion", 3)
        if (options.includeIdentity) {
            scene.id.takeIf { it.isNotBlank() }?.let { addProperty("id", it) }
            scene.owner.takeIf { it.isNotBlank() }?.let { addProperty("owner", it) }
        }
        addProperty("name", clip(scene.name, MAX_NAME).ifEmpty { "scene" })
        if (options.includeIdentity) scene.lastAnchor?.let { add("lastAnchor", anchorToObject(it)) }
        add("elements", JsonArray().apply { scene.elements.forEach { add(v3ElementToObject(it)) } })
        if (scene.emitters.isNotEmpty()) add("emitters", JsonArray().apply { scene.emitters.forEach { add(emitterToObject(it)) } })
        scene.animation?.let { add("animation", animationToObject(it)) }
    }

    private fun v3ElementToObject(el: Element): JsonObject = JsonObject().apply {
        when (el) {
            is ArmorStandElement -> {
                addProperty("type", "armor_stand")
                addProperty("localId", el.localId)
                add("offset", vec3(el.offset))
                addProperty("yaw", el.yaw)
                add("poseDeg", poseToDegrees(el.pose))
                v3EquipmentToObject(el.equipment)?.let { add("equipment", it) }
                add("flags", flagsToObject(el.flags))
                el.customName?.let { addProperty("customName", clip(it, MAX_CUSTOM_NAME)) }
            }
            is DisplayElement -> {
                addProperty("type", "display")
                addProperty("localId", el.localId)
                add("offset", vec3(el.offset))
                addProperty("yaw", el.yaw)
                addProperty("kind", el.kind.name)
                add("transform", JsonObject().apply {
                    add("translation", vec3(el.transform.translation))
                    add("scale", vec3(el.transform.scale))
                    add("leftRotation", quat(el.transform.leftRotation))
                    add("rightRotation", quat(el.transform.rightRotation))
                })
                when (el.kind) {
                    DisplayKind.ITEM -> {
                        val item = el.item
                        when {
                            item?.id != null -> add("item", itemRefToObject(item))
                            else -> addProperty("payload", item?.bukkit ?: "")
                        }
                    }
                    // Content the schema can't hold as block/text (odd block string, very long text) is
                    // kept verbatim in the legacy payload field rather than dropped or clipped.
                    DisplayKind.BLOCK ->
                        if (BLOCK_PATTERN.containsMatchIn(el.payload)) addProperty("block", el.payload) else addProperty("payload", el.payload)
                    DisplayKind.TEXT ->
                        if (codePoints(el.payload) <= MAX_TEXT) addProperty("text", el.payload) else addProperty("payload", el.payload)
                }
                addProperty("billboard", el.billboard)
                el.brightnessBlock?.let { addProperty("brightnessBlock", it) }
                el.brightnessSky?.let { addProperty("brightnessSky", it) }
                el.glowColor?.let { addProperty("glowColor", it) }
                addProperty("viewRange", el.viewRange)
            }
        }
    }

    private fun v3EquipmentToObject(e: Equipment): JsonObject? {
        val o = JsonObject()
        fun slot(key: String, ref: ItemRef?) {
            ref ?: return
            if (ref.id != null) o.add(key, itemRefToObject(ref)) else ref.bukkit?.let { o.addProperty(key, it) }
        }
        slot("head", e.head); slot("chest", e.chest); slot("legs", e.legs)
        slot("feet", e.feet); slot("mainHand", e.mainHand); slot("offHand", e.offHand)
        return o.takeIf { it.size() > 0 }
    }

    private fun itemRefToObject(r: ItemRef) = JsonObject().apply {
        addProperty("id", r.id)
        addProperty("count", r.count.coerceIn(1, 99))
        ItemRefs.normalizeComponents(r.components)?.let { addProperty("components", it) }
        r.bukkit?.takeIf { it.isNotEmpty() }?.let { addProperty("bukkit", it) }
    }

    private fun poseToDegrees(p: Pose6) = JsonObject().apply {
        fun part(e: EulerXYZ) = JsonArray().apply { add(deg4(e.x)); add(deg4(e.y)); add(deg4(e.z)) }
        add("head", part(p.head)); add("body", part(p.body))
        add("leftArm", part(p.leftArm)); add("rightArm", part(p.rightArm))
        add("leftLeg", part(p.leftLeg)); add("rightLeg", part(p.rightLeg))
    }

    /**
     * Radians → degrees rounded to 4 decimals exactly like the exporters' `%.4f` (and the website's
     * `round4`): Java's Formatter rounds the shortest round-trip digits half-up.
     */
    fun deg4(rad: Double): Double = round4(Math.toDegrees(rad))

    fun round4(v: Double): Double = if (!v.isFinite()) v else String.format(Locale.ROOT, "%.4f", v).toDouble()

    private fun clip(s: String, maxCodePoints: Int): String =
        if (codePoints(s) <= maxCodePoints) s else s.substring(0, s.offsetByCodePoints(0, maxCodePoints))

    private fun codePoints(s: String) = s.codePointCount(0, s.length)

    // =============================================================================================
    // v2 read (lenient, as in 1.0–1.2)
    // =============================================================================================

    private fun v2ToScene(o: JsonObject): Scene = Scene(
        schemaVersion = Scene.SCHEMA_VERSION,
        id = o.get("id")?.asString ?: UUID.randomUUID().toString(),
        owner = o.get("owner")?.asString ?: "",
        name = o.get("name").asString,
        lastAnchor = o.getAsJsonObject("lastAnchor")?.let { objectToAnchor(it) },
        elements = o.getAsJsonArray("elements")?.map { v2Element(it.asJsonObject) }?.toMutableList() ?: mutableListOf(),
        emitters = o.getAsJsonArray("emitters")?.map { objectToEmitter(it.asJsonObject) }?.toMutableList() ?: mutableListOf(),
        animation = o.getAsJsonObject("animation")?.let { objectToAnimation(it) },
    )

    private fun v2Element(o: JsonObject): Element {
        val localId = o.get("localId").asInt
        val offset = vec3(o.getAsJsonArray("offset"))
        val yaw = o.get("yaw")?.asFloat ?: 0f
        return when (o.get("type").asString) {
            "armor_stand" -> ArmorStandElement(
                localId = localId, offset = offset, yaw = yaw,
                pose = o.getAsJsonObject("pose")?.let { objectToPose(it) } ?: Pose6(),
                equipment = o.getAsJsonObject("equipment")?.let { v2Equipment(it) } ?: Equipment(),
                flags = o.getAsJsonObject("flags")?.let { objectToFlags(it) } ?: ArmorStandFlags(),
                customName = o.get("customName")?.asString,
            )
            "display" -> {
                val kind = DisplayKind.valueOf(o.get("kind").asString)
                val payload = o.get("payload")?.asString ?: ""
                DisplayElement(
                    localId = localId, offset = offset, yaw = yaw, kind = kind,
                    transform = o.getAsJsonObject("transform")?.let { objectToTransform(it) } ?: Transform.IDENTITY,
                    item = if (kind == DisplayKind.ITEM) payload.takeIf { it.isNotBlank() }?.let { ItemRef.legacy(it) } else null,
                    payload = if (kind == DisplayKind.ITEM) "" else payload,
                    billboard = o.get("billboard")?.asString ?: "FIXED",
                    brightnessBlock = o.get("brightnessBlock")?.asInt,
                    brightnessSky = o.get("brightnessSky")?.asInt,
                    glowColor = o.get("glowColor")?.asInt,
                    viewRange = o.get("viewRange")?.asFloat ?: 1.0f,
                )
            }
            else -> error("Unknown element type: ${o.get("type")}")
        }
    }

    private fun v2Equipment(o: JsonObject) = Equipment(
        head = o.get("head")?.asString?.let { ItemRef.legacy(it) }, chest = o.get("chest")?.asString?.let { ItemRef.legacy(it) },
        legs = o.get("legs")?.asString?.let { ItemRef.legacy(it) }, feet = o.get("feet")?.asString?.let { ItemRef.legacy(it) },
        mainHand = o.get("mainHand")?.asString?.let { ItemRef.legacy(it) }, offHand = o.get("offHand")?.asString?.let { ItemRef.legacy(it) },
    )

    // =============================================================================================
    // v2 write (store.write-schema: 2)
    // =============================================================================================

    private fun sceneToV2(scene: Scene, options: WriteOptions): JsonObject = JsonObject().apply {
        addProperty("schemaVersion", 2)
        if (options.includeIdentity) {
            addProperty("id", scene.id)
            addProperty("owner", scene.owner)
        }
        addProperty("name", scene.name)
        if (options.includeIdentity) scene.lastAnchor?.let { add("lastAnchor", anchorToObject(it)) }
        add("elements", JsonArray().apply { scene.elements.forEach { add(v2ElementToObject(scene, it, options)) } })
        if (scene.emitters.isNotEmpty()) add("emitters", JsonArray().apply { scene.emitters.forEach { add(emitterToObject(it)) } })
        scene.animation?.let { add("animation", animationToObject(it)) }
    }

    /** ItemRef → Bukkit base64 for v2: the exact `bukkit` form, else the injected runtime encoder. */
    private fun legacyItem(scene: Scene, where: String, ref: ItemRef, options: WriteOptions): String? {
        ref.bukkit?.takeIf { it.isNotEmpty() }?.let { return it }
        val encoded = options.legacyItemEncoder?.invoke(ref)
        if (encoded == null) options.onWarning?.invoke("scene '${scene.name}' $where: ${ref.id} can't be written as v2 Bukkit base64; slot left empty")
        return encoded
    }

    private fun v2ElementToObject(scene: Scene, el: Element, options: WriteOptions): JsonObject {
        val o = JsonObject()
        o.addProperty("localId", el.localId)
        o.add("offset", vec3(el.offset))
        o.addProperty("yaw", el.yaw)
        when (el) {
            is ArmorStandElement -> {
                o.addProperty("type", "armor_stand")
                o.add("pose", poseToObject(el.pose))
                val eq = JsonObject()
                fun slot(key: String, ref: ItemRef?) {
                    ref ?: return
                    legacyItem(scene, "#${el.localId} $key", ref, options)?.let { eq.addProperty(key, it) }
                }
                val e = el.equipment
                slot("head", e.head); slot("chest", e.chest); slot("legs", e.legs)
                slot("feet", e.feet); slot("mainHand", e.mainHand); slot("offHand", e.offHand)
                o.add("equipment", eq)
                o.add("flags", flagsToObject(el.flags))
                el.customName?.let { o.addProperty("customName", it) }
            }
            is DisplayElement -> {
                o.addProperty("type", "display")
                o.addProperty("kind", el.kind.name)
                o.add("transform", transformToObject(el.transform))
                val payload = when (el.kind) {
                    DisplayKind.ITEM -> el.item?.let { legacyItem(scene, "#${el.localId} item", it, options) } ?: ""
                    else -> el.payload
                }
                o.addProperty("payload", payload)
                o.addProperty("billboard", el.billboard)
                el.brightnessBlock?.let { o.addProperty("brightnessBlock", it) }
                el.brightnessSky?.let { o.addProperty("brightnessSky", it) }
                el.glowColor?.let { o.addProperty("glowColor", it) }
                o.addProperty("viewRange", el.viewRange)
            }
        }
        return o
    }

    // =============================================================================================
    // shared shapes (emitters, animation, anchor, flags — identical in v2 and v3)
    // =============================================================================================

    private fun emitterToObject(e: ParticleEmitter) = JsonObject().apply {
        addProperty("id", e.id); addProperty("particle", e.particle)
        add("offset", vec3(e.offset)); addProperty("count", e.count)
        add("spread", vec3(e.spread)); addProperty("speed", e.speed)
        addProperty("rateTicks", e.rateTicks); addProperty("dustColor", e.dustColor)
    }

    private fun objectToEmitter(o: JsonObject) = ParticleEmitter(
        id = o.get("id").asInt,
        particle = o.get("particle")?.asString ?: "HAPPY_VILLAGER",
        offset = o.getAsJsonArray("offset")?.let { vec3(it) } ?: Vec3.ZERO,
        count = o.get("count")?.asInt ?: 5,
        spread = o.getAsJsonArray("spread")?.let { vec3(it) } ?: Vec3(0.2, 0.2, 0.2),
        speed = o.get("speed")?.asDouble ?: 0.0,
        rateTicks = o.get("rateTicks")?.asInt ?: 10,
        dustColor = o.get("dustColor")?.asInt ?: 0xFFB7D5,
    )

    private fun animationToObject(a: Animation) = JsonObject().apply {
        addProperty("lengthTicks", a.lengthTicks)
        addProperty("loop", a.loop)
        val tracks = JsonArray()
        a.tracks.forEach { t ->
            val to = JsonObject()
            to.addProperty("elementLocalId", t.elementLocalId)
            val kfs = JsonArray()
            t.keyframes.forEach { kfs.add(keyframeToObject(it)) }
            to.add("keyframes", kfs)
            tracks.add(to)
        }
        add("tracks", tracks)
    }

    private fun objectToAnimation(o: JsonObject): Animation {
        val tracks = o.getAsJsonArray("tracks")?.map { el ->
            val to = el.asJsonObject
            Track(
                elementLocalId = to.get("elementLocalId").asInt,
                keyframes = to.getAsJsonArray("keyframes")?.map { objectToKeyframe(it.asJsonObject) }?.toMutableList()
                    ?: mutableListOf(),
            )
        }?.toMutableList() ?: mutableListOf()
        return Animation(
            lengthTicks = o.get("lengthTicks")?.asInt ?: 40,
            loop = o.get("loop")?.asBoolean ?: true,
            tracks = tracks,
        )
    }

    private fun keyframeToObject(k: Keyframe) = JsonObject().apply {
        addProperty("tick", k.tick)
        k.pose?.let { add("pose", poseToObject(it)) }
        k.transform?.let { add("transform", transformToObject(it)) }
        k.offset?.let { add("offset", vec3(it)) }
    }

    private fun objectToKeyframe(o: JsonObject) = Keyframe(
        tick = o.get("tick").asInt,
        pose = o.getAsJsonObject("pose")?.let { objectToPose(it) },
        transform = o.getAsJsonObject("transform")?.let { objectToTransform(it) },
        offset = o.getAsJsonArray("offset")?.let { vec3(it) },
    )

    private fun anchorToObject(a: Anchor) = JsonObject().apply {
        addProperty("world", a.world); addProperty("x", a.x); addProperty("y", a.y); addProperty("z", a.z)
    }

    private fun objectToAnchor(o: JsonObject) =
        Anchor(o.get("world").asString, o.get("x").asDouble, o.get("y").asDouble, o.get("z").asDouble)

    private fun vec3(v: Vec3) = JsonArray().apply { add(v.x); add(v.y); add(v.z) }
    private fun vec3(a: JsonArray) = Vec3(a[0].asDouble, a[1].asDouble, a[2].asDouble)

    private fun euler(e: EulerXYZ) = JsonArray().apply { add(e.x); add(e.y); add(e.z) }
    private fun euler(a: JsonArray) = EulerXYZ(a[0].asDouble, a[1].asDouble, a[2].asDouble)

    private fun quat(q: Quat) = JsonArray().apply { add(q.x); add(q.y); add(q.z); add(q.w) }
    private fun quat(a: JsonArray) = Quat(a[0].asDouble, a[1].asDouble, a[2].asDouble, a[3].asDouble)

    private fun poseToObject(p: Pose6) = JsonObject().apply {
        add("head", euler(p.head)); add("body", euler(p.body))
        add("leftArm", euler(p.leftArm)); add("rightArm", euler(p.rightArm))
        add("leftLeg", euler(p.leftLeg)); add("rightLeg", euler(p.rightLeg))
    }

    private fun objectToPose(o: JsonObject) = Pose6(
        head = euler(o.getAsJsonArray("head")), body = euler(o.getAsJsonArray("body")),
        leftArm = euler(o.getAsJsonArray("leftArm")), rightArm = euler(o.getAsJsonArray("rightArm")),
        leftLeg = euler(o.getAsJsonArray("leftLeg")), rightLeg = euler(o.getAsJsonArray("rightLeg")),
    )

    private fun transformToObject(t: Transform) = JsonObject().apply {
        add("translation", vec3(t.translation)); add("leftRotation", quat(t.leftRotation))
        add("scale", vec3(t.scale)); add("rightRotation", quat(t.rightRotation))
    }

    private fun objectToTransform(o: JsonObject) = Transform(
        translation = vec3(o.getAsJsonArray("translation")), leftRotation = quat(o.getAsJsonArray("leftRotation")),
        scale = vec3(o.getAsJsonArray("scale")), rightRotation = quat(o.getAsJsonArray("rightRotation")),
    )

    private fun flagsToObject(f: ArmorStandFlags) = JsonObject().apply {
        addProperty("small", f.small); addProperty("invisible", f.invisible)
        addProperty("noBasePlate", f.noBasePlate); addProperty("noGravity", f.noGravity)
        addProperty("arms", f.arms); addProperty("marker", f.marker); addProperty("glowing", f.glowing)
    }

    private fun objectToFlags(o: JsonObject) = ArmorStandFlags(
        small = o.get("small")?.asBoolean ?: false, invisible = o.get("invisible")?.asBoolean ?: false,
        noBasePlate = o.get("noBasePlate")?.asBoolean ?: false, noGravity = o.get("noGravity")?.asBoolean ?: true,
        arms = o.get("arms")?.asBoolean ?: true, marker = o.get("marker")?.asBoolean ?: false,
        glowing = o.get("glowing")?.asBoolean ?: false,
    )
}
