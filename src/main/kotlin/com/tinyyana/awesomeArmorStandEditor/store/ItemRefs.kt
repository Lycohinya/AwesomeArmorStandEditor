package com.tinyyana.awesomeArmorStandEditor.store

/** Pure helpers for [com.tinyyana.awesomeArmorStandEditor.model.ItemRef] (no Bukkit). */
object ItemRefs {

    private val ID = Regex("^[a-z0-9_.-]+:[a-z0-9_./-]+\\z")

    fun isValidId(id: String): Boolean = ID.containsMatchIn(id)

    /**
     * Normalises `ItemMeta#getAsComponentString()` output to the `/give` component suffix:
     * keeps only the `[...]` part (an `id[...]` prefix some servers return is stripped), and returns
     * null for nothing / `[]`.
     */
    fun normalizeComponents(raw: String?): String? {
        val s = raw?.trim() ?: return null
        if (s.isEmpty()) return null
        val start = s.indexOf('[')
        if (start < 0) return null
        val body = s.substring(start)
        if (!body.endsWith("]")) return null
        return body.takeUnless { it.substring(1, it.length - 1).isBlank() }
    }
}
