package co.solventa.mobile.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessBackgroundMotionTest {
    @Test fun motionRunsOnlyWhenAllSafetyConditionsAllowIt() {
        for (resumed in listOf(false, true)) {
                for (editing in listOf(false, true)) {
                    for (error in listOf(false, true)) {
                        for (submitting in listOf(false, true)) {
                            val status = accessMotionStatus(resumed, 1f, editing, error, submitting)
                            assertEquals(
                                resumed && !editing && !error && !submitting,
                                status == AccessMotionStatus.RUNNING,
                            )
                    }
                }
            }
        }
    }

    @Test fun disabledOrInvalidSystemScaleUsesStaticFallback() {
        for (scale in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(AccessMotionStatus.SYSTEM_STATIC,
                accessMotionStatus(true, scale, false, false, false))
        }
    }

    @Test fun policyKeepsAutomaticPauses() {
        assertEquals(AccessMotionStatus.NOT_VISIBLE,
            accessMotionStatus(false, 1f, false, false, false))
        assertEquals(AccessMotionStatus.INPUT_PAUSED,
            accessMotionStatus(true, 1f, true, false, false))
        assertEquals(AccessMotionStatus.ERROR_PAUSED,
            accessMotionStatus(true, 1f, true, true, false))
        assertEquals(AccessMotionStatus.SENDING_PAUSED,
            accessMotionStatus(true, 1f, true, true, true))
        assertEquals(AccessMotionStatus.MODAL_PAUSED,
            accessMotionStatus(true, 1f, false, false, false, modalOpen = true))
    }

    @Test fun phaseAdvancesDeterministicallyOverTwentySecondsWithoutResettingOnResume() {
        assertEquals(.25f, advanceAccessPhase(0f, 5f, 1f), .00001f)
        assertEquals(.5f, advanceAccessPhase(.25f, 5f, 1f), .00001f)
        assertEquals(.1f, advanceAccessPhase(.9f, 4f, 1f), .00001f)
        assertEquals(.25f, advanceAccessPhase(0f, 10f, 2f), .00001f)
        for (scale in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(.31f, advanceAccessPhase(.31f, 5f, scale), 0f)
        }
        assertEquals(.31f, advanceAccessPhase(.31f, 0f, 1f), 0f)
    }
}
