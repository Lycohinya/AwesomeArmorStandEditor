package com.tinyyana.awesomeArmorStandEditor.session

import com.tinyyana.awesomeArmorStandEditor.model.Pose6
import com.tinyyana.awesomeArmorStandEditor.model.Transform
import com.tinyyana.awesomeArmorStandEditor.model.Vec3

/**
 * Captures the state of one element immediately before an [adjust] so it can be restored once.
 * Single-slot only: a new adjust overwrites the previous snapshot.
 */
data class UndoSnapshot(
    val localId: Int,
    val pose: Pose6?,       // non-null for ArmorStandElement
    val transform: Transform?, // non-null for DisplayElement
    val offset: Vec3,
)
