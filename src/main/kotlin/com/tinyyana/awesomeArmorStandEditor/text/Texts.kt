package com.tinyyana.awesomeArmorStandEditor.text

import net.kyori.adventure.platform.bukkit.BukkitAudiences
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Player-facing text: lang/<code>.yml (MiniMessage) -> Adventure Component, sent via BukkitAudiences
 * so it works on Spigot and Paper alike. All strings are externalized here; debug/log text is not.
 *
 * The language is server-wide, picked by `language` in config.yml (`auto` reads the server's JVM
 * locale). Every shipped language is copied into the data folder so admins can edit them in place.
 */
class Texts private constructor(private val audiences: BukkitAudiences) {

    private val mm = MiniMessage.miniMessage()

    @Volatile private var messages: Map<String, String> = emptyMap()
    @Volatile private var pages: List<String> = emptyList()
    @Volatile private var prefix: Component = Component.empty()

    /** Resolved language code currently in use, e.g. `zh_TW`. */
    @Volatile var language: String = FALLBACK; private set

    fun send(sender: CommandSender, key: String, vararg placeholders: Pair<String, String>) {
        audiences.sender(sender).sendMessage(prefix.append(render(key, placeholders)))
    }

    fun actionbar(sender: CommandSender, key: String, vararg placeholders: Pair<String, String>) {
        audiences.sender(sender).sendActionBar(render(key, placeholders))
    }

    /** Send raw MiniMessage text as an action bar (for dynamic readouts). */
    fun actionbarRaw(sender: CommandSender, text: String) {
        audiences.sender(sender).sendActionBar(mm.deserialize(text))
    }

    /** Raw component, no prefix, italic forced off — for GUI item names/lore. */
    fun component(key: String, vararg placeholders: Pair<String, String>): Component =
        render(key, placeholders).decoration(TextDecoration.ITALIC, false)

    fun mini(text: String): Component = mm.deserialize(text).decoration(TextDecoration.ITALIC, false)

    fun sendComponent(sender: CommandSender, component: Component) {
        audiences.sender(sender).sendMessage(prefix.append(component))
    }

    /** A clickable chat button: the lang text, running [command] when clicked. */
    fun button(key: String, command: String, vararg placeholders: Pair<String, String>): Component =
        render(key, placeholders).clickEvent(ClickEvent.runCommand(command))

    /** One chat line: the [key] message followed by [buttons], space-separated. */
    fun sendWithButtons(sender: CommandSender, key: String, buttons: List<Component>, vararg placeholders: Pair<String, String>) {
        var line = render(key, placeholders)
        for (b in buttons) line = line.append(Component.space()).append(b)
        audiences.sender(sender).sendMessage(prefix.append(line))
    }

    /** A line made only of [buttons]. */
    fun sendButtons(sender: CommandSender, buttons: List<Component>) {
        if (buttons.isEmpty()) return
        var line = buttons.first()
        for (b in buttons.drop(1)) line = line.append(Component.space()).append(b)
        audiences.sender(sender).sendMessage(prefix.append(line))
    }

    fun raw(key: String): String? = messages[key]

    /** Plain label lookup for short strings spliced into other text (mode, body part, axis…). */
    fun label(key: String): String = messages[key] ?: key

    /** Pages of the in-game guide book, MiniMessage, one per page. */
    fun guidePages(): List<String> = pages

    /**
     * Display name of a preset. A `preset.name.<id>` entry in the lang file wins, so the presets we
     * ship translate; anything else (a pose saved with `/aase pose save`) keeps its presets.yml name.
     */
    fun presetName(id: String, fallback: String): String = messages["preset.name.$id"] ?: fallback

    /** Legacy section-string render — for ItemMeta display name/lore (cross-platform, no Paper API). */
    fun legacy(key: String, vararg placeholders: Pair<String, String>): String =
        LegacyComponentSerializer.legacySection().serialize(component(key, *placeholders))

    fun legacyOf(component: Component): String = LegacyComponentSerializer.legacySection().serialize(component)

    /**
     * Escapes MiniMessage tags in a placeholder value that came from outside (an imported scene's name,
     * a remote server's error text): placeholders are substituted into the MiniMessage source, so an
     * unescaped `<click:...>` in such a value would become a live tag.
     */
    fun escape(text: String): String = mm.escapeTags(text)

