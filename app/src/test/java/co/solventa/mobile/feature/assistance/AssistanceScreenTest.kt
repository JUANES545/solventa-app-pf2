package co.solventa.mobile.feature.assistance

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AssistanceScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun receiptIsShownOnlyAfterBackendConfirmation() {
        val model = AssistanceViewModel(
            FakeAssistanceGateway(),
            FixedAssistanceLocator(assistanceLocation),
        )
        compose.runOnUiThread {
            model.show("actor-a", UUID.randomUUID().toString())
            model.locate()
        }
        compose.waitUntil(10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            !model.state.value.busy
        }
        compose.runOnUiThread { model.select(assistanceProvider) }
        compose.setContent { AssistanceScreen(model) }
        compose.onNodeWithTag("assistance-receipt-id").assertDoesNotExist()
        compose.onNodeWithTag("assistance-submit").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            model.state.value.receipt != null
        }
        compose.onNodeWithTag("assistance-receipt-id").assertIsDisplayed()
        compose.onNodeWithTag("assistance-receipt-status").assertIsDisplayed()
    }
}
