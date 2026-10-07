package com.tinyyana.awesomeArmorStandEditor.remote

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI

/**
 * Pure pieces of the remote import / share upload (no Bukkit, no network), so they are unit-tested:
 * recognising a short code, checking the configured base URL, bounded reading, cooldowns.
 */
object ShortCodes {

    /** Alphabet without 0 1 i l o; 6–8 characters accepted (the website issues 7), case-insensitive. */
    private val CODE = Regex("^[2-9a-hjkmnp-z]{6,8}\\z", RegexOption.IGNORE_CASE)

    /** `http(s)://host/s/<code>`, optionally `.json` / `.png`, query or fragment. */
    private val URL = Regex("^https?://[^\\s/]+/s/([^\\s/?#.]+)(?:\\.(?:json|png))?(?:[?#].*)?\\z", RegexOption.IGNORE_CASE)

    /** The lowercase short code in [input] (a bare code or a share URL), or null. */
    fun parse(input: String): String? {
        val text = input.trim()
        if (CODE.containsMatchIn(text)) return text.lowercase()
        val m = URL.find(text) ?: return null
        val code = m.groupValues[1]
        return if (CODE.containsMatchIn(code)) code.lowercase() else null
    }

    fun isAase1(input: String): Boolean = input.trim().startsWith("AASE1:", ignoreCase = true)
}

object RemoteUrls {

    /**
     * Only https, except plain http to localhost / 127.0.0.1 for local testing. Returns the normalised
     * base (no trailing slash) or null when the configured value is not allowed.
     */
    fun checkBase(baseUrl: String): String? {
        val uri = runCatching { URI(baseUrl.trim().trimEnd('/')) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        val ok = when (uri.scheme?.lowercase()) {
            "https" -> true
            "http" -> host.equals("localhost", ignoreCase = true) || host == "127.0.0.1"
            else -> false
        }
        return if (ok) uri.toString() else null
    }

    fun sceneUrl(base: String, code: String) = "$base/api/scenes/$code.json"

    fun uploadUrl(base: String) = "$base/api/scenes"
}

/** Reads a stream with a hard byte cap and a deadline; never buffers more than cap + one chunk. */
object BoundedRead {

    sealed interface Result {
        class Ok(val bytes: ByteArray) : Result
        data object TooLarge : Result
        data object TimedOut : Result
    }

    /**
     * Stops as soon as more than [maxBytes] arrive (the body is not drained) or [deadlineNanos]
     * (System.nanoTime based) passes between chunks. A read that blocks past the deadline is ended by
     * the caller closing the stream.
     */
    fun read(input: InputStream, maxBytes: Long, deadlineNanos: Long? = null, now: () -> Long = System::nanoTime): Result {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            if (deadlineNanos != null && now() - deadlineNanos > 0) return Result.TimedOut
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) return Result.TooLarge
            out.write(buf, 0, n)
        }
        return Result.Ok(out.toByteArray())
    }

    /** A declared Content-Length over the cap can be refused before reading anything. */
    fun declaredTooLarge(contentLength: Long?, maxBytes: Long): Boolean = contentLength != null && contentLength > maxBytes
}

/** Per-player cooldown arithmetic. */
object Cooldowns {
    /** Whole seconds left (rounded up), 0 when the player may go. */
    fun remainingSeconds(lastMillis: Long?, nowMillis: Long, cooldownSeconds: Int): Int {
        if (lastMillis == null || cooldownSeconds <= 0) return 0
        val left = lastMillis + cooldownSeconds * 1000L - nowMillis
        return if (left <= 0) 0 else ((left + 999) / 1000).toInt()
    }
}
