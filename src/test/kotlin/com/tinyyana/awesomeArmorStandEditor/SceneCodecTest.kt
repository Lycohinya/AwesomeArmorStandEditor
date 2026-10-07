package com.tinyyana.awesomeArmorStandEditor

import com.google.gson.JsonParser
import com.tinyyana.awesomeArmorStandEditor.model.ArmorStandElement
import com.tinyyana.awesomeArmorStandEditor.model.DisplayElement
import com.tinyyana.awesomeArmorStandEditor.model.DisplayKind
import com.tinyyana.awesomeArmorStandEditor.model.Equipment
import com.tinyyana.awesomeArmorStandEditor.model.EulerXYZ
import com.tinyyana.awesomeArmorStandEditor.model.ItemRef
import com.tinyyana.awesomeArmorStandEditor.model.Pose6
import com.tinyyana.awesomeArmorStandEditor.model.Quat
import com.tinyyana.awesomeArmorStandEditor.model.Scene
import com.tinyyana.awesomeArmorStandEditor.model.Anchor
import com.tinyyana.awesomeArmorStandEditor.model.Transform
import com.tinyyana.awesomeArmorStandEditor.model.Vec3
import com.tinyyana.awesomeArmorStandEditor.store.ItemRefs
import com.tinyyana.awesomeArmorStandEditor.store.SceneCodec
import com.tinyyana.awesomeArmorStandEditor.store.SceneValidationException
import com.tinyyana.awesomeArmorStandEditor.store.SceneValidator
import com.tinyyana.awesomeArmorStandEditor.store.ShareCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SceneCodecTest {

    private val v2Legacy = """
        {
          "schemaVersion": 2,
          "id": "legacy-1",
          "owner": "00000000-0000-0000-0000-000000000001",
          "name": "Old save",
          "elements": [
            { "localId": 1, "offset": [0.0, 0.0, 0.0], "yaw": 10.0, "type": "armor_stand",
              "pose": { "head": [0.5, 0.0, 0.0], "body": [0.0, 0.0, 0.0], "leftArm": [0.0, 0.0, 0.0],
                        "rightArm": [-1.2, 0.0, 0.0], "leftLeg": [0.0, 0.0, 0.0], "rightLeg": [0.0, 0.0, 0.0] },
              "equipment": { "head": "rO0ABXNy-helmet", "mainHand": "rO0ABXNy-sword" },
              "flags": { "small": true, "invisible": false, "noBasePlate": false, "noGravity": true, "arms": true, "marker": false, "glowing": false } },
            { "localId": 2, "offset": [1.0, 0.0, 0.0], "yaw": 0.0, "type": "display", "kind": "ITEM",
              "transform": { "translation": [0.0, 0.0, 0.0], "leftRotation": [0.0, 0.0, 0.0, 1.0], "scale": [1.0, 1.0, 1.0], "rightRotation": [0.0, 0.0, 0.0, 1.0] },
              "payload": "rO0ABXNy-gem", "billboard": "FIXED", "viewRange": 1.0 },
            { "localId": 3, "offset": [2.0, 0.0, 0.0], "yaw": 0.0, "type": "display", "kind": "BLOCK",
              "transform": { "translation": [0.0, 0.0, 0.0], "leftRotation": [0.0, 0.0, 0.0, 1.0], "scale": [1.0, 1.0, 1.0], "rightRotation": [0.0, 0.0, 0.0, 1.0] },
              "payload": "minecraft:oak_stairs[facing=east]", "billboard": "FIXED", "viewRange": 1.0 }
          ]
        }
    """.trimIndent()

    @Test
    fun `v2 reads leniently and base64 becomes a legacy ItemRef`() {
        val scene = SceneCodec.fromJson(v2Legacy)
        val stand = scene.elements[0] as ArmorStandElement
        assertEquals(ItemRef.legacy("rO0ABXNy-helmet"), stand.equipment.head)
        assertEquals(ItemRef(id = null, bukkit = "rO0ABXNy-sword"), stand.equipment.mainHand)
        assertEquals(0.5, stand.pose.head.x)
        val item = scene.elements[1] as DisplayElement
        assertEquals(ItemRef.legacy("rO0ABXNy-gem"), item.item)
        assertEquals("", item.payload)
        assertEquals("minecraft:oak_stairs[facing=east]", (scene.elements[2] as DisplayElement).payload)
        assertEquals(Scene.SCHEMA_VERSION, scene.schemaVersion)
    }

    @Test
    fun `v2 to v3 keeps legacy items as strings and payload, and reads back the same`() {
        val scene = SceneCodec.fromJson(v2Legacy)
        val v3 = JsonParser.parseString(SceneCodec.toJson(scene)).asJsonObject
        assertEquals(3, v3["schemaVersion"].asInt)
        val stand = v3.getAsJsonArray("elements")[0].asJsonObject
        assertEquals("rO0ABXNy-helmet", stand.getAsJsonObject("equipment")["head"].asString)
        assertEquals(28.6479, stand.getAsJsonObject("poseDeg").getAsJsonArray("head")[0].asDouble)
        assertFalse(stand.has("pose"))
        val item = v3.getAsJsonArray("elements")[1].asJsonObject
        assertEquals("rO0ABXNy-gem", item["payload"].asString)
        assertFalse(item.has("item"))
        assertEquals("minecraft:oak_stairs[facing=east]", v3.getAsJsonArray("elements")[2].asJsonObject["block"].asString)
        assertTrue(SceneValidator.validate(v3).isEmpty())
    }

    @Test
    fun `v2 write is lossless for the v2 data and v2 round trips exactly`() {
        val scene = SceneCodec.fromJson(v2Legacy)
        val v2 = SceneCodec.toJson(scene, SceneCodec.WriteOptions(schema = 2))
        assertEquals(2, JsonParser.parseString(v2).asJsonObject["schemaVersion"].asInt)
        assertEquals(scene, SceneCodec.fromJson(v2))
    }

    @Test
    fun `v2 write uses bukkit, then the runtime encoder, and leaves out what it cannot encode`() {
        val scene = Scene(id = "s", owner = "o", name = "n")
        scene.elements += ArmorStandElement(
            localId = 1,
            equipment = Equipment(
                head = ItemRef("minecraft:iron_helmet", bukkit = "B64HEAD"),
                chest = ItemRef("minecraft:iron_chestplate"),
                legs = ItemRef("minecraft:unknown_thing"),
            ),
        )
        val warnings = mutableListOf<String>()
        val opts = SceneCodec.WriteOptions(
            schema = 2,
            legacyItemEncoder = { ref -> if (ref.id == "minecraft:iron_chestplate") "ENCODED" else null },
            onWarning = { warnings += it },
        )
        val eq = JsonParser.parseString(SceneCodec.toJson(scene, opts)).asJsonObject
            .getAsJsonArray("elements")[0].asJsonObject.getAsJsonObject("equipment")
        assertEquals("B64HEAD", eq["head"].asString)
        assertEquals("ENCODED", eq["chest"].asString)
        assertFalse(eq.has("legs"))
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("minecraft:unknown_thing"), warnings[0])
    }

    @Test
    fun `v3 normalises poseDeg, rotationDeg and defaults`() {
        val json = """
            {"schemaVersion":3,"name":"x","elements":[
              {"type":"armor_stand","localId":1,"offset":[0,0,0],"poseDeg":{"rightArm":[90,0,-45]}},
              {"type":"display","localId":2,"offset":[0,0,0],"kind":"TEXT","text":"a","transform":{"rotationDeg":[90,0,0]}}
            ]}
        """.trimIndent()
        val scene = SceneCodec.fromJson(json)
        assertTrue(scene.id.isNotBlank(), "missing id gets a fresh one")
        assertEquals("", scene.owner)
        val stand = scene.elements[0] as ArmorStandElement
        assertEquals(EulerXYZ(Math.toRadians(90.0), 0.0, Math.toRadians(-45.0)), stand.pose.rightArm)
        assertEquals(EulerXYZ.ZERO, stand.pose.head)
        assertTrue(stand.flags.noGravity && stand.flags.arms)
        val text = scene.elements[1] as DisplayElement
        assertEquals(Math.sqrt(0.5), text.transform.leftRotation.x, 1e-15)
        assertEquals(Math.sqrt(0.5), text.transform.leftRotation.w, 1e-15)
        assertEquals(Vec3.ONE, text.transform.scale)
        assertEquals(Quat.IDENTITY, text.transform.rightRotation)
        assertEquals("FIXED", text.billboard)
        assertEquals(1.0f, text.viewRange)
    }

    @Test
    fun `v3 write rounds poseDeg to 4 decimals and keeps quaternions exactly`() {
        val q = Quat(0.1234567890123, 0.2, 0.3, 0.9)
        val scene = Scene(id = "s", owner = "00000000-0000-0000-0000-000000000001", name = "n", lastAnchor = Anchor("w", 1.0, 2.0, 3.0))
        scene.elements += ArmorStandElement(localId = 1, pose = Pose6(head = EulerXYZ(0.1, 0.0, 0.0)))
        scene.elements += DisplayElement(localId = 2, kind = DisplayKind.ITEM, transform = Transform(leftRotation = q), item = ItemRef("minecraft:stone", 2, "[]", "B64"))
        val o = JsonParser.parseString(SceneCodec.toJson(scene)).asJsonObject
        val els = o.getAsJsonArray("elements")
        assertEquals(5.7296, els[0].asJsonObject.getAsJsonObject("poseDeg").getAsJsonArray("head")[0].asDouble)
        val disp = els[1].asJsonObject
        assertEquals(0.1234567890123, disp.getAsJsonObject("transform").getAsJsonArray("leftRotation")[0].asDouble)
        val item = disp.getAsJsonObject("item")
        assertEquals("minecraft:stone", item["id"].asString)
        assertEquals(2, item["count"].asInt)
        assertFalse(item.has("components"), "[] components are dropped")
        assertEquals("B64", item["bukkit"].asString)
        assertTrue(o.has("owner") && o.has("id") && o.has("lastAnchor"))

        val shared = JsonParser.parseString(SceneCodec.toJson(scene, SceneCodec.WriteOptions(includeIdentity = false))).asJsonObject
        assertFalse(shared.has("owner")); assertFalse(shared.has("id")); assertFalse(shared.has("lastAnchor"))
        assertTrue(SceneValidator.validate(shared).isEmpty())
    }

    @Test
    fun `v3 write keeps content the schema cannot hold in the legacy payload`() {
        val scene = Scene(id = "s", owner = "o", name = "n")
        val longText = "字".repeat(1500)
        scene.elements += DisplayElement(localId = 1, kind = DisplayKind.TEXT, payload = longText)
        scene.elements += DisplayElement(localId = 2, kind = DisplayKind.BLOCK, payload = "Minecraft:Weird Block")
        val o = JsonParser.parseString(SceneCodec.toJson(scene)).asJsonObject.getAsJsonArray("elements")
        assertEquals(longText, o[0].asJsonObject["payload"].asString)
        assertEquals("Minecraft:Weird Block", o[1].asJsonObject["payload"].asString)
        assertEquals(longText, (SceneCodec.fromJson(SceneCodec.toJson(scene)).elements[0] as DisplayElement).payload)
    }

    @Test
    fun `a scene v3 cannot express falls back to v2 instead of losing data`() {
        val scene = Scene(id = "s", owner = "o", name = "big")
        repeat(201) { scene.elements += ArmorStandElement(localId = it + 1) }
        val warnings = mutableListOf<String>()
        val text = SceneCodec.toJson(scene, SceneCodec.WriteOptions(onWarning = { warnings += it }))
        assertEquals(2, JsonParser.parseString(text).asJsonObject["schemaVersion"].asInt)
        // The plugin's own save file reads back (trusted, lenient v2)...
        assertEquals(201, SceneCodec.fromJson(text, trusted = true).elements.size)
        assertEquals(1, warnings.size)
        // ...but the same text from outside (an AASE1 code, a remote scene) is validated like v3.
        val untrusted = SceneCodec.decode(text) as SceneCodec.DecodeResult.Invalid
        assertEquals("/elements", untrusted.errors.first().pointer)
    }

    @Test
    fun `invalid v3 throws with pointers, malformed json is reported at the root`() {
        val e = assertFailsWith<SceneValidationException> {
            SceneCodec.fromJson("""{"schemaVersion":3,"name":"x","elements":[{"type":"armor_stand","localId":1,"offset":[0,0,0],"bogus":1}]}""")
        }
        assertEquals("/elements/0/bogus", e.errors.first().pointer)
        assertTrue(e.errors.first().message.contains("unknown property"))

        val bad = SceneCodec.decode("{name: 'lenient gson would take this'}")
        assertTrue(bad is SceneCodec.DecodeResult.Invalid)
        assertEquals("", bad.errors.first().pointer)
        assertTrue(SceneCodec.decode("""{"schemaVersion":3,"name":"x","elements":[]} trailing""") is SceneCodec.DecodeResult.Invalid)
    }

    @Test
    fun `validator counts code points and accepts integral numbers written as decimals`() {
        val emoji = "🌸" // one code point, two UTF-16 units
        assertTrue(SceneValidator.validateText("""{"schemaVersion":3,"name":"${emoji.repeat(64)}","elements":[]}""").isEmpty())
        assertEquals("/name", SceneValidator.validateText("""{"schemaVersion":3,"name":"${emoji.repeat(65)}","elements":[]}""").first().pointer)
        assertTrue(SceneValidator.validateText("""{"schemaVersion":3.0,"name":"a","elements":[{"type":"armor_stand","localId":1.0,"offset":[0,0,0]}]}""").isEmpty())
        assertEquals("/elements/0/localId", SceneValidator.validateText("""{"schemaVersion":3,"name":"a","elements":[{"type":"armor_stand","localId":1.5,"offset":[0,0,0]}]}""").first().pointer)
    }

    @Test
    fun `pointer tokens are RFC 6901 escaped`() {
        val errors = SceneValidator.validateText("""{"schemaVersion":3,"name":"a","elements":[],"a/b~c":1}""")
        assertEquals("/a~1b~0c", errors.first().pointer)
    }

    @Test
    fun `component strings are normalised to the bracket part`() {
        assertEquals("[enchantments={sharpness:5}]", ItemRefs.normalizeComponents("[enchantments={sharpness:5}]"))
        assertEquals("[custom_name=\"x\"]", ItemRefs.normalizeComponents("minecraft:diamond_sword[custom_name=\"x\"]"))
        assertEquals("[a=1]", ItemRefs.normalizeComponents("  [a=1]  "))
        assertNull(ItemRefs.normalizeComponents("[]"))
        assertNull(ItemRefs.normalizeComponents("[ ]"))
        assertNull(ItemRefs.normalizeComponents(""))
        assertNull(ItemRefs.normalizeComponents(null))
        assertNull(ItemRefs.normalizeComponents("minecraft:stone"))
        assertTrue(ItemRefs.isValidId("minecraft:diamond_sword"))
        assertFalse(ItemRefs.isValidId("Minecraft:Stone"))
        assertFalse(ItemRefs.isValidId("minecraft:stone\n"))
    }

    @Test
    fun `snbt escapes backslash, quote, CR, LF and TAB and nothing else`() {
        assertEquals("\"a\\\\b\\\"c\\rd\\ne\\tf'g§h\"", com.tinyyana.awesomeArmorStandEditor.export.SummonExporter.snbt("a\\b\"c\rd\ne\tf'g§h"))
    }

    @Test
    fun `share text is de-identified`() {
        val scene = Scene(id = "secret-id", owner = "00000000-0000-0000-0000-000000000007", name = "n", lastAnchor = Anchor("w", 0.0, 0.0, 0.0))
        scene.elements += ArmorStandElement(localId = 1)
        val decoded = ShareCode.decode(ShareCode.encode(scene))!!
        assertEquals("", decoded.owner)
        assertFalse(decoded.id == "secret-id")
        assertNull(decoded.lastAnchor)
    }
}
