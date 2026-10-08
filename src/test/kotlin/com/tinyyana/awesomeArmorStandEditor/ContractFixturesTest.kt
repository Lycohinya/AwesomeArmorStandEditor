package com.tinyyana.awesomeArmorStandEditor

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.tinyyana.awesomeArmorStandEditor.export.McFunctionExporter
import com.tinyyana.awesomeArmorStandEditor.export.SummonExporter
import com.tinyyana.awesomeArmorStandEditor.model.Scene
import com.tinyyana.awesomeArmorStandEditor.model.Vec3
import com.tinyyana.awesomeArmorStandEditor.store.SceneCodec
import com.tinyyana.awesomeArmorStandEditor.store.SceneError
import com.tinyyana.awesomeArmorStandEditor.store.SceneValidator
import com.tinyyana.awesomeArmorStandEditor.store.ShareCode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Golden fixtures shared with Pose Pavilion (the website copies src/test/resources/fixtures and the schema).
 *
 *  - `<name>.scene.json` → SummonExporter / McFunctionExporter must equal `<name>.summon.txt` /
 *    `<name>.mcfunction.json` exactly (readme fixed to "README");
 *  - `invalid/<name>.scene.json` → the first validation error's pointer must equal `<name>.expected.json`;
 *  - `math/rotation.json`, `math/pose.json` → computed here (JOML Quaterniond / Math.toRadians).
 *
 * Regenerate the expected files with `GOLDEN_UPDATE=1 ./gradlew test` (or `-Dgolden.update=true`),
 * then review the diff by hand.
 */
class ContractFixturesTest {

    private val dir = File("src/test/resources/fixtures")
    private val update = System.getProperty("golden.update") == "true" || System.getenv("GOLDEN_UPDATE") == "1"
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    private fun names(d: File, suffix: String) =
        (d.listFiles { f -> f.isFile && f.name.endsWith(suffix) } ?: emptyArray()).map { it.name.removeSuffix(suffix) }.sorted()

    private fun load(file: File): Scene = when (val r = SceneCodec.decode(file.readText(Charsets.UTF_8))) {
        is SceneCodec.DecodeResult.Ok -> r.scene
        is SceneCodec.DecodeResult.Invalid -> fail("${file.name} is not a valid scene: ${r.errors}")
    }

    private fun mcfunctionJson(files: Map<String, String>) = gson.toJson(files) + "\n"

    @Test
    fun `positive fixtures export exactly the golden summon and mcfunction output`() {
        val scenes = names(dir, ".scene.json")
        assertTrue(scenes.size >= 5, "expected at least 5 golden scenes, found $scenes")
        val mismatches = mutableListOf<String>()
        for (name in scenes) {
            val scene = load(File(dir, "$name.scene.json"))
            assertEquals(3, JsonParser.parseString(File(dir, "$name.scene.json").readText()).asJsonObject["schemaVersion"].asInt, name)
            val summon = SummonExporter.export(scene) + "\n"
            val mc = McFunctionExporter.export(scene, "README")
            val summonFile = File(dir, "$name.summon.txt")
            val mcFile = File(dir, "$name.mcfunction.json")
            if (update) {
                summonFile.writeText(summon, Charsets.UTF_8)
                mcFile.writeText(mcfunctionJson(mc), Charsets.UTF_8)
                continue
            }
            if (!summonFile.isFile || !mcFile.isFile) {
                mismatches += "$name: golden files missing (run with GOLDEN_UPDATE=1)"
                continue
            }
            if (summonFile.readText(Charsets.UTF_8).replace("\r\n", "\n") != summon) mismatches += "$name.summon.txt"
            @Suppress("UNCHECKED_CAST")
            val expected = gson.fromJson(mcFile.readText(Charsets.UTF_8), LinkedHashMap::class.java) as Map<String, String>
            if (expected != mc) mismatches += "$name.mcfunction.json"
        }
        assertTrue(mismatches.isEmpty(), "golden output differs: $mismatches")
    }

