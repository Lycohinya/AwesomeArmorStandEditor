package com.tinyyana.awesomeArmorStandEditor.remote

import com.tinyyana.awesomeArmorStandEditor.AwesomeArmorStandEditorPlugin
import com.tinyyana.awesomeArmorStandEditor.config.RemoteSettings
import com.tinyyana.awesomeArmorStandEditor.model.Scene
import com.tinyyana.awesomeArmorStandEditor.sched.PlayerTasks
import com.tinyyana.awesomeArmorStandEditor.store.SceneError
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Remote import (`/aase import <code|url>`) and share upload (`/aase share`) against Pose Pavilion.
 *
 * - Off (`import.remote.enabled: false`) means no HttpClient and no thread is ever created.
 * - Per-player cooldown and a server-wide cap on concurrent requests.
 * - Network work runs on a small daemon pool; results come back to the player's own thread through
 *   PlayerTasks (the region thread on Folia, the main thread on Spigot), and are dropped if the player has left by then.
 */
class RemoteService(private val plugin: AwesomeArmorStandEditorPlugin) {

    private val texts get() = plugin.texts
    private val lastRequest = ConcurrentHashMap<UUID, Long>()
    private val inFlight = AtomicInteger()

    @Volatile private var client: StudioClient? = null
    @Volatile private var clientSettings: RemoteSettings? = null
    @Volatile private var executor: ExecutorService? = null
    @Volatile private var warnedBadBase = false

    val settings: RemoteSettings get() = plugin.settings.remote

    /** Lazily (re)built when the settings change; null when remote is disabled or the base URL is not allowed. */
    @Synchronized
    private fun client(): StudioClient? {
        val s = settings
        if (!s.enabled) return null
        if (client != null && clientSettings == s) return client
        shutdown()
        val base = RemoteUrls.checkBase(s.baseUrl) ?: run {
            if (!warnedBadBase) plugin.logger.warning("import.remote.base-url must be https (http only for localhost): ${s.baseUrl}")
            warnedBadBase = true
            return null
        }
        val pool = Executors.newFixedThreadPool(s.maxConcurrent.coerceIn(1, 8), daemonThreads())
        @Suppress("DEPRECATION")
        val ua = "AwesomeArmorStandEditor/${plugin.description.version}"
        executor = pool
        clientSettings = s
        return StudioClient(s, base, ua, pool).also { client = it }
    }

    /** Called on /aase reload and on disable: the next request rebuilds from the new settings. */
    @Synchronized
    fun shutdown() {
        executor?.shutdownNow()
        executor = null
        warnedBadBase = false
        client = null
        clientSettings = null
    }

    // --- import ---

    /**
     * Fetch the scene for [code] and hand it to [onScene] on the player's thread. Caller already checked
     * the permission and the unsaved-changes guard; [onScene] re-checks whatever may have changed.
     */
    fun importRemote(player: Player, code: String, onScene: (Player, Scene) -> Unit) {
        if (!settings.enabled) return texts.send(player, "remote.disabled")
        val c = client() ?: return texts.send(player, "remote.disabled")
        if (!acquire(player)) return
        texts.send(player, "remote.fetching", "code" to texts.escape(code))
        val playerId = player.uniqueId
        val future = try {
            c.fetch(code)
        } catch (e: Exception) {
            CompletableFuture.completedFuture(StudioClient.FetchResult.Failed(e.javaClass.simpleName))
        }
        future.whenComplete { result, error ->
            inFlight.decrementAndGet()
            val r = result ?: StudioClient.FetchResult.Failed(error?.javaClass?.simpleName ?: "error")
            onMain(playerId) { p -> deliverFetch(p, code, r, onScene) }
        }
    }

