package com.tinyyana.awesomeArmorStandEditor.listener

import com.tinyyana.awesomeArmorStandEditor.AwesomeArmorStandEditorPlugin
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.EventPriority
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerArmorStandManipulateEvent

/** Keeps our elements from being destroyed or fiddled with by vanilla mechanics. */
class EntityProtectionListener(private val plugin: AwesomeArmorStandEditorPlugin) : Listener {

    @EventHandler
    fun onDamage(event: EntityDamageEvent) {
        if (plugin.registry.isOurs(event.entity)) event.isCancelled = true
    }

    /**
     * Equipment on our elements can come from share codes and remote scenes, so it may be forged
     * (any enchantment, any custom data another plugin trusts). It is display-only and must never
     * reach a player: if something still kills one of our stands (/kill, another plugin), it drops
     * nothing.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDeath(event: EntityDeathEvent) {
        if (!plugin.registry.isOurs(event.entity)) return
        event.drops.clear()
        event.droppedExp = 0
    }

    @EventHandler
    fun onManipulate(event: PlayerArmorStandManipulateEvent) {
        // Editing equipment goes through the GUI; block vanilla equip-swapping on our stands.
        if (plugin.registry.isOurs(event.rightClicked)) event.isCancelled = true
    }
}
