package co.solventa.mobile.espresso

import androidx.test.platform.app.InstrumentationRegistry
import co.solventa.mobile.R
import co.solventa.mobile.feature.auth.AuthHttpError
import co.solventa.mobile.feature.auth.MobileAuthApi
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

@BackendE2e
@HiltAndroidTest
class AuthBackendE2eTest : AuthJourney() {
    @Test fun realRegistrationLoginRestoreRefreshAndRevocation() {
        check(InstrumentationRegistry.getArguments().getString("backendUrl") != null) {
            "E2E requires backendUrl and a disposable local backend; no production fallback."
        }
        val api = MobileAuthApi(localBackendUrl())
        val email = "espresso-${UUID.randomUUID()}@example.com"
        launch()
        register(email)
        awaitText(R.string.registration_success)
        assertNull(store.read())
        click(R.string.go_to_login)
        assertInput(R.string.email, email)
        login(email)
        awaitText(R.string.account_title)
        val original = checkNotNull(store.read())
        assertEquals(email, original.user.email)
        assertEquals(original.user, api.current(original.accessToken))

        scenario!!.close()
        // Expire only the local access deadline to exercise the real refresh endpoint without waiting.
        store.write(original.copy(expiresAt = 0))
        launch()
        awaitText(R.string.account_title)
        awaitText(R.string.sign_out)
        val rotated = checkNotNull(store.read())
        assertNotEquals(original.refreshToken, rotated.refreshToken)
        assertEquals(original.user, api.current(rotated.accessToken))
        click(R.string.sign_out)
        awaitText(R.string.sign_in)
        assertNull(store.read())
        try {
            api.refresh(rotated.refreshToken)
            fail("The real backend accepted a revoked refresh token")
        } catch (error: AuthHttpError) {
            assertEquals(401, error.status)
        }
        scenario!!.close()
        launch()
        awaitText(R.string.sign_in)
        assertInput(R.string.password, "")
    }
}
