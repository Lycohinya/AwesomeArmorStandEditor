package com.tinyyana.awesomeArmorStandEditor

import com.tinyyana.awesomeArmorStandEditor.store.ItemCodec
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectOutputStream
import java.io.Serializable
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ItemCodecFilterTest {

    private fun serialize(obj: Any): String {
        val bos = ByteArrayOutputStream()
        ObjectOutputStream(bos).use { it.writeObject(obj) }
        return Base64.getEncoder().encodeToString(bos.toByteArray())
    }

    /** Stand-in for a gadget class: anything Serializable outside the item shapes. */
    class NotAnItem(val payload: String) : Serializable

    @Test
    fun `maps of boxed values and strings pass the filter`() {
        val map = linkedMapOf<String, Any>("v" to 1, "type" to "DIAMOND_SWORD", "amount" to 3, "lore" to arrayListOf("a", "b"))
        assertEquals(map, ItemCodec.readFiltered(serialize(map)))
    }

    @Test
    fun `arbitrary serializable classes are rejected`() {
        assertNull(ItemCodec.readFiltered(serialize(NotAnItem("x"))))
        assertNull(ItemCodec.readFiltered(serialize(File("C:/x"))))
        // Nested inside an allowed container is still rejected.
        assertNull(ItemCodec.readFiltered(serialize(hashMapOf("k" to NotAnItem("x")))))
    }

    @Test
    fun `garbage returns null`() {
        assertNull(ItemCodec.readFiltered("not base64 at all"))
        assertNull(ItemCodec.decode(serialize(linkedMapOf("v" to 1))))
    }
}
