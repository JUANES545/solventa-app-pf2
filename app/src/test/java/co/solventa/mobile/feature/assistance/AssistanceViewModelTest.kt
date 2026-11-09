package co.solventa.mobile.feature.assistance

import android.app.Application
import android.os.Looper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

internal class FakeAssistanceGateway : AssistanceGateway {
    var nearby = listOf(assistanceProvider)
    var requestError: AssistanceError? = null
    val operations = mutableListOf<String>()

    override suspend fun providers(
        actor: String,
        location: AssistanceLocation,
        radiusKm: Int,
    ) = nearby

    override suspend fun request(
        actor: String,
        claimId: String,
        provider: AssistanceProvider,
        location: AssistanceLocation,
        operationId: String,
    ): AssistanceReceipt {
        operations += operationId
        requestError?.let { throw AssistanceProblem(it) }
        return AssistanceReceipt(
            UUID.randomUUID().toString(),
            "registrada",
            provider.id,
            provider.serviceType,
            Instant.parse("2026-03-16T12:31:00Z"),
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AssistanceViewModelTest {
    private fun await(model: AssistanceViewModel) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (model.state.value.busy && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertFalse("La operación no terminó", model.state.value.busy)
    }

    @Test fun permissionDenialAndUnavailableGpsOfferValidatedManualFallback() {
        val gateway = FakeAssistanceGateway()
        val model = AssistanceViewModel(gateway, FixedAssistanceLocator())
        model.show("actor-a", UUID.randomUUID().toString())
        model.permissionDenied()
        assertEquals(AssistanceStep.MANUAL, model.state.value.step)
        assertEquals(AssistanceError.PERMISSION, model.state.value.error)
        model.manualInput("91", "-74")
        model.useManualLocation()
        assertEquals(AssistanceError.INVALID_LOCATION, model.state.value.error)
        model.manualInput("4.60971", "-74.08175")
        model.useManualLocation()
        await(model)
        assertEquals(AssistanceStep.PROVIDERS, model.state.value.step)
        assertEquals(AssistanceLocationOrigin.MANUAL, model.state.value.location?.origin)

        model.close()
        model.show("actor-a", UUID.randomUUID().toString())
        model.locate()
        await(model)
        assertEquals(AssistanceStep.MANUAL, model.state.value.step)
        assertEquals(AssistanceError.LOCATION_UNAVAILABLE, model.state.value.error)
    }

    @Test fun emptyProvidersAndBackendErrorsStayExplicitAndRetryable() {
        val gateway = FakeAssistanceGateway().apply { nearby = emptyList() }
        val model = AssistanceViewModel(gateway, FixedAssistanceLocator(assistanceLocation))
        model.show("actor-a", UUID.randomUUID().toString())
        model.locate()
        await(model)
        assertEquals(AssistanceStep.PROVIDERS, model.state.value.step)
        assertEquals(AssistanceError.NO_PROVIDER, model.state.value.error)
        assertTrue(model.state.value.providers.isEmpty())
        gateway.nearby = listOf(assistanceProvider)
        model.retryProviders()
        await(model)
        assertEquals(listOf(assistanceProvider), model.state.value.providers)
        assertNull(model.state.value.error)
    }

    @Test fun retryUsesTheSameOperationAndSuccessOnlyAppearsAfterConfirmation() {
        val gateway = FakeAssistanceGateway()
        val model = AssistanceViewModel(gateway, FixedAssistanceLocator(assistanceLocation))
        val claim = UUID.randomUUID().toString()
        model.show("actor-a", claim)
        model.locate()
        await(model)
        model.select(assistanceProvider)
        assertEquals(AssistanceStep.CONFIRMATION, model.state.value.step)
        assertNull(model.state.value.receipt)
        gateway.requestError = AssistanceError.NETWORK
        model.submit()
        await(model)
        assertEquals(AssistanceStep.CONFIRMATION, model.state.value.step)
        assertEquals(AssistanceError.NETWORK, model.state.value.error)
        assertNull(model.state.value.receipt)
        val firstOperation = model.state.value.operationId
        assertNotNull(firstOperation)
        gateway.requestError = null
        model.submit()
        await(model)
        assertEquals(listOf(firstOperation, firstOperation), gateway.operations)
        assertEquals(AssistanceStep.SUCCESS, model.state.value.step)
        assertNotNull(model.state.value.receipt)

        model.close()
        model.show("actor-a", claim)
        model.locate()
        await(model)
        model.select(assistanceProvider)
        model.submit()
        await(model)
        assertNotEquals(firstOperation, gateway.operations.last())
        model.bindActor("actor-b")
        assertFalse(model.state.value.open)
        assertNull(model.state.value.receipt)
    }
}