    @Test
    fun `positive fixtures survive a v3 write and read without changing the export`() {
        for (name in names(dir, ".scene.json")) {
            val scene = load(File(dir, "$name.scene.json"))
            val written = SceneCodec.toJson(scene)
            assertTrue(SceneValidator.validateText(written).isEmpty(), "$name: written v3 must validate: ${SceneValidator.validateText(written)}")
            val again = SceneCodec.fromJson(written)
            assertEquals(written, SceneCodec.toJson(again), "$name: v3 write must be stable after one pass")
            assertEquals(SummonExporter.export(scene), SummonExporter.export(again), "$name: export changed after v3 round trip")
            assertEquals(McFunctionExporter.export(scene, "README"), McFunctionExporter.export(again, "README"), name)
        }
    }

    @Test
    fun `invalid fixtures report the expected first pointer`() {
        val invalid = File(dir, "invalid")
        val cases = names(invalid, ".scene.json")
        assertTrue(cases.size >= 10, "expected at least 10 invalid fixtures, found $cases")
        val wrong = mutableListOf<String>()
        val table = StringBuilder()
        for (name in cases) {
            val expected = JsonParser.parseString(File(invalid, "$name.expected.json").readText()).asJsonObject["pointer"].asString
            val errors = SceneValidator.validateText(File(invalid, "$name.scene.json").readText(Charsets.UTF_8))
            val got = errors.firstOrNull()?.pointer
            table.append("$name\t$got\t${errors.firstOrNull()?.message}\n")
            if (got != expected) wrong += "$name: expected $expected, got $got (${errors.take(3)})"
            // The codec must refuse it too.
            assertTrue(SceneCodec.decode(File(invalid, "$name.scene.json").readText()) is SceneCodec.DecodeResult.Invalid, name)
        }
        // name <TAB> first pointer <TAB> message, to compare with the website's validator.
        File("build").mkdirs()
        File("build/contract-invalid-pointers.tsv").writeText(table.toString(), Charsets.UTF_8)
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun `real legacy AASE1 share codes pass the strict untrusted import path`() {
        val share = File(dir, "share")
        val codes = names(share, ".txt")
        assertTrue(codes.size >= 2, "expected the legacy share codes in fixtures/share, found $codes")
        for (name in codes) {
            val code = File(share, "$name.txt").readText(Charsets.UTF_8).trim()
            when (val r = ShareCode.decodeResult(code)) {
                is ShareCode.Decoded.Ok -> assertTrue(r.scene.elements.isNotEmpty(), name)
                is ShareCode.Decoded.Invalid -> fail("$name was rejected: ${r.errors.take(3)}")
                ShareCode.Decoded.Malformed -> fail("$name is malformed")
            }
        }
    }

    @Test
    fun `nesting depth is measured outside strings and refused before parsing`() {
        val ok = "[".repeat(SceneValidator.MAX_DEPTH) + "]".repeat(SceneValidator.MAX_DEPTH)
        val tooDeep = "[".repeat(SceneValidator.MAX_DEPTH + 1) + "]".repeat(SceneValidator.MAX_DEPTH + 1)
        assertTrue(!SceneValidator.textDepthExceeds(ok))
        assertTrue(SceneValidator.textDepthExceeds(tooDeep))
        // brackets and escaped quotes inside a string do not count
        val inString = "{\"name\":\"" + "[{\\\"".repeat(100) + "\",\"x\":1}"
        assertTrue(!SceneValidator.textDepthExceeds(inString), inString)
        val errors = SceneValidator.validateText("{\"schemaVersion\":3,\"name\":\"x\",\"elements\":$tooDeep}")
        assertEquals(listOf(SceneError("", SceneValidator.TOO_DEEP)), errors)
        val decoded = SceneCodec.decode("{\"a\":$tooDeep}") as SceneCodec.DecodeResult.Invalid
        assertTrue(decoded.errors.single().message.contains("nesting too deep"))
    }

    @Test
    fun `non-finite numbers are reported at the number, even where the schema has no range`() {
        val errors = SceneValidator.validateText(
            """{"schemaVersion":3,"name":"x","elements":[],"emitters":[{"id":1,"speed":-1e999}]}""",
        )
        assertEquals(listOf(SceneError("/emitters/0/speed", SceneValidator.NOT_FINITE)), errors)
        assertTrue(SceneValidator.NOT_FINITE.contains("must be a finite number"))
    }

    private val rotationInputs = listOf(
        Vec3(30.0, 45.0, 60.0), Vec3(90.0, 0.0, 0.0), Vec3(-45.0, 120.0, 10.0), Vec3(0.0, 0.0, 0.0),
        Vec3(180.0, -90.0, 45.0), Vec3(0.0, 90.0, 0.0), Vec3(0.0, 0.0, 90.0), Vec3(10.0, 20.0, 30.0),
        Vec3(-90.0, -90.0, -90.0), Vec3(0.5, 359.0, -7.25),
    )

    @Test
    fun `math rotation fixture matches JOML Quaterniond rotationXYZ`() {
        val file = File(dir, "math/rotation.json")
        val rows = rotationInputs.map { d -> d to SceneCodec.quatFromEulerXYZDeg(d) }
        if (update) {
            file.parentFile.mkdirs()
            file.writeText(
                rows.joinToString(",\n", "[\n", "\n]\n") { (d, q) -> "  [[${d.x}, ${d.y}, ${d.z}], [${q.x}, ${q.y}, ${q.z}, ${q.w}]]" },
                Charsets.UTF_8,
            )
            return
        }
        val stored = JsonParser.parseString(file.readText()).asJsonArray
        assertEquals(rows.size, stored.size())
        stored.forEachIndexed { i, row ->
            val (deg, q) = row.asJsonArray.let { it[0].asJsonArray to it[1].asJsonArray }
            val d = Vec3(deg[0].asDouble, deg[1].asDouble, deg[2].asDouble)
            val got = SceneCodec.quatFromEulerXYZDeg(d)
            assertEquals(rotationInputs[i], d)
            listOf(got.x, got.y, got.z, got.w).forEachIndexed { k, v -> assertEquals(q[k].asDouble, v, 1e-12, "row $i") }
        }
        // Unit quaternions, and the [90,0,0] case is the textbook value.
        val q90 = SceneCodec.quatFromEulerXYZDeg(Vec3(90.0, 0.0, 0.0))
        assertEquals(Math.sqrt(0.5), q90.x, 1e-12); assertEquals(Math.sqrt(0.5), q90.w, 1e-12)
    }

    private val poseInputs = listOf(
        Vec3(0.0, 0.0, 0.0), Vec3(90.0, -90.0, 180.0), Vec3(-10.5, 12.25, 359.0), Vec3(0.0001, -0.0001, 45.0), Vec3(-120.0, 30.0, 0.5),
    )

    @Test
    fun `math pose fixture is degrees to radians both ways`() {
        val file = File(dir, "math/pose.json")
        if (update) {
            file.parentFile.mkdirs()
            file.writeText(
                poseInputs.joinToString(",\n", "[\n", "\n]\n") { d ->
                    "  [[${d.x}, ${d.y}, ${d.z}], [${Math.toRadians(d.x)}, ${Math.toRadians(d.y)}, ${Math.toRadians(d.z)}]]"
                },
                Charsets.UTF_8,
            )
            return
        }
        val stored = JsonParser.parseString(file.readText()).asJsonArray
        assertEquals(poseInputs.size, stored.size())
        for (row in stored) {
            val deg = row.asJsonArray[0].asJsonArray
            val rad = row.asJsonArray[1].asJsonArray
            for (k in 0 until 3) {
                assertEquals(rad[k].asDouble, Math.toRadians(deg[k].asDouble), 0.0)
                // radians -> degrees -> 4 decimals gives the degrees back (what a v3 write stores)
                assertEquals(deg[k].asDouble, SceneCodec.deg4(rad[k].asDouble), 0.0)
            }
        }
    }

    @Test
    fun `fixtures carry ids so the mcfunction tag prefix is stable`() {
        for (name in names(dir, ".scene.json")) {
            val o = JsonParser.parseString(File(dir, "$name.scene.json").readText()).asJsonObject
            assertTrue(o.has("id"), "$name has no id")
        }
        // and the schema shipped in the jar is the repo's schema/scene.v3.schema.json
        val bundled = javaClass.classLoader.getResourceAsStream(SceneValidator.SCHEMA_RESOURCE)!!.reader().readText()
        assertEquals(JsonParser.parseString(File("schema/scene.v3.schema.json").readText()), JsonParser.parseString(bundled))
    }
}
