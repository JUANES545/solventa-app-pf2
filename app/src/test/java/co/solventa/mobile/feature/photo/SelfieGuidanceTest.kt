package co.solventa.mobile.feature.photo

import org.junit.Assert.*
import org.junit.Test

class SelfieGuidanceTest {
    private val centered = SelfiePosition(.5f, .5f, .8f, .8f, 0f, 0f, 0f)
    private fun sample(gate: SelfieGuidance, time: Long, position: SelfiePosition? = centered,
        count: Int = 1, active: Boolean = true, age: Long = 0, frame: Long = time + 1) =
        gate.update(count, position, time - age, time, frame, active)

    @Test fun exactlyOneFaceRequired() {
        assertEquals(SelfieHint.NO_FACE, sample(SelfieGuidance(), 10, count = 0))
        assertEquals(SelfieHint.MULTIPLE, sample(SelfieGuidance(), 10, count = 2))
        assertEquals(SelfieHint.MULTIPLE, sample(SelfieGuidance(), 10, count = 10))
        assertEquals(SelfieHint.HOLD, sample(SelfieGuidance(), 10))
    }

    @Test fun geometryPoseAndPixelQualityRejectTheirBoundaries() {
        listOf(
            centered.copy(centerX = .399f) to SelfieHint.CENTER,
            centered.copy(centerY = .601f) to SelfieHint.CENTER,
            centered.copy(width = .619f) to SelfieHint.CLOSER,
            centered.copy(height = .619f) to SelfieHint.CLOSER,
            centered.copy(width = .961f) to SelfieHint.FARTHER,
            centered.copy(height = .981f) to SelfieHint.FARTHER,
            centered.copy(pitch = -12.01f) to SelfieHint.STRAIGHT,
            centered.copy(yaw = 12.01f) to SelfieHint.STRAIGHT,
            centered.copy(roll = -10.01f) to SelfieHint.STRAIGHT,
            centered.copy(enoughPixels = false) to SelfieHint.CLOSER,
            centered.copy(centerX = Float.NaN) to SelfieHint.PREPARING,
            centered.copy(pitch = Float.POSITIVE_INFINITY) to SelfieHint.PREPARING,
        ).forEach { (position, expected) -> assertEquals(expected, sample(SelfieGuidance(), 10, position)) }
        listOf(centered.copy(centerX = .4f), centered.copy(centerX = .6f),
            centered.copy(centerY = .4f), centered.copy(centerY = .6f),
            centered.copy(width = .62f, height = .62f), centered.copy(width = .96f, height = .98f),
            centered.copy(pitch = 12f, yaw = -12f, roll = 10f)).forEach {
            assertEquals(SelfieHint.HOLD, sample(SelfieGuidance(), 10, it))
        }
    }

    @Test fun stableWindowFiresOnceAndNeverOnFirstFrameOrMillisecond() {
        val gate = SelfieGuidance()
        assertEquals(SelfieHint.HOLD, sample(gate, 0))
        assertEquals(SelfieHint.HOLD, sample(gate, 1))
        for (time in listOf(180L, 360L, 540L, 720L, 899L)) assertEquals(SelfieHint.HOLD, sample(gate, time))
        assertEquals(SelfieHint.CAPTURING, sample(gate, 900))
        repeat(10) { assertNotEquals(SelfieHint.CAPTURING, sample(gate, 901L + it)) }
        gate.reset()
        assertNotEquals(SelfieHint.CAPTURING, sample(gate, 2_000))
    }

    @Test fun permittedSizeOutsideGuideBoundsNeverStartsOrCompletesStableWindow() {
        listOf(
            centered.copy(centerX = .4f, width = .96f),
            centered.copy(centerX = .6f, width = .96f),
            centered.copy(centerY = .4f, height = .98f),
            centered.copy(centerY = .6f, height = .98f),
        ).forEach { outside ->
            val gate = SelfieGuidance()
            for (time in 0L..1_800L step 180) {
                assertEquals(SelfieHint.CENTER, sample(gate, time, outside))
            }
            for (time in 1_980L..2_700L step 180) {
                assertEquals(SelfieHint.HOLD, sample(gate, time))
            }
            assertEquals(SelfieHint.CAPTURING, sample(gate, 2_880))
            assertNotEquals(SelfieHint.CAPTURING, sample(gate, 3_060))
        }
    }

