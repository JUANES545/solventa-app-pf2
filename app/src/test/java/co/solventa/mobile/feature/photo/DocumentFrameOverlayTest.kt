package co.solventa.mobile.feature.photo

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentFrameOverlayTest {
    private fun assertBounds(expected: Rect, actual: Rect?) {
        requireNotNull(actual)
        assertEquals(expected.left, actual.left, .001f)
        assertEquals(expected.top, actual.top, .001f)
        assertEquals(expected.right, actual.right, .001f)
        assertEquals(expected.bottom, actual.bottom, .001f)
    }

    @Test fun narrowViewportKeepsTheGuideCenteredAndInsideTheImage() {
        val bounds = Rect(0f, 0f, 200f, 800f)
        assertBounds(Rect(16f, 347.5f, 184f, 452.5f), documentFrameBounds(bounds, bounds))
    }

    @Test fun tallViewportDoesNotStretchTheGuideToTheScreenHeight() {
        val bounds = Rect(0f, 0f, 400f, 1600f)
        assertBounds(Rect(32f, 695f, 368f, 905f), documentFrameBounds(bounds, bounds))
    }

    @Test fun landscapeViewportLimitsHeightInsteadOfClippingTheFrame() {
        val bounds = Rect(0f, 0f, 1000f, 200f)
        assertBounds(Rect(365.6f, 16f, 634.4f, 184f), documentFrameBounds(bounds, bounds))
    }

    @Test fun horizontalLetterboxingUsesImageBoundsNotTheFullViewport() {
        val image = Rect(0f, 150f, 400f, 450f)
        val frame = requireNotNull(documentFrameBounds(image, Rect(0f, 0f, 400f, 600f)))
        assertBounds(Rect(32f, 195f, 368f, 405f), frame)
        assertTrue(frame.top > 150f && frame.bottom < 450f)
    }

    @Test fun verticalLetterboxingPreservesTheOffsetFromTheImageOrigin() {
        val image = Rect(300f, 0f, 500f, 600f)
        val frame = requireNotNull(documentFrameBounds(image, Rect(0f, 0f, 800f, 600f)))
        assertBounds(Rect(316f, 247.5f, 484f, 352.5f), frame)
        assertTrue(frame.left > 300f && frame.right < 500f)
    }

    @Test fun clippedImageUsesOnlyItsVisibleIntersectionWithTheViewport() {
        assertBounds(
            Rect(16f, 97.5f, 184f, 202.5f),
            documentFrameBounds(Rect(-100f, -50f, 200f, 350f), Rect(0f, 0f, 400f, 300f)),
        )
    }

    @Test fun missingOrInvalidImageAreaNeverCreatesAGuide() {
        val viewport = Rect(0f, 0f, 400f, 600f)
        for (image in listOf(
            Rect.Zero,
            Rect(200f, 0f, 100f, 100f),
            Rect(0f, 100f, 100f, 50f),
            Rect(500f, 0f, 600f, 100f),
            Rect(400f, 0f, 500f, 100f),
            Rect(Float.NaN, 0f, 100f, 100f),
            Rect(0f, 0f, Float.POSITIVE_INFINITY, 100f),
        )) assertNull(documentFrameBounds(image, viewport))
        assertNull(documentFrameBounds(viewport, Rect.Zero))
        assertNull(documentFrameBounds(viewport, Rect(0f, 0f, 100f, Float.NaN)))
    }
}
