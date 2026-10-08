package com.tinyyana.awesomeArmorStandEditor.store

import com.tinyyana.awesomeArmorStandEditor.model.Scene
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Portable share code text: gzip the scene JSON (v3) and URL-safe Base64 it, with a versioned prefix so
 * future formats can be told apart. It is usually longer than chat's 256-character limit, so it is meant
 * to be copied into a file or Pose Pavilion, and imported anywhere the plugin runs.
 *
 * Decode is a trust boundary (arbitrary player-supplied text): the Base64 length and the decompressed
 * size are both capped to stop oversized/decompression-bomb inputs, any malformed data is reported
 * instead of thrown into game logic, and the scene is validated strictly (never the lenient v2 reader).
 */
object ShareCode {

    private const val PREFIX = "AASE1:"
    private const val MAX_CODE_LEN = 60_000        // a code longer than this is rejected outright
    private const val MAX_JSON_BYTES = 1024 * 1024 // cap the decompressed JSON at 1 MiB

    fun encode(scene: Scene): String {
        // De-identified (no owner / id / last anchor): the text is meant to be passed around and pasted
        // into Pose Pavilion. Import re-owns the scene anyway.
        val json = SceneCodec.toJson(scene, SceneCodec.WriteOptions(includeIdentity = false)).toByteArray(Charsets.UTF_8)
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(json) }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bos.toByteArray())
    }

    sealed interface Decoded {
        data class Ok(val scene: Scene) : Decoded
        /** Well-formed code whose scene failed validation; [errors] are in document order. */
        data class Invalid(val errors: List<SceneError>) : Decoded
        /** Not an AASE1 code at all: bad Base64 / gzip, empty or oversized. */
        data object Malformed : Decoded
    }

    /**
     * Decodes and validates a code. The scene inside is untrusted whatever its schemaVersion, so it
     * always goes through the strict validator (nesting depth, finite numbers, schema, semantics).
     */
    fun decodeResult(code: String): Decoded {
        val body = code.trim().removePrefix(PREFIX)
        if (body.isEmpty() || body.length > MAX_CODE_LEN) return Decoded.Malformed
        val json = runCatching {
            val gz = Base64.getUrlDecoder().decode(body)
            GZIPInputStream(ByteArrayInputStream(gz)).use { readBounded(it, MAX_JSON_BYTES) }
        }.getOrNull() ?: return Decoded.Malformed
        return when (val r = runCatching { SceneCodec.decode(String(json, Charsets.UTF_8)) }.getOrNull()) {
            is SceneCodec.DecodeResult.Ok -> Decoded.Ok(r.scene)
            is SceneCodec.DecodeResult.Invalid -> Decoded.Invalid(r.errors)
            null -> Decoded.Malformed
        }
    }

    /** Returns null on any malformed, oversized or invalid code. */
    fun decode(code: String): Scene? = (decodeResult(code) as? Decoded.Ok)?.scene

    /** Read at most [max] bytes; null if the stream would exceed it (avoids decompression bombs). */
    private fun readBounded(input: InputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
