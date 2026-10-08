package com.tinyyana.awesomeArmorStandEditor.config

import org.bukkit.Material
import org.bukkit.configuration.file.FileConfiguration

/**
 * Immutable settings snapshot loaded from config.yml. All performance/limit constants live here so
 * they're config-overridable (never hardcoded on a hot path). Reloaded via /aase reload.
 */
data class EditorSettings(
    val toolMaterial: Material,
    val rotationStepsDeg: List<Double>,
    val translateSteps: List<Double>,
    val scaleSteps: List<Double>,
    val limitPerPlayer: Int,
    val limitGlobal: Int,
    val limitPerChunk: Int,
    val regionEventProbe: Boolean,
    val selectRange: Int,
    val particleBudget: Int,
    val particleRange: Int,
    val maxPurgeRadius: Int,
    val soundEnabled: Boolean,
    /** Scene file format written by /aase save: 3 (default) or 2 (legacy, for older plugin versions). */
    val writeSchema: Int = 3,
    val remote: RemoteSettings = RemoteSettings(),
    /** /aase share uploads to the website for a short code (needs remote.enabled too). */
    val shareUpload: Boolean = true,
) {
    companion object {
        fun load(config: FileConfiguration): EditorSettings {
            val toolMat = config.getString("tool.material")?.let {
                runCatching { Material.valueOf(it.uppercase()) }.getOrNull()
            } ?: Material.BLAZE_ROD

            fun doubles(path: String, default: List<Double>): List<Double> =
                config.getList(path)?.mapNotNull { (it as? Number)?.toDouble() }?.takeIf { it.isNotEmpty() }
                    ?: default

            return EditorSettings(
                toolMaterial = toolMat,
                rotationStepsDeg = doubles("steps.rotation-deg", listOf(1.0, 15.0, 45.0)),
                translateSteps = doubles("steps.translate", listOf(0.1, 0.5, 1.0)),
                scaleSteps = doubles("steps.scale", listOf(0.1, 0.25, 1.0)),
                limitPerPlayer = config.getInt("limits.per-player", 200),
                limitGlobal = config.getInt("limits.global", 5000),
                limitPerChunk = config.getInt("limits.per-chunk", 100),
                regionEventProbe = config.getBoolean("region.event-probe", true),
                selectRange = config.getInt("tool.select-range", 6),
                particleBudget = config.getInt("particles.budget-per-tick", 200),
                particleRange = config.getInt("particles.render-range", 32),
                maxPurgeRadius = config.getInt("admin.max-purge-radius", 64),
                soundEnabled = config.getBoolean("tool.sound", true),
                writeSchema = if (config.getInt("store.write-schema", 3) == 2) 2 else 3,
                remote = RemoteSettings(
                    enabled = config.getBoolean("import.remote.enabled", true),
                    baseUrl = config.getString("import.remote.base-url", RemoteSettings.DEFAULT_BASE_URL)!!.trim().trimEnd('/'),
                    timeoutSeconds = config.getInt("import.remote.timeout-seconds", 10).coerceIn(1, 120),
                    maxBytes = config.getLong("import.remote.max-bytes", 1_048_576L).coerceIn(1024L, 16L * 1024 * 1024),
                    cooldownSeconds = config.getInt("import.remote.cooldown-seconds", 10).coerceAtLeast(0),
                    maxConcurrent = config.getInt("import.remote.max-concurrent", 4).coerceAtLeast(1),
                ),
                shareUpload = config.getBoolean("share.upload", true),
            )
        }
    }
}

/** `import.remote.*` — fetching scenes from Pose Pavilion by short code. Outbound HTTPS only. */
data class RemoteSettings(
    val enabled: Boolean = true,
    val baseUrl: String = DEFAULT_BASE_URL,
    val timeoutSeconds: Int = 10,
    val maxBytes: Long = 1_048_576L,
    val cooldownSeconds: Int = 10,
    val maxConcurrent: Int = 4,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://lyco-aase.tinyyana.com"
    }
}
