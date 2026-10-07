package com.tinyyana.awesomeArmorStandEditor.remote

import com.google.gson.JsonObject
import com.tinyyana.awesomeArmorStandEditor.config.RemoteSettings
import com.tinyyana.awesomeArmorStandEditor.model.Scene
import com.tinyyana.awesomeArmorStandEditor.store.SceneCodec
import com.tinyyana.awesomeArmorStandEditor.store.JsonTooDeepException
import com.tinyyana.awesomeArmorStandEditor.store.SceneError
import com.tinyyana.awesomeArmorStandEditor.store.SceneValidator
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * Outbound-only HTTPS client for AASE Studio (`GET /api/scenes/<code>.json`, `POST /api/scenes`).
 * The plugin never listens on a port. Every result is a value (no exceptions escape the futures);
 * futures complete off the main thread — callers hop back with the Bukkit scheduler.
 *
 * Trust boundary: the body is read with a byte cap ([RemoteSettings.maxBytes]) and a deadline, parsed
 * strictly and validated against the scene schema before anything reaches the placement code.
 */
class StudioClient(
    private val settings: RemoteSettings,
    private val base: String,
    private val userAgent: String,
    private val executor: ExecutorService,
) {
    private val timeout: Duration = Duration.ofSeconds(settings.timeoutSeconds.toLong())

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NEVER)   // a redirect could leave https; treat it as an error
        .executor(executor)
        .build()

    sealed interface FetchResult {
        data class Ok(val scene: Scene) : FetchResult
        data object NotFound : FetchResult
        data object Timeout : FetchResult
        data object TooLarge : FetchResult
        data object NotJson : FetchResult
        data class Invalid(val errors: List<SceneError>) : FetchResult
        data class HttpError(val status: Int) : FetchResult
        data class Failed(val reason: String) : FetchResult
    }

    sealed interface UploadResult {
        data class Ok(val code: String, val url: String?, val importCommand: String) : UploadResult
        data object RateLimited : UploadResult
        data object TooLarge : UploadResult
        data object Rejected : UploadResult
        data object Timeout : UploadResult
        data class HttpError(val status: Int) : UploadResult
        data class Failed(val reason: String) : UploadResult
    }

    fun fetch(code: String): CompletableFuture<FetchResult> {
        val request = HttpRequest.newBuilder(URI(RemoteUrls.sceneUrl(base, code)))
            .timeout(timeout)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .GET()
            .build()
        val body = AtomicReference<InputStream?>()
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
            .thenApplyAsync({ response ->
                body.set(response.body())
                response.body().use { readScene(response, it) }
            }, executor)
            // HttpRequest.timeout only covers the headers; this bounds the whole exchange.
            .orTimeout(settings.timeoutSeconds * 2L, TimeUnit.SECONDS)
            .whenComplete { _, _ -> runCatching { body.get()?.close() } }
            .exceptionally { mapFetchError(it) }
    }

    private fun readScene(response: HttpResponse<InputStream>, input: InputStream): FetchResult {
        when (response.statusCode()) {
            200 -> {}
            404, 410 -> return FetchResult.NotFound
            else -> return FetchResult.HttpError(response.statusCode())
        }
        val declared = response.headers().firstValueAsLong("Content-Length").let { if (it.isPresent) it.asLong else null }
        if (BoundedRead.declaredTooLarge(declared, settings.maxBytes)) return FetchResult.TooLarge
        val deadline = System.nanoTime() + timeout.toNanos()
        val bytes = when (val r = BoundedRead.read(input, settings.maxBytes, deadline)) {
            is BoundedRead.Result.Ok -> r.bytes
            BoundedRead.Result.TooLarge -> return FetchResult.TooLarge
            BoundedRead.Result.TimedOut -> return FetchResult.Timeout
        }
        val doc = try {
            SceneValidator.parseStrict(String(bytes, Charsets.UTF_8))
        } catch (e: JsonTooDeepException) {
            return FetchResult.Invalid(listOf(SceneError("", SceneValidator.TOO_DEEP)))
        } catch (e: Exception) {
            return FetchResult.NotJson
        }
        // decodeTree is untrusted by default: always the strict validator, whatever the schemaVersion.
        return when (val decoded = SceneCodec.decodeTree(doc)) {
            is SceneCodec.DecodeResult.Ok -> FetchResult.Ok(decoded.scene)
            is SceneCodec.DecodeResult.Invalid -> FetchResult.Invalid(decoded.errors)
        }
    }

    private fun mapFetchError(t: Throwable): FetchResult {
        val cause = unwrap(t)
        return when (cause) {
            is HttpTimeoutException, is TimeoutException -> FetchResult.Timeout
            else -> FetchResult.Failed(cause.javaClass.simpleName)
        }
    }

    /** POST a de-identified v3 scene JSON; the website answers 201 {code, importCommand, url, expiresAt}. */
    fun upload(json: String): CompletableFuture<UploadResult> {
        val request = HttpRequest.newBuilder(URI(RemoteUrls.uploadUrl(base)))
            .timeout(timeout)
            .header("User-Agent", userAgent)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json, Charsets.UTF_8))
            .build()
        val body = AtomicReference<InputStream?>()
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
            .thenApplyAsync({ response ->
                body.set(response.body())
                response.body().use { readUpload(response.statusCode(), it) }
            }, executor)
            .orTimeout(settings.timeoutSeconds * 2L, TimeUnit.SECONDS)
            .whenComplete { _, _ -> runCatching { body.get()?.close() } }
            .exceptionally { t ->
                when (val cause = unwrap(t)) {
                    is HttpTimeoutException, is TimeoutException -> UploadResult.Timeout
                    else -> UploadResult.Failed(cause.javaClass.simpleName)
                }
            }
    }

    private fun readUpload(status: Int, input: InputStream): UploadResult {
        when (status) {
            200, 201 -> {}
            429 -> return UploadResult.RateLimited
            413 -> return UploadResult.TooLarge
            400 -> return UploadResult.Rejected
            else -> return UploadResult.HttpError(status)
        }
        val bytes = (BoundedRead.read(input, 64 * 1024L, System.nanoTime() + timeout.toNanos()) as? BoundedRead.Result.Ok)?.bytes
            ?: return UploadResult.Failed("response")
        return parseUploadResponse(String(bytes, Charsets.UTF_8)) ?: UploadResult.Failed("response")
    }

    private fun unwrap(t: Throwable): Throwable {
        var c = t
        while ((c is CompletionException || c is java.util.concurrent.ExecutionException) && c.cause != null) c = c.cause!!
        return c
    }

    companion object {
        /** Parses the 201 body; the code must look like a short code (never echo arbitrary text into a command). */
        fun parseUploadResponse(text: String): UploadResult.Ok? {
            val o = runCatching { SceneValidator.parseStrict(text) as? JsonObject }.getOrNull() ?: return null
            val code = o.get("code")?.takeIf { it.isJsonPrimitive }?.asString?.let { ShortCodes.parse(it) } ?: return null
            val url = o.get("url")?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf { it.startsWith("https://") || it.startsWith("http://localhost") || it.startsWith("http://127.0.0.1") }
            return UploadResult.Ok(code, url, "/aase import $code")
        }
    }
}
