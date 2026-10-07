package com.tinyyana.awesomeArmorStandEditor.store

import com.tinyyana.awesomeArmorStandEditor.model.ItemRef
import org.bukkit.Bukkit
import org.bukkit.inventory.ItemStack

/**
 * ItemRef <-> ItemStack at runtime (needs the server). Bukkit-only API:
 * `ItemFactory#createItemStack(String)` and `ItemMeta#getAsComponentString()` are Spigot API.
 */
object ItemResolver {

    /**
     * `bukkit` (exact base64) first; otherwise `id + components` through the server's item parser
     * (the same syntax as `/give`), with amount = count. Null when neither works.
     */
    fun resolve(ref: ItemRef): ItemStack? {
        ref.bukkit?.takeIf { it.isNotEmpty() }?.let { b64 -> ItemCodec.decode(b64)?.let { return it } }
        val id = ref.id ?: return null
        return try {
            Bukkit.getItemFactory().createItemStack(id + (ref.components ?: "")).also {
                it.amount = ref.count.coerceIn(1, it.maxStackSize.coerceAtLeast(1))
            }
        } catch (e: Exception) {
            null
        }
    }

    /** What the plugin stores for an item a player put in: id, count, components and the exact base64. */
    fun toRef(item: ItemStack): ItemRef {
        val components = runCatching { item.itemMeta?.asComponentString }.getOrNull()
        return ItemRef(
            id = item.type.key.toString(),
            count = item.amount.coerceIn(1, 99),
            components = ItemRefs.normalizeComponents(components),
            bukkit = ItemCodec.encode(item),
        )
    }

    /** v2 writer hook: an ItemRef without base64 → resolve it and encode. Null if it can't be made. */
    fun encodeLegacy(ref: ItemRef): String? = resolve(ref)?.let { ItemCodec.encode(it) }
}