    private fun render(key: String, placeholders: Array<out Pair<String, String>>): Component {
        var text = messages[key] ?: return mm.deserialize("<red>$key")
        for ((k, v) in placeholders) text = text.replace("{$k}", v)
        return mm.deserialize(text)
    }

    fun reload(plugin: JavaPlugin) {
        saveLangFiles(plugin)
        val code = resolveLanguage(plugin)
        val lang = readLang(plugin, code)
        language = code
        messages = lang.getKeys(true).filter { lang.isString(it) }.associateWith { lang.getString(it)!! }
        pages = lang.getStringList("guide.pages")
        prefix = messages["prefix"]?.let { mm.deserialize(it) } ?: Component.empty()
    }

    fun close() = audiences.close()

    companion object {
        const val AUTO = "auto"
        const val FALLBACK = "en"
        val SUPPORTED = listOf("zh_TW", "en")

        fun load(plugin: JavaPlugin): Texts {
            warnAboutLegacyFiles(plugin)
            return Texts(BukkitAudiences.create(plugin)).also {
                it.reload(plugin)
                plugin.logger.info("Language: ${it.language}")
            }
        }

        private fun resolveLanguage(plugin: JavaPlugin): String {
            val configured = plugin.config.getString("language")?.trim().orEmpty()
            if (configured.isEmpty() || configured.equals(AUTO, ignoreCase = true)) return serverLanguage()
            SUPPORTED.firstOrNull { it.equals(configured, ignoreCase = true) }?.let { return it }
            plugin.logger.warning(
                "Unknown language '$configured' in config.yml (supported: ${SUPPORTED.joinToString()}), using auto.",
            )
            return serverLanguage()
        }

        /** `auto`: the region the server's JVM runs in. Only two languages ship, so any Chinese locale gets zh_TW. */
        private fun serverLanguage(): String =
            if (Locale.getDefault().language == "zh") "zh_TW" else FALLBACK

        /**
         * Write every shipped language into the data folder, not just the active one — an admin
         * comparing or switching languages shouldn't have to crack open the jar. A file that's
         * already there is never touched, so local edits survive.
         */
        private fun saveLangFiles(plugin: JavaPlugin) {
            for (code in SUPPORTED) {
                // saveResource() logs a warning of its own when the file is already there, so ask first.
                if (!File(plugin.dataFolder, "lang/$code.yml").exists()) plugin.saveResource("lang/$code.yml", false)
            }
        }

        /** User file wins key by key, jar copy fills the gaps and upgrades old static strings to new placeholder templates. */
        private fun readLang(plugin: JavaPlugin, code: String): YamlConfiguration {
            val path = "lang/$code.yml"
            val file = File(plugin.dataFolder, path)
            val user = YamlConfiguration.loadConfiguration(file)
            plugin.getResource(path)?.use { stream ->
                val defaults = YamlConfiguration.loadConfiguration(InputStreamReader(stream, StandardCharsets.UTF_8))
                for (key in defaults.getKeys(true)) {
                    if (defaults.isString(key)) {
                        if (!user.contains(key) || !user.isString(key)) {
                            user.set(key, defaults.getString(key))
                        } else {
                            val defVal = defaults.getString(key)!!
                            val userVal = user.getString(key)!!
                            // If default has placeholders that old user string completely lacks, upgrade it
                            if (defVal.contains("{") && !userVal.contains("{")) {
                                user.set(key, defVal)
                            }
                        }
                    }
                }
                user.setDefaults(defaults)
            }
            return user
        }

        /** 0.x kept text in messages.yml/guide.yml. Don't delete an admin's edits — tell them where it moved. */
        private fun warnAboutLegacyFiles(plugin: JavaPlugin) {
            for (old in listOf("messages.yml", "guide.yml")) {
                if (!File(plugin.dataFolder, old).exists()) continue
                plugin.logger.warning(
                    "$old is no longer read - player-facing text moved to lang/<code>.yml " +
                        "(see 'language' in config.yml). Copy over any edits, then delete it.",
                )
            }
        }
    }
}