    @Test fun overflowingFaceResetsAnExistingStableWindowWithoutChangingOversizeHint() {
        val gate = SelfieGuidance()
        for (time in 0L..720L step 180) assertEquals(SelfieHint.HOLD, sample(gate, time))
        assertEquals(SelfieHint.CENTER, sample(gate, 900, centered.copy(centerX = .6f, width = .96f)))
        for (time in 1_080L..1_800L step 180) assertEquals(SelfieHint.HOLD, sample(gate, time))
        assertEquals(SelfieHint.CAPTURING, sample(gate, 1_980))
        assertEquals(SelfieHint.FARTHER, sample(SelfieGuidance(), 0,
            centered.copy(centerX = .6f, width = .961f)))
        assertEquals(SelfieHint.FARTHER, sample(SelfieGuidance(), 0,
            centered.copy(centerY = .6f, height = .981f)))
    }

    @Test fun absentMultipleStaleInactiveOrInvalidFrameBreaksContinuity() {
        val invalidInputs: List<(SelfieGuidance) -> Unit> = listOf(
            { sample(it, 800, count = 0) }, { sample(it, 800, count = 2) },
            { sample(it, 800, active = false) }, { sample(it, 800, age = 301) },
            { sample(it, 800, frame = 1) }, { sample(it, 700) },
            { sample(it, 800, position = null) },
        )
        invalidInputs.forEach { invalidate ->
            val gate = SelfieGuidance()
            for (time in 0L..720L step 180) sample(gate, time)
            invalidate(gate)
            assertEquals(SelfieHint.HOLD, sample(gate, 900))
            for (time in 1_080L..1_620L step 180) assertEquals(SelfieHint.HOLD, sample(gate, time))
            assertEquals(SelfieHint.CAPTURING, sample(gate, 1_800))
        }
    }

    @Test fun gapMovementAndPoseDriftRestartTheFullWindow() {
        listOf(centered.copy(centerX = .55f), centered.copy(width = .87f),
            centered.copy(pitch = 6f)).forEach { moved ->
            val gate = SelfieGuidance()
            for (time in 0L..720L step 180) sample(gate, time)
            assertEquals(SelfieHint.HOLD, sample(gate, 900, moved))
            for (time in 1_080L..1_620L step 180) assertEquals(SelfieHint.HOLD, sample(gate, time, moved))
            assertEquals(SelfieHint.CAPTURING, sample(gate, 1_800, moved))
        }
        val gap = SelfieGuidance()
        sample(gap, 0)
        assertEquals(SelfieHint.HOLD, sample(gap, 900))
        val reset = SelfieGuidance()
        for (time in 0L..720L step 180) sample(reset, time)
        reset.reset()
        assertEquals(SelfieHint.HOLD, sample(reset, 900))
    }

    @Test fun futureReceiptAndClockReversalNeverCapture() {
        val gate = SelfieGuidance()
        assertEquals(SelfieHint.PREPARING, gate.update(1, centered, 100, 99, 1, true))
        assertEquals(SelfieHint.PREPARING, gate.update(1, centered, -1, 100, 2, true))
        for (time in 200L..920L step 180) sample(gate, time)
        assertEquals(SelfieHint.PREPARING, sample(gate, 500, frame = 2_000))
        assertEquals(SelfieHint.HOLD, sample(gate, 1_100, frame = 2_001))
    }

    @Test fun simultaneousCallbacksCanEmitOnlyOneCaptureDecision() {
        val gate = SelfieGuidance()
        for (time in 0L..720L step 180) sample(gate, time)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val results = executor.invokeAll(List(16) {
                java.util.concurrent.Callable { sample(gate, 900) }
            }).map { it.get() }
            assertEquals(1, results.count { it == SelfieHint.CAPTURING })
        } finally { executor.shutdown() }
    }
}