    private fun deliverFetch(player: Player, code: String, r: StudioClient.FetchResult, onScene: (Player, Scene) -> Unit) {
        when (r) {
            is StudioClient.FetchResult.Ok -> onScene(player, r.scene)
            StudioClient.FetchResult.NotFound -> texts.send(player, "remote.not-found", "code" to texts.escape(code))
            StudioClient.FetchResult.Timeout -> texts.send(player, "remote.timeout")
            StudioClient.FetchResult.TooLarge -> texts.send(player, "remote.too-large", "max" to humanBytes(settings.maxBytes))
            StudioClient.FetchResult.NotJson -> texts.send(player, "remote.not-json")
            is StudioClient.FetchResult.Invalid -> sendInvalid(player, r.errors)
            is StudioClient.FetchResult.HttpError -> texts.send(player, "remote.http-error", "status" to r.status.toString())
            is StudioClient.FetchResult.Failed -> texts.send(player, "remote.failed", "reason" to texts.escape(r.reason))
        }
    }

    /**
     * "Format problems" reply for a scene that failed validation (remote short code or AASE1 text):
     * the count plus the first three pointers. Pointer and message come from outside, so both are escaped.
     */
    fun sendInvalid(player: Player, errors: List<SceneError>) {
        texts.send(player, "remote.invalid", "count" to errors.size.toString())
        for (e in errors.take(3)) {
            texts.send(player, "remote.invalid-line", "pointer" to texts.escape(e.pointer.ifEmpty { "/" }), "message" to texts.escape(e.message))
        }
    }

    // --- share upload ---

    /** True when /aase share should try the website first (upload on + remote on). */
    fun uploadEnabled(): Boolean = plugin.settings.shareUpload && settings.enabled

    /**
     * Upload the (already de-identified) v3 [json]. [onResult] runs on the player's thread with the result,
     * or is skipped if the player left. Returns false when nothing was sent (disabled, cooldown, busy) —
     * the caller then falls back to AASE1 text right away.
     */
    fun upload(player: Player, json: String, onResult: (Player, StudioClient.UploadResult) -> Unit): Boolean {
        val c = client() ?: return false
        if (!acquire(player)) return false
        texts.send(player, "share.uploading")
        val playerId = player.uniqueId
        val future = try {
            c.upload(json)
        } catch (e: Exception) {
            CompletableFuture.completedFuture(StudioClient.UploadResult.Failed(e.javaClass.simpleName))
        }
        future.whenComplete { result, error ->
            inFlight.decrementAndGet()
            val r = result ?: StudioClient.UploadResult.Failed(error?.javaClass?.simpleName ?: "error")
            onMain(playerId) { p -> onResult(p, r) }
        }
        return true
    }

    // --- shared ---

    /** Cooldown + concurrency gate. Sends the reason and returns false when the request may not start. */
    private fun acquire(player: Player): Boolean {
        val now = System.currentTimeMillis()
        val wait = Cooldowns.remainingSeconds(lastRequest[player.uniqueId], now, settings.cooldownSeconds)
        if (wait > 0) {
            texts.send(player, "remote.cooldown", "seconds" to wait.toString())
            return false
        }
        while (true) {
            val cur = inFlight.get()
            if (cur >= settings.maxConcurrent) {
                texts.send(player, "remote.busy")
                return false
            }
            if (inFlight.compareAndSet(cur, cur + 1)) break
        }
        lastRequest[player.uniqueId] = now
        return true
    }

    private fun onMain(playerId: UUID, block: (Player) -> Unit) {
        if (!plugin.isEnabled) return
        val player = plugin.server.getPlayer(playerId) ?: return   // left meanwhile: drop
        // The player's own thread, not plain runTask: on Folia-style servers that lane owns no
        // region and spawning the imported scene there throws (see PlayerTasks).
        runCatching { PlayerTasks.run(plugin, player, block) }
            .onFailure { plugin.logger.warning("Could not schedule the remote result back to the player's thread: $it") }
    }

    private fun humanBytes(b: Long): String = if (b >= 1024 * 1024) "${b / (1024 * 1024)} MiB" else "${b / 1024} KiB"

    private fun daemonThreads(): ThreadFactory {
        val n = AtomicInteger()
        return ThreadFactory { r -> Thread(r, "AASE-remote-${n.incrementAndGet()}").apply { isDaemon = true } }
    }
}
