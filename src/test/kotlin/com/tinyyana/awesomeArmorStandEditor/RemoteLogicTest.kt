package com.tinyyana.awesomeArmorStandEditor

import com.tinyyana.awesomeArmorStandEditor.remote.BoundedRead
import com.tinyyana.awesomeArmorStandEditor.remote.Cooldowns
import com.tinyyana.awesomeArmorStandEditor.remote.RemoteUrls
import com.tinyyana.awesomeArmorStandEditor.remote.ShortCodes
import com.tinyyana.awesomeArmorStandEditor.remote.StudioClient
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteLogicTest {

    @Test
    fun `short codes are recognised case-insensitively and normalised to lowercase`() {
        assertEquals("abc2345", ShortCodes.parse("abc2345"))
        assertEquals("abc2345", ShortCodes.parse("  ABC2345 "))
        assertEquals("k7m9p2", ShortCodes.parse("k7m9p2"))         // 6
        assertEquals("k7m9p2qr", ShortCodes.parse("k7m9p2qr"))     // 8
        assertNull(ShortCodes.parse("k7m9p"))                       // 5
        assertNull(ShortCodes.parse("k7m9p2qrs"))                   // 9
        assertNull(ShortCodes.parse("abc1234"))                     // 1 is not in the alphabet
        assertNull(ShortCodes.parse("abcl234"))                     // l
        assertNull(ShortCodes.parse("abco234"))                     // o
        assertNull(ShortCodes.parse("abci234"))                     // i
        assertNull(ShortCodes.parse("castle"))
        assertNull(ShortCodes.parse("AASE1:H4sIAAAA"))
    }

    @Test
    fun `share urls yield their code`() {
        assertEquals("abc2345", ShortCodes.parse("https://lyco-aase.tinyyana.com/s/abc2345"))
        assertEquals("abc2345", ShortCodes.parse("https://lyco-aase.tinyyana.com/s/ABC2345?x=1#y"))
        assertEquals("abc2345", ShortCodes.parse("http://localhost:8080/s/abc2345.json"))
        assertEquals("abc2345", ShortCodes.parse("https://example.org/s/abc2345.png"))
        assertNull(ShortCodes.parse("https://lyco-aase.tinyyana.com/x/abc2345"))
        assertNull(ShortCodes.parse("https://lyco-aase.tinyyana.com/s/abc1234"))
        assertNull(ShortCodes.parse("ftp://host/s/abc2345"))
        assertTrue(ShortCodes.isAase1("aase1:xyz"))
        assertFalse(ShortCodes.isAase1("abc2345"))
    }

    @Test
    fun `base url must be https except localhost`() {
        assertEquals("https://lyco-aase.tinyyana.com", RemoteUrls.checkBase("https://lyco-aase.tinyyana.com/"))
        assertEquals("http://localhost:8787", RemoteUrls.checkBase("http://localhost:8787"))
        assertEquals("http://127.0.0.1:8787", RemoteUrls.checkBase("http://127.0.0.1:8787"))
        assertNull(RemoteUrls.checkBase("http://lyco-aase.tinyyana.com"))
        assertNull(RemoteUrls.checkBase("http://192.168.1.5"))
        assertNull(RemoteUrls.checkBase("file:///etc/passwd"))
        assertNull(RemoteUrls.checkBase("https://user:pw@host"))
        assertNull(RemoteUrls.checkBase("not a url"))
        assertEquals("https://h/api/scenes/abc2345.json", RemoteUrls.sceneUrl("https://h", "abc2345"))
        assertEquals("https://h/api/scenes", RemoteUrls.uploadUrl("https://h"))
    }

    @Test
    fun `bounded read stops once the cap is passed without reading the rest`() {
        val ok = BoundedRead.read(ByteArrayInputStream(ByteArray(1000)), 1000)
        assertIs<BoundedRead.Result.Ok>(ok)
        assertEquals(1000, ok.bytes.size)

        assertEquals(BoundedRead.Result.TooLarge, BoundedRead.read(ByteArrayInputStream(ByteArray(1001)), 1000))

        // An endless stream: must stop shortly after the cap instead of buffering forever.
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int { served += len; return len }
        }
        assertEquals(BoundedRead.Result.TooLarge, BoundedRead.read(endless, 1_048_576))
        assertTrue(served <= 1_048_576 + 8192, "read $served bytes")

        assertTrue(BoundedRead.declaredTooLarge(2_000_000, 1_048_576))
        assertFalse(BoundedRead.declaredTooLarge(null, 1_048_576))
        assertFalse(BoundedRead.declaredTooLarge(1_048_576, 1_048_576))
    }

    @Test
    fun `bounded read gives up after the deadline`() {
        var clock = 0L
        val slow = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int { clock += 1_000_000_000L; return 10 }
        }
        assertEquals(BoundedRead.Result.TimedOut, BoundedRead.read(slow, 1_000_000, deadlineNanos = 3_000_000_000L, now = { clock }))
    }

    @Test
    fun `cooldown rounds up and is off when zero`() {
        assertEquals(0, Cooldowns.remainingSeconds(null, 1000, 10))
        assertEquals(10, Cooldowns.remainingSeconds(1000, 1000, 10))
        assertEquals(1, Cooldowns.remainingSeconds(1000, 10_500, 10))
        assertEquals(0, Cooldowns.remainingSeconds(1000, 11_000, 10))
        assertEquals(0, Cooldowns.remainingSeconds(1000, 1000, 0))
    }

    @Test
    fun `upload response must carry a real short code`() {
        val ok = StudioClient.parseUploadResponse(
            """{"code":"abc2345","importCommand":"/aase import abc2345","url":"https://lyco-aase.tinyyana.com/s/abc2345","expiresAt":"2026-11-06T00:00:00Z"}""",
        )!!
        assertEquals("abc2345", ok.code)
        assertEquals("/aase import abc2345", ok.importCommand)
        assertEquals("https://lyco-aase.tinyyana.com/s/abc2345", ok.url)
        // Never echo arbitrary server text into a clickable command.
        assertNull(StudioClient.parseUploadResponse("""{"code":"abc2345; /op me"}"""))
        assertNull(StudioClient.parseUploadResponse("not json"))
        assertNull(StudioClient.parseUploadResponse("""{"code":"abc2345","url":"javascript:alert(1)"}""")!!.url)
    }
}
