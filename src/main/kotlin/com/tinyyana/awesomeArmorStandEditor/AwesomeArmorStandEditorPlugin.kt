package com.tinyyana.awesomeArmorStandEditor

import com.tinyyana.awesomeArmorStandEditor.admin.AdminTools
import com.tinyyana.awesomeArmorStandEditor.animation.AnimationPlayer
import com.tinyyana.awesomeArmorStandEditor.command.AaseCommand
import com.tinyyana.awesomeArmorStandEditor.config.EditorSettings
import com.tinyyana.awesomeArmorStandEditor.edit.EditToolListener
import com.tinyyana.awesomeArmorStandEditor.edit.EditorController
import com.tinyyana.awesomeArmorStandEditor.integration.LycoLibHook
import com.tinyyana.awesomeArmorStandEditor.listener.ChunkIndexListener
import com.tinyyana.awesomeArmorStandEditor.listener.EntityProtectionListener
import com.tinyyana.awesomeArmorStandEditor.menu.ControlPanel
import com.tinyyana.awesomeArmorStandEditor.menu.EquipmentMenu
import com.tinyyana.awesomeArmorStandEditor.menu.GuideBook
import com.tinyyana.awesomeArmorStandEditor.menu.PresetGallery
import com.tinyyana.awesomeArmorStandEditor.particle.ParticleService
import com.tinyyana.awesomeArmorStandEditor.placement.EntityRegistry
import com.tinyyana.awesomeArmorStandEditor.preset.PresetLibrary
import com.tinyyana.awesomeArmorStandEditor.placement.PlacementService
import com.tinyyana.awesomeArmorStandEditor.region.EventProbeGuard
import com.tinyyana.awesomeArmorStandEditor.region.PermissiveGuard
import com.tinyyana.awesomeArmorStandEditor.region.RegionGuard
import com.tinyyana.awesomeArmorStandEditor.session.EditSessionManager
import com.tinyyana.awesomeArmorStandEditor.store.SceneStore
import com.tinyyana.awesomeArmorStandEditor.text.Texts
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class AwesomeArmorStandEditorPlugin : JavaPlugin() {

    lateinit var keys: AaseKeys; private set
    lateinit var texts: Texts; private set
    lateinit var registry: EntityRegistry; private set
    lateinit var placement: PlacementService; private set
    lateinit var store: SceneStore; private set
    lateinit var sessions: EditSessionManager; private set
    lateinit var controller: EditorController; private set
    lateinit var panel: ControlPanel; private set
    lateinit var gallery: PresetGallery; private set
    lateinit var equipmentMenu: EquipmentMenu; private set
    lateinit var guideBook: GuideBook; private set
    lateinit var particles: ParticleService; private set
    lateinit var animation: AnimationPlayer; private set
    lateinit var presets: PresetLibrary; private set
    lateinit var adminTools: AdminTools; private set

    @Volatile lateinit var settings: EditorSettings; private set
    @Volatile lateinit var guard: RegionGuard; private set

    override fun onEnable() {
        saveDefaultConfig()
        keys = AaseKeys(this)
        texts = Texts.load(this)
        settings = EditorSettings.load(config)
        presets = PresetLibrary.load(this)
        registry = EntityRegistry(keys)
        placement = PlacementService(registry, keys)
        store = SceneStore(File(dataFolder, "scenes"))
        sessions = EditSessionManager()
        guard = buildGuard()
        controller = EditorController(this)
        panel = ControlPanel(this)
        gallery = PresetGallery(this)
        equipmentMenu = EquipmentMenu(this)
        guideBook = GuideBook(this)
        particles = ParticleService(this, keys)
        animation = AnimationPlayer(this)
        adminTools = AdminTools(this)
        LycoLibHook.init(server.pluginManager)
        registry.indexLoaded(this)
        particles.indexLoaded()
        particles.start()

        val command = AaseCommand(this)
        getCommand("aase")?.let {
            it.setExecutor(command)
            it.tabCompleter = command
        }

        server.pluginManager.apply {
            registerEvents(EditToolListener(this@AwesomeArmorStandEditorPlugin), this@AwesomeArmorStandEditorPlugin)
            registerEvents(EntityProtectionListener(this@AwesomeArmorStandEditorPlugin), this@AwesomeArmorStandEditorPlugin)
            registerEvents(ChunkIndexListener(this@AwesomeArmorStandEditorPlugin), this@AwesomeArmorStandEditorPlugin)
            registerEvents(panel, this@AwesomeArmorStandEditorPlugin)
            registerEvents(gallery, this@AwesomeArmorStandEditorPlugin)
            registerEvents(equipmentMenu, this@AwesomeArmorStandEditorPlugin)
        }

        logger.info("AwesomeArmorStandEditor enabled (standalone, Spigot/Paper compatible).")
    }

    override fun onDisable() {
        // Entities placed in the world persist (they're real tagged entities). Only editing state is dropped.
        if (::particles.isInitialized) particles.stop()
        if (::texts.isInitialized) texts.close()
    }

    fun reloadAll() {
        reloadConfig()
        settings = EditorSettings.load(config)
        guard = buildGuard()
        texts.reload(this)
        presets.reload(this)
    }

    private fun buildGuard(): RegionGuard = if (settings.regionEventProbe) EventProbeGuard() else PermissiveGuard
}
