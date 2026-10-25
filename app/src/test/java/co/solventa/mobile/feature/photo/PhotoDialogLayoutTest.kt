package co.solventa.mobile.feature.photo

import android.app.Application
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import co.solventa.mobile.core.designsystem.SolventaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h914dp-280dpi")
class PhotoDialogLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var dialogView: View

    private fun show(kind: PhotoKind = PhotoKind.DOCUMENT, fontScale: Float = 1f) {
        lateinit var view: View
        var topInset = 0
        compose.activity.enableEdgeToEdge()
        compose.setContent {
            PhotoReviewDialog({}) {
                view = LocalView.current
                dialogView = view
                topInset = WindowInsets.safeDrawing.getTop(LocalDensity.current)
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    SolventaTheme {
                        PhotoReviewScreen(PhotoState(open = true, kind = kind), {}, {}, {}, onRestart = {}) {}
                    }
                }
            }
        }
        compose.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(view, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 68, 0, 0))
                .setVisible(WindowInsetsCompat.Type.statusBars(), true).build())
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("The dialog must receive the real 68px status inset", 68, topInset) }
    }

    @Test fun dialogMustHonorWindowViewportSmallerThanConfigurationScreenHeight() {
        show()
        val button = compose.onNode(hasText("Abrir cámara") and hasClickAction()).fetchSemanticsNode()
        val scroll = compose.onNode(hasScrollAction()).fetchSemanticsNode()
        val label = compose.onNodeWithText("Abrir cámara", useUnmergedTree = true).fetchSemanticsNode()
        compose.runOnIdle {
            val host = dialogView.parent as View
            host.measure(View.MeasureSpec.makeMeasureSpec(719, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1532, View.MeasureSpec.EXACTLY))
            assertEquals("Native dialog must honor the actual 1532px window viewport, not screenHeightDp",
                1532, host.measuredHeight)
            host.layout(0, 0, 719, 1532)
            assertEquals(0f, scroll.config[SemanticsProperties.VerticalScrollAxisRange].maxValue(), .5f)
            assertEquals(button.layoutInfo.height.toFloat(), button.boundsInRoot.height, 1f)
            assertEquals(label.layoutInfo.height.toFloat(), label.boundsInRoot.height, 1f)
            assertTrue(button.boundsInRoot.bottom <= scroll.boundsInRoot.bottom + 1)
            assertTrue(button.boundsInRoot.bottom <= 1532f)
        }
    }

    @Test fun documentIntroKeepsWholeFooterInsideInsetViewportWithoutScrollingWhenItFits() {
        show()
        compose.runOnIdle {
            val provider = dialogView.parent as DialogWindowProvider
            assertEquals(WindowManager.LayoutParams.MATCH_PARENT, provider.window.attributes.width)
            assertEquals(WindowManager.LayoutParams.MATCH_PARENT, provider.window.attributes.height)
            assertTrue(provider.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
        val button = compose.onNode(hasText("Abrir cámara") and hasClickAction())
        val node = button.fetchSemanticsNode()
        val scroll = compose.onNode(hasScrollAction()).fetchSemanticsNode()
        val range = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals("A fitting intro should not need scroll", 0f, range.maxValue(), .5f)
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("Control must retain >=52dp", node.layoutInfo.height >= 52f * density - 1)
        assertEquals("The full control, not a clipped fragment, must be visible",
            node.layoutInfo.height.toFloat(), node.boundsInRoot.height, 1f)
        assertTrue("Footer must be inside the actual scroll viewport",
            node.boundsInRoot.bottom <= scroll.boundsInRoot.bottom + 1)
        val text = compose.onNodeWithText("Abrir cámara", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("The label must not be clipped", text.layoutInfo.height.toFloat(), text.boundsInRoot.height, 1f)
    }

    @Test fun overflowingSelfieAtTwoHundredPercentScrollsTheCompleteFooterIntoTheWindow() {
        show(kind = PhotoKind.SELFIE, fontScale = 2f)
        compose.runOnIdle {
            val host = dialogView.parent as View
            (host as DialogWindowProvider).window.setLayout(719, 640)
            host.measure(View.MeasureSpec.makeMeasureSpec(719, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, 719, 640)
        }
        compose.waitForIdle()
        val button = compose.onNode(hasText("Abrir cámara") and hasClickAction())
        val scroll = compose.onNode(hasScrollAction()).fetchSemanticsNode()
        val range = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("Overflow must produce a positive scroll range", range.maxValue() > 0f)
        button.performScrollTo().assertIsDisplayed()
        val node = button.fetchSemanticsNode()
        assertTrue("The action must actually be reached by scrolling", range.value() > 0f)
        assertTrue("No artificial 52dp reduction", node.layoutInfo.height >=
            52 * compose.activity.resources.displayMetrics.density - 1)
        assertEquals(node.layoutInfo.height.toFloat(), node.boundsInRoot.height, 1f)
        assertTrue(node.boundsInRoot.bottom <= scroll.boundsInRoot.bottom + 1)
        assertTrue("The scroll viewport itself must fit the native window", scroll.boundsInRoot.bottom <= 640f)
        val text = compose.onNodeWithText("Abrir cámara", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(text.layoutInfo.height.toFloat(), text.boundsInRoot.height, 1f)
    }
}
