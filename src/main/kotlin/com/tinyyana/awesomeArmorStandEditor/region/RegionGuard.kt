package com.tinyyana.awesomeArmorStandEditor.region

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.inventory.EquipmentSlot

/** Decides whether a player may edit/place at a location, respecting land-protection plugins. */
interface RegionGuard {
    fun canBuild(player: Player, location: Location): Boolean
}

/** No land integration — always allows. Used when the event probe is disabled by config. */
object PermissiveGuard : RegionGuard {
    override fun canBuild(player: Player, location: Location): Boolean = true
}

/**
 * Universal guard: fires a synthetic [BlockPlaceEvent] and honours any land plugin's veto
 * (GriefPrevention/WorldGuard/Towny/Lands all listen to it). No block is actually placed.
 *
 * This is a *permission query*, not a re-enactment of how our entities appear. We create elements
 * with world.spawn(), which fires no vanilla placement event at all — a land plugin never sees the
 * spawn and so can never veto it directly. The probe asks the equivalent question ("would you let
 * this player build at this block?") and we obey the answer ourselves. Every code path that spawns
 * or teleports an element must call this; nothing downstream will catch a missed one.
 *
 * Must be called on the thread that owns the location (edit handlers already are). The event constructor is the
 * public non-deprecated one; EntityPlaceEvent is @ApiStatus.Internal and needs an already-spawned
 * entity, so it cannot serve as a pre-check.
 *
 * Known cost: block loggers (CoreProtect et al.) may record the probe. The placed block is the AIR
 * the player stands in, so most filter it out.
 */
class EventProbeGuard : RegionGuard {
    override fun canBuild(player: Player, location: Location): Boolean {
        val block = location.block
        val against = block.getRelative(BlockFace.DOWN)
        val hand = player.inventory.itemInMainHand
        val event = BlockPlaceEvent(block, block.state, against, hand, player, true, EquipmentSlot.HAND)
        Bukkit.getPluginManager().callEvent(event)
        return event.canBuild() && !event.isCancelled
    }
}
