package com.tinyyana.awesomeArmorStandEditor

import com.tinyyana.awesomeArmorStandEditor.model.Vec3
import com.tinyyana.awesomeArmorStandEditor.recall.Candidate
import com.tinyyana.awesomeArmorStandEditor.recall.CloseDecision
import com.tinyyana.awesomeArmorStandEditor.recall.HintThrottle
import com.tinyyana.awesomeArmorStandEditor.recall.LegacyGrouping
import com.tinyyana.awesomeArmorStandEditor.recall.OrphanRule
import com.tinyyana.awesomeArmorStandEditor.recall.PendingRemove
import com.tinyyana.awesomeArmorStandEditor.recall.RemovePlan
import com.tinyyana.awesomeArmorStandEditor.recall.RemoveScope
import com.tinyyana.awesomeArmorStandEditor.recall.SceneIds
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Recall deletes placed work, so the rules deciding *which* entities go — grouping, ownership,
 * orphan status — are pinned here without a server.
 */
class RecallLogicTest {

    private val me = UUID.randomUUID()
    private val other = UUID.randomUUID()

    private fun c(
        localId: Int, x: Double, y: Double = 64.0, z: Double = 0.0,
        owner: UUID = me, scene: String = "s1", placement: String? = null, name: String? = null,
        emitter: Boolean = false, world: String = "world",
    ) = Candidate(UUID.randomUUID(), owner, scene, localId, placement, name, emitter, world, Vec3(x, y, z))

    // --- RemovePlan ---------------------------------------------------------

    @Test
    fun `only the requester's own entities are ever planned`() {
        val mine = c(1, 0.0, placement = "p1")
        val theirs = c(1, 0.5, owner = other, placement = "p1")
        val plan = RemovePlan.plan(listOf(mine, theirs), me, RemoveScope.Placement("p1"))
        assertEquals(listOf(mine.id), plan.ids)
    }

    @Test
    fun `placement scope takes one copy and leaves the other copy of the same scene`() {
        val a1 = c(1, 0.0, placement = "pa")
        val a2 = c(2, 1.0, placement = "pa")
        val aFx = c(1, 0.5, placement = "pa", emitter = true)
        val b1 = c(1, 3.0, placement = "pb")
        val plan = RemovePlan.plan(listOf(a1, a2, aFx, b1), me, RemoveScope.Placement("pa"))
        assertEquals(setOf(a1.id, a2.id, aFx.id), plan.ids.toSet())
        assertEquals(2, plan.elements)
        assertEquals(1, plan.emitters)
        assertEquals(1, plan.groups)
        assertEquals(setOf("pa"), plan.placements)
    }

    @Test
    fun `sphere scope is a true sphere in one world and counts groups`() {
        val near = c(1, 3.0, placement = "pa")
        val corner = c(2, 6.0, z = 6.0, placement = "pb") // inside the box, outside an 8-radius sphere
        val farWorld = c(3, 1.0, placement = "pc", world = "nether")
        val legacy = c(4, 2.0)
        val scope = RemoveScope.Sphere("world", Vec3(0.0, 64.0, 0.0), 8.0)
        val plan = RemovePlan.plan(listOf(near, corner, farWorld, legacy), me, scope)
        assertEquals(setOf(near.id, legacy.id), plan.ids.toSet())
        assertEquals(2, plan.groups, "placement pa + one legacy scene group")
    }

    @Test
    fun `scene scope matches the stamped name case-insensitively, or the saved id for legacy entities`() {
        val stamped = c(1, 0.0, placement = "pa", name = "Cherry Tree")
        val legacy = c(2, 0.0, scene = "old-id")
        val unrelated = c(3, 0.0, placement = "pb", name = "Lamp")
        val plan = RemovePlan.plan(
            listOf(stamped, legacy, unrelated), me, RemoveScope.SceneName("cherry tree", legacySceneId = "old-id"),
        )
        assertEquals(setOf(stamped.id, legacy.id), plan.ids.toSet())

        val noSave = RemovePlan.plan(listOf(stamped, legacy), me, RemoveScope.SceneName("CHERRY TREE", null))
        assertEquals(listOf(stamped.id), noSave.ids, "without a save the legacy path is simply absent")
    }

    @Test
    fun `duplicate candidates from index and nearby search are counted once`() {
        val e = c(1, 0.0, placement = "pa")
        val plan = RemovePlan.plan(listOf(e, e), me, RemoveScope.Placement("pa"))
        assertEquals(1, plan.ids.size)
    }

    @Test
    fun `pending remove expires after 30 seconds`() {
        val p = PendingRemove(RemoveScope.Placement("pa"), "world", Vec3.ZERO, createdAtMillis = 1_000)
        assertFalse(p.isExpired(1_000 + 30_000))
        assertTrue(p.isExpired(1_000 + 30_001))
    }

    // --- LegacyGrouping -----------------------------------------------------

