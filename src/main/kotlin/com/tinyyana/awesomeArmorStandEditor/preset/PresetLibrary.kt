package com.tinyyana.awesomeArmorStandEditor.preset

import com.tinyyana.awesomeArmorStandEditor.model.EulerXYZ
import com.tinyyana.awesomeArmorStandEditor.model.Pose6
import com.tinyyana.awesomeArmorStandEditor.model.Vec3
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

data class PosePreset(val id: String, val name: String, val icon: Material, val pose: Pose6, val arms: Boolean?)

data class EmitterTemplate(
    val particle: String, val offset: Vec3, val count: Int, val spread: Vec3, val speed: Double, val rate: Int,
)

data class FxPreset(val id: String, val name: String, val icon: Material, val emitters: List<EmitterTemplate>)

/** Data-driven pose & effect presets from presets.yml, so a non-artist picks instead of crafting. */
class PresetLibrary private constructor() {

    @Volatile var poses: List<PosePreset> = emptyList(); private set
    @Volatile var fx: List<FxPreset> = emptyList(); private set

    fun pose(id: String): PosePreset? = poses.find { it.id.equals(id, ignoreCase = true) }
    fun fx(id: String): FxPreset? = fx.find { it.id.equals(id, ignoreCase = true) }

    fun reload(plugin: JavaPlugin) {
        // saveResource() warns on every boot when the file already exists, so ask first.
        if (!File(plugin.dataFolder, "presets.yml").exists()) plugin.saveResource("presets.yml", false)
        val cfg = YamlConfiguration.loadConfiguration(File(plugin.dataFolder, "presets.yml"))
        poses = cfg.getMapList("poses").mapNotNull { parsePose(it) }
        fx = cfg.getMapList("fx").mapNotNull { parseFx(it) }
    }

    companion object {
        fun load(plugin: JavaPlugin): PresetLibrary = PresetLibrary().also { it.reload(plugin) }

        private fun parsePose(m: Map<*, *>): PosePreset? {
            val id = m["id"]?.toString() ?: return null
            return PosePreset(
                id = id,
                name = m["name"]?.toString() ?: id,
                icon = material(m["icon"]) ?: Material.ARMOR_STAND,
                arms = m["arms"] as? Boolean,
                pose = Pose6(
                    head = euler(m["head"]), body = euler(m["body"]),
                    leftArm = euler(m["left-arm"]), rightArm = euler(m["right-arm"]),
                    leftLeg = euler(m["left-leg"]), rightLeg = euler(m["right-leg"]),
                ),
            )
        }

        private fun parseFx(m: Map<*, *>): FxPreset? {
            val id = m["id"]?.toString() ?: return null
            val emitters = (m["emitters"] as? List<*>)?.mapNotNull { parseEmitter(it as? Map<*, *> ?: return@mapNotNull null) }
                ?: emptyList()
            return FxPreset(
                id = id,
                name = m["name"]?.toString() ?: id,
                icon = material(m["icon"]) ?: Material.BLAZE_POWDER,
                emitters = emitters,
            )
        }

        private fun parseEmitter(m: Map<*, *>): EmitterTemplate = EmitterTemplate(
            particle = m["particle"]?.toString() ?: "HAPPY_VILLAGER",
            offset = vec3(m["offset"]),
            count = num(m["count"])?.toInt() ?: 5,
            spread = vec3(m["spread"]),
            speed = num(m["speed"])?.toDouble() ?: 0.0,
            rate = num(m["rate"])?.toInt() ?: 10,
        )

        /** Pose arrays are authored in degrees; convert to radians for the model. */
        private fun euler(v: Any?): EulerXYZ {
            val l = v as? List<*> ?: return EulerXYZ.ZERO
            if (l.size < 3) return EulerXYZ.ZERO
            return EulerXYZ(
                Math.toRadians(num(l[0])?.toDouble() ?: 0.0),
                Math.toRadians(num(l[1])?.toDouble() ?: 0.0),
                Math.toRadians(num(l[2])?.toDouble() ?: 0.0),
            )
        }

        private fun vec3(v: Any?): Vec3 {
            val l = v as? List<*> ?: return Vec3.ZERO
            if (l.size < 3) return Vec3.ZERO
            return Vec3(num(l[0])?.toDouble() ?: 0.0, num(l[1])?.toDouble() ?: 0.0, num(l[2])?.toDouble() ?: 0.0)
        }

        private fun num(v: Any?): Number? = v as? Number
        private fun material(v: Any?): Material? = v?.let { runCatching { Material.valueOf(it.toString().uppercase()) }.getOrNull() }
    }
}
