package com.tinyyana.awesomeArmorStandEditor

import org.bukkit.NamespacedKey
import org.bukkit.plugin.Plugin

/** PDC keys stamped on every entity we own, so we can identify/purge our elements precisely. */
class AaseKeys(plugin: Plugin) {
    val owner: NamespacedKey = NamespacedKey(plugin, "owner")   // owner UUID (string)
    val scene: NamespacedKey = NamespacedKey(plugin, "scene")   // scene id (string)
    val local: NamespacedKey = NamespacedKey(plugin, "local")   // element localId (int)
    val tool: NamespacedKey = NamespacedKey(plugin, "tool")     // marks the editor tool item (byte)
    val emitter: NamespacedKey = NamespacedKey(plugin, "emitter") // particle emitter params (string) on marker
    val placement: NamespacedKey = NamespacedKey(plugin, "placement") // placement UUID (string): one per placed copy
    val sceneName: NamespacedKey = NamespacedKey(plugin, "sceneName") // scene name at placement time (string)
}
