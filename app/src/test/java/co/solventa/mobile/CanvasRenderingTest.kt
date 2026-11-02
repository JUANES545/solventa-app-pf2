package co.solventa.mobile

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import co.solventa.mobile.core.designsystem.SolventaTheme
import co.solventa.mobile.core.ui.SolventaBrandMark
import co.solventa.mobile.feature.photo.DocumentFrameOverlay
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CanvasRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun rendered() = compose.runOnIdle {
        val view = compose.activity.window.decorView
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            .asImageBitmap().toPixelMap()
    }

    @Test fun brandDrawsTheShieldAndLetterInBothExplicitThemes() {
        val dark = mutableStateOf(false)
        compose.setContent {
            SolventaTheme(darkTheme = dark.value) {
                Box(Modifier.testTag("brand")) { SolventaBrandMark(96.dp) }
            }
        }
        fun colors(): Set<Int> {
            val image = rendered()
            return (0 until image.width).flatMap { x -> (0 until image.height).map { y -> image[x, y].hashCode() } }.toSet()
        }
        val light = colors()
        assertTrue(light.size > 2)
        compose.runOnIdle { dark.value = true }
        val night = colors()
        assertTrue(night.size > 2)
        assertNotEquals(light, night)
    }

    @Test fun documentGuideDrawsOnlyInsideValidVisibleImageBounds() {
        val bounds = mutableStateOf<Rect?>(null)
        compose.setContent {
            SolventaTheme {
                DocumentFrameOverlay(bounds.value, Modifier.size(200.dp).testTag("guide"))
            }
        }
        fun pixels() = rendered().let { pixels ->
            (0 until pixels.width).flatMap { x -> (0 until pixels.height).map { y -> pixels[x, y].hashCode() } }
        }
        val empty = pixels()
        for (invalid in listOf(Rect.Zero, Rect(0f, 0f, 1f, 1f), Rect(0f, 0f, 30f, 1f))) {
            compose.runOnIdle { bounds.value = invalid }
            assertEquals(empty, pixels())
        }
        compose.runOnIdle { bounds.value = Rect(0f, 0f, 180f, 180f) }
        assertNotEquals(empty, pixels())
    }
}
