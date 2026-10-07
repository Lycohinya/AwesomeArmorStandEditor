package com.tinyyana.awesomeArmorStandEditor.store

import org.bukkit.inventory.ItemStack
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputFilter
import java.util.Base64

/**
 * ItemStack <-> Base64 via Bukkit's ConfigurationSerializable stream. This is the stable,
 * cross-platform (Spigot + Paper) path — avoids Paper-only ItemStack.serializeAsBytes().
 *
 * Decode is a trust boundary: the bytes come from share codes and remote scenes anyone can write,
 * and Bukkit's stream is plain Java deserialization. An allowlist filter keeps it to the shapes an
 * item actually serializes to (Bukkit's Wrapper around maps/lists of boxed primitives and strings),
 * so a crafted payload cannot instantiate arbitrary Serializable classes on the server.
 */
object ItemCodec {

    private const val MAX_DEPTH = 64L
    private const val MAX_REFS = 50_000L
    private const val MAX_BYTES = 1L shl 20
    private const val MAX_ARRAY = 50_000L

    private val allowedExact = setOf(
        "java.lang.Object", "java.lang.String", "java.lang.Boolean", "java.lang.Byte", "java.lang.Short",
        "java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double", "java.lang.Character",
        "java.lang.Number",
        "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap",
        "java.util.ArrayList", "java.util.LinkedList", "java.util.HashSet", "java.util.LinkedHashSet",
        // HashMap.readObject sizes its table through the filter as a Map.Entry[] (an interface, no state).
        "java.util.Map\$Entry",
        "org.bukkit.util.io.Wrapper",
    )

    /** Guava's immutable collections serialize through their SerializedForm classes. */
    private const val GUAVA_COLLECT = "com.google.common.collect."

    internal fun isAllowedClass(name: String): Boolean = name in allowedExact || name.startsWith(GUAVA_COLLECT)

    internal val filter = ObjectInputFilter { info ->
        if (info.depth() > MAX_DEPTH || info.references() > MAX_REFS || info.streamBytes() > MAX_BYTES ||
            info.arrayLength() > MAX_ARRAY
        ) {
            return@ObjectInputFilter ObjectInputFilter.Status.REJECTED
        }
        var c = info.serialClass() ?: return@ObjectInputFilter ObjectInputFilter.Status.UNDECIDED
        while (c.isArray) c = c.componentType
        if (c.isPrimitive || isAllowedClass(c.name)) ObjectInputFilter.Status.ALLOWED
        else ObjectInputFilter.Status.REJECTED
    }

    fun encode(item: ItemStack): String {
        ByteArrayOutputStream().use { bos ->
            BukkitObjectOutputStream(bos).use { it.writeObject(item) }
            return Base64.getEncoder().encodeToString(bos.toByteArray())
        }
    }

    /** Returns null on any malformed/incompatible/rejected data rather than throwing into game logic. */
    fun decode(data: String): ItemStack? = readFiltered(data) as? ItemStack

    internal fun readFiltered(data: String): Any? = try {
        val bytes = Base64.getDecoder().decode(data.trim())
        ByteArrayInputStream(bytes).use { bis ->
            BukkitObjectInputStream(bis).use {
                it.setObjectInputFilter(filter)
                it.readObject()
            }
        }
    } catch (e: Exception) {
        null
    }
}