    @Test
    fun `with a save, picks the element nearest each expected spot and never the other copy`() {
        // Saved scene: #1 at origin, #2 one block east, emitter #1 half a block up.
        val offsets = LegacyGrouping.Offsets(
            elements = mapOf(1 to Vec3(0.0, 0.0, 0.0), 2 to Vec3(1.0, 0.0, 0.0)),
            emitters = mapOf(1 to Vec3(0.0, 0.5, 0.0)),
        )
        // Copy A at x=0, copy B at x=5 — same owner, same scene id, no placement.
        val a1 = c(1, 0.0); val a2 = c(2, 1.0); val aFx = c(1, 0.0, y = 64.5, emitter = true)
        val b1 = c(1, 5.0); val b2 = c(2, 6.0)
        val picked = LegacyGrouping.select(seed = a2, candidates = listOf(a1, a2, aFx, b1, b2), offsets = offsets)
        assertEquals(setOf(a1.id, a2.id, aFx.id), picked)
    }

    @Test
    fun `a missing element is not borrowed from a neighbouring copy`() {
        val offsets = LegacyGrouping.Offsets(mapOf(1 to Vec3.ZERO, 2 to Vec3(1.0, 0.0, 0.0)), emptyMap())
        val a1 = c(1, 0.0)
        val b2 = c(2, 5.0) // copy A's #2 is gone; B's #2 is 4 blocks from where A's should be
        assertEquals(setOf(a1.id), LegacyGrouping.select(a1, listOf(a1, b2), offsets))
    }

    @Test
    fun `migrated entities, other owners and other scenes are never grouped as legacy`() {
        val offsets = LegacyGrouping.Offsets(mapOf(1 to Vec3.ZERO, 2 to Vec3(1.0, 0.0, 0.0)), emptyMap())
        val seed = c(1, 0.0)
        val migrated = c(2, 1.0, placement = "pa")
        val theirs = c(2, 1.0, owner = other)
        val otherScene = c(2, 1.0, scene = "s2")
        assertEquals(setOf(seed.id), LegacyGrouping.select(seed, listOf(seed, migrated, theirs, otherScene), offsets))
    }

    @Test
    fun `without a save, takes same owner and scene within 16 blocks`() {
        val seed = c(1, 0.0)
        val near = c(2, 15.0)
        val far = c(3, 17.0)
        val otherWorld = c(4, 1.0, world = "nether")
        assertEquals(setOf(seed.id, near.id), LegacyGrouping.select(seed, listOf(seed, near, far, otherWorld), offsets = null))
    }

    @Test
    fun `a seed whose localId the save no longer lists falls back to the radius rule`() {
        val offsets = LegacyGrouping.Offsets(mapOf(1 to Vec3.ZERO), emptyMap())
        val seed = c(9, 0.0)
        val near = c(1, 3.0)
        assertEquals(setOf(seed.id, near.id), LegacyGrouping.select(seed, listOf(seed, near), offsets))
    }

    // --- OrphanRule ---------------------------------------------------------

    @Test
    fun `no save and no open session is an orphan, an open session protects it`() {
        assertTrue(OrphanRule.isOrphan(1, emitter = false, saved = null, session = null))
        assertFalse(OrphanRule.isOrphan(1, emitter = false, saved = null, session = SceneIds(setOf(1), emptySet())))
    }

    @Test
    fun `a localId the save no longer lists is an orphan unless the open session still has it`() {
        val saved = SceneIds(setOf(1, 2), setOf(1))
        assertFalse(OrphanRule.isOrphan(2, emitter = false, saved = saved, session = null))
        assertTrue(OrphanRule.isOrphan(3, emitter = false, saved = saved, session = null))
        assertFalse(OrphanRule.isOrphan(3, emitter = false, saved = saved, session = SceneIds(setOf(1, 2, 3), emptySet())))
    }

    @Test
    fun `emitter markers are judged against emitter ids, not element ids`() {
        val saved = SceneIds(elements = setOf(1), emitters = setOf(2))
        assertFalse(OrphanRule.isOrphan(2, emitter = true, saved = saved, session = null))
        assertTrue(OrphanRule.isOrphan(1, emitter = true, saved = saved, session = null))
    }

    // --- CloseDecision ------------------------------------------------------

    @Test
    fun `close never drops unsaved work without asking`() {
        assertEquals(CloseDecision.Action.NO_SESSION, CloseDecision.decide(false, false, null))
        assertEquals(CloseDecision.Action.NO_SESSION, CloseDecision.decide(false, true, "discard"))
        assertEquals(CloseDecision.Action.CLOSE, CloseDecision.decide(true, false, null))
        assertEquals(CloseDecision.Action.ASK, CloseDecision.decide(true, true, null))
        assertEquals(CloseDecision.Action.SAVE_THEN_CLOSE, CloseDecision.decide(true, true, "save"))
        assertEquals(CloseDecision.Action.DISCARD, CloseDecision.decide(true, true, "DISCARD"))
        assertEquals(CloseDecision.Action.USAGE, CloseDecision.decide(true, true, "bogus"))
    }

    // --- HintThrottle -------------------------------------------------------

    @Test
    fun `hints are limited to one per three seconds per player`() {
        val t = HintThrottle(3_000)
        assertTrue(t.tryAcquire(me, 0))
        assertFalse(t.tryAcquire(me, 2_999))
        assertTrue(t.tryAcquire(other, 2_999), "another player has their own budget")
        assertTrue(t.tryAcquire(me, 3_000))
        t.forget(me)
        assertTrue(t.tryAcquire(me, 3_001))
    }
}
