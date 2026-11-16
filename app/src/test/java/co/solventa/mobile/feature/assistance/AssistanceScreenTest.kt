package co.solventa.mobile.feature.assistance

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import co.solventa.mobile.R
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

    @Test fun receiptAcknowledgesOnlyTheRegisteredRequestAfterBackendResponse() {
        val catalogWarning = "El catálogo puede incluir datos de prueba. Registrar una solicitud no confirma atención ni despacho."
        val model = AssistanceViewModel(
            FakeAssistanceGateway(),
            FixedAssistanceLocator(assistanceLocation),
        )
        compose.runOnUiThread {
            model.show("actor-a", UUID.randomUUID().toString())
        }
        compose.setContent { AssistanceScreen(model) }
        compose.onNodeWithText(catalogWarning).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Confirma dónde necesitas ayuda. Usaremos la ubicación solo para consultar prestadores y registrar esta solicitud.")
            .performScrollTo().assertIsDisplayed()
        compose.runOnUiThread { model.locate() }
        compose.waitUntil(10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            !model.state.value.busy
        }
        compose.onNodeWithText(catalogWarning).performScrollTo().assertIsDisplayed()
        compose.runOnUiThread { model.select(assistanceProvider) }
        compose.onNodeWithText(catalogWarning).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Confirma la solicitud").assertIsDisplayed()
        compose.onNodeWithTag("assistance-receipt-id").assertDoesNotExist()
        compose.onNodeWithTag("assistance-submit").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            model.state.value.receipt != null
        }
        compose.onNodeWithText("Solicitud registrada").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("El registro no confirma que se haya avisado a un prestador.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Asistencia confirmada").assertDoesNotExist()
        compose.onNodeWithTag("assistance-receipt-id").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("assistance-receipt-status").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.ui_show_details))
            .performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.ui_reference, model.state.value.receipt!!.id))
            .performScrollTo().assertIsDisplayed()
    }
}
