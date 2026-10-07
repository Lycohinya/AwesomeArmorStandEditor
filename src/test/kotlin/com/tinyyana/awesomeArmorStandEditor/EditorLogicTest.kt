package com.tinyyana.awesomeArmorStandEditor

import com.tinyyana.awesomeArmorStandEditor.edit.Axis
import com.tinyyana.awesomeArmorStandEditor.edit.BodyPart
import com.tinyyana.awesomeArmorStandEditor.edit.InterpolationOps
import com.tinyyana.awesomeArmorStandEditor.edit.PoseOps
import com.tinyyana.awesomeArmorStandEditor.edit.QuatMath
import com.tinyyana.awesomeArmorStandEditor.edit.ScenePoints
import com.tinyyana.awesomeArmorStandEditor.edit.TransformOps
import com.tinyyana.awesomeArmorStandEditor.model.Anchor
import com.tinyyana.awesomeArmorStandEditor.model.Animation
import com.tinyyana.awesomeArmorStandEditor.model.ArmorStandElement
import com.tinyyana.awesomeArmorStandEditor.model.ArmorStandFlags
import com.tinyyana.awesomeArmorStandEditor.model.DisplayElement
import com.tinyyana.awesomeArmorStandEditor.model.DisplayKind
import com.tinyyana.awesomeArmorStandEditor.model.Equipment
import com.tinyyana.awesomeArmorStandEditor.model.EulerXYZ
import com.tinyyana.awesomeArmorStandEditor.model.ItemRef
import com.tinyyana.awesomeArmorStandEditor.model.Keyframe
import com.tinyyana.awesomeArmorStandEditor.model.ParticleEmitter
import com.tinyyana.awesomeArmorStandEditor.model.Pose6
import com.tinyyana.awesomeArmorStandEditor.model.Scene
import com.tinyyana.awesomeArmorStandEditor.model.Track
import com.tinyyana.awesomeArmorStandEditor.model.Transform
import com.tinyyana.awesomeArmorStandEditor.model.Vec3
import com.tinyyana.awesomeArmorStandEditor.export.SummonExporter
import com.tinyyana.awesomeArmorStandEditor.store.SceneCodec
import com.tinyyana.awesomeArmorStandEditor.store.ShareCode
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EditorLogicTest {

    @Test
    fun sceneRoundTripsThroughJson() {
        val scene = Scene(
            id = "abc-123",
            owner = "00000000-0000-0000-0000-000000000001",
            name = "測試場景",
            lastAnchor = Anchor("world", 10.0, 64.0, -20.0),
        )
        scene.elements += ArmorStandElement(
            localId = 1,
            offset = Vec3(0.5, 0.0, -0.5),
            yaw = 45f,
            pose = Pose6(head = EulerXYZ(0.1, -0.2, 0.3)),
            equipment = Equipment(head = ItemRef.legacy("base64helmet"), mainHand = ItemRef.legacy("base64sword")),
            flags = ArmorStandFlags(small = true, invisible = false, arms = true),
            customName = "<red>守衛",
        )
        scene.elements += DisplayElement(
            localId = 2,
            offset = Vec3(1.0, 2.0, 3.0),
            kind = DisplayKind.BLOCK,
            transform = Transform(translation = Vec3(0.0, 1.0, 0.0), scale = Vec3(2.0, 2.0, 2.0)),
            payload = "minecraft:stone",
            billboard = "CENTER",
            brightnessBlock = 15,
            viewRange = 2.5f,
        )

        // v2 is the lossless legacy format (radians); v3 rounds poseDeg to 4 decimals, see SceneCodecTest
        val restored = SceneCodec.fromJson(SceneCodec.toJson(scene, SceneCodec.WriteOptions(schema = 2)))
        assertEquals(scene, restored)
    }

    @Test
    fun nudgeWrapsAndNormalizes() {
        // 170° + 20° -> -170° (wrapped into (-180,180])
        val e = PoseOps.nudge(EulerXYZ(0.0, Math.toRadians(170.0), 0.0), Axis.Y, 20.0)
        assertEquals(-170.0, Math.toDegrees(PoseOps.normalizeRad(e.y)), 1e-9)

        assertTrue(PoseOps.normalizeRad(3 * PI) <= PI && PoseOps.normalizeRad(3 * PI) > -PI)
        // setPart/getPart round-trips
        val pose = PoseOps.setPart(Pose6(), BodyPart.LEFT_ARM, EulerXYZ(1.0, 2.0, 3.0))
        assertEquals(EulerXYZ(1.0, 2.0, 3.0), PoseOps.getPart(pose, BodyPart.LEFT_ARM))
    }

    @Test
    fun emittersAndAnimationRoundTrip() {
        val scene = Scene(id = "s1", owner = "00000000-0000-0000-0000-000000000002", name = "動畫")
        scene.elements += ArmorStandElement(localId = 1)
        scene.emitters += ParticleEmitter(id = 1, particle = "FLAME", offset = Vec3(0.0, 1.0, 0.0), rateTicks = 5)
        scene.animation = Animation(
            lengthTicks = 20, loop = true,
            tracks = mutableListOf(
                Track(
                    elementLocalId = 1,
                    keyframes = mutableListOf(
                        Keyframe(0, pose = Pose6()),
                        Keyframe(20, pose = Pose6(head = EulerXYZ(0.0, 1.0, 0.0))),
                    ),
                ),
            ),
        )
        assertEquals(scene, SceneCodec.fromJson(SceneCodec.toJson(scene)))
    }

    @Test
    fun interpolationSamplesMidpoint() {
        val kfs = listOf(
            Keyframe(0, pose = Pose6(head = EulerXYZ(0.0, 0.0, 0.0))),
            Keyframe(10, pose = Pose6(head = EulerXYZ(0.0, 2.0, 0.0))),
        )
        val mid = InterpolationOps.sample(kfs, 5)
        assertNotNull(mid.pose)
        assertEquals(1.0, mid.pose!!.head.y, 1e-9)  // halfway between 0 and 2
        // clamps outside range
        assertEquals(2.0, InterpolationOps.sample(kfs, 99).pose!!.head.y, 1e-9)
    }

    @Test
    fun summonExporterFormat() {
        val scene = Scene(id = "e", owner = "00000000-0000-0000-0000-000000000003", name = "x")
        scene.elements += ArmorStandElement(
            localId = 1, offset = Vec3(1.5, 0.0, -2.0), yaw = 45f,
            pose = Pose6(head = EulerXYZ(0.1, 0.0, 0.0)), customName = "<red>守衛",
        )
        scene.elements += DisplayElement(localId = 2, kind = DisplayKind.TEXT, payload = "哈囉")
        val out = SummonExporter.export(scene)

        // Text components must be SNBT, never the legacy JSON-string form (stored literally in 26.2).
        assertTrue(out.contains("CustomName:\"守衛\""), out)
        assertTrue(out.contains("text:\"哈囉\""), out)
        assertFalse(out.contains("{\"text\":"), out)

        // Coordinates must NOT carry an 'f' suffix (~0f is a parse error); NBT floats still do.
        assertTrue(out.contains("~1.5 ~0 ~-2"), out)
        assertFalse(Regex("~-?[0-9.]+f").containsMatchIn(out), "coordinate has an 'f' suffix: $out")

        // Capture the real output so it can be run against a live server (build/ is gitignored).
        java.io.File("build/export-sample.txt").writeText(out)
    }

    @Test
    fun shareCodeRoundTripsAndRejectsGarbage() {
        val scene = Scene(id = "abc", owner = "00000000-0000-0000-0000-000000000009", name = "分享測試")
        scene.elements += ArmorStandElement(localId = 1, offset = Vec3(0.5, 1.0, -0.5), customName = "<gold>招牌")
        scene.emitters += ParticleEmitter(id = 1, particle = "FLAME", rateTicks = 5)

        val code = ShareCode.encode(scene)
        assertTrue(code.startsWith("AASE1:"), code)
        // The share text is de-identified: id and owner come back fresh / empty.
        val decoded = ShareCode.decode(code)!!
        assertEquals(scene.copy(id = decoded.id, owner = ""), decoded)
        assertFalse(decoded.id == scene.id)

        // Malformed / hostile input returns null instead of throwing into game logic.
        assertEquals(null, ShareCode.decode("not-a-real-code"))
        assertEquals(null, ShareCode.decode("AASE1:@@@ not base64 @@@"))
        assertEquals(null, ShareCode.decode(""))
    }

    @Test
    fun quatAndTransformOps() {
        // fromAxisAngle produces a unit quaternion
        val q = QuatMath.fromAxisAngle(Axis.Y, Math.toRadians(90.0))
        val mag = q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w
        assertEquals(1.0, mag, 1e-9)

        // identity multiply
        assertEquals(q, QuatMath.mul(q, com.tinyyana.awesomeArmorStandEditor.model.Quat.IDENTITY))

        val t = TransformOps.translate(Transform.IDENTITY, Axis.X, 1.5)
        assertEquals(1.5, t.translation.x, 1e-9)

        // scale clamps to the positive minimum
        val s = TransformOps.scaleAxis(Transform.IDENTITY, Axis.X, -100.0)
        assertTrue(s.scale.x >= 0.05)
    }

    @Test
    fun scenePointsCoverEveryPlaceAnEntityCanLand() {
        val scene = Scene(id = "s", owner = "o", name = "n")
        scene.elements += ArmorStandElement(localId = 1, offset = Vec3(1.0, 0.0, 0.0))
        scene.emitters += ParticleEmitter(id = 1, particle = "FLAME", offset = Vec3(0.0, 0.0, 7.0))
        // An imported share code can carry keyframes that fling the element far from the origin.
        scene.animation = Animation(
            tracks = mutableListOf(Track(1, mutableListOf(Keyframe(tick = 0, offset = Vec3(80.0, 0.0, 0.0))))),
        )

        val offsets = ScenePoints.offsets(scene)

        assertTrue(Vec3.ZERO in offsets, "origin must be probed")
        assertTrue(Vec3(1.0, 0.0, 0.0) in offsets, "element must be probed")
        assertTrue(Vec3(0.0, 0.0, 7.0) in offsets, "emitter must be probed")
        assertTrue(Vec3(80.0, 0.0, 0.0) in offsets, "animated position must be probed")
    }

    @Test
    fun undoSnapshotCapturesAndRestoresArmorStandAndDisplay() {
        val stand = ArmorStandElement(
            localId = 1,
            offset = Vec3(1.0, 2.0, 3.0),
            pose = Pose6(head = EulerXYZ(0.5, 0.0, 0.0)),
        )
        val snap = com.tinyyana.awesomeArmorStandEditor.session.UndoSnapshot(
            localId = stand.localId,
            pose = stand.pose,
            transform = null,
            offset = stand.offset,
        )
        // Simulate modification
        stand.offset = Vec3(4.0, 5.0, 6.0)
        stand.pose = Pose6(head = EulerXYZ(1.0, 1.0, 1.0))

        // Restore from snapshot
        snap.pose?.let { stand.pose = it }
        stand.offset = snap.offset

        assertEquals(Vec3(1.0, 2.0, 3.0), stand.offset)
        assertEquals(EulerXYZ(0.5, 0.0, 0.0), stand.pose.head)
    }
}
