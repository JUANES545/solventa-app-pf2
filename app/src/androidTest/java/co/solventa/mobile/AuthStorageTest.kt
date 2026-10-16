package co.solventa.mobile

import androidx.test.platform.app.InstrumentationRegistry
import co.solventa.mobile.feature.auth.AuthGrant
import co.solventa.mobile.feature.auth.AuthUser
import co.solventa.mobile.feature.auth.EncryptedAuthStore
import co.solventa.mobile.feature.auth.decodeGrant
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AuthStorageTest {
    @Test fun keystoreStorageRoundTripIsEncryptedAndClearable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EncryptedAuthStore(context)
        val grant = AuthGrant(
            AuthUser("11111111-1111-4111-8111-111111111111", "Cliente", "cliente@example.org"),
            "instrumentation-test-access", "instrumentation-test-refresh", 100_000, 200_000,
        )
        try {
            store.write(grant)
            val raw = context.getSharedPreferences("mobile-auth", 0).getString("grant", "")!!
            assertFalse(raw.contains(grant.accessToken))
            assertFalse(raw.contains(grant.refreshToken))
            assertEquals(grant, store.read())
        } finally {
            store.clear()
        }
        assertNull(store.read())
    }

    @Test fun responseDecoderRejectsUnexpectedTokenTypeAndRole() {
        val response = JSONObject()
            .put("usuario", JSONObject().put("id", "11111111-1111-4111-8111-111111111111")
                .put("nombre", "Cliente").put("correo", "cliente@example.org").put("rol", "cliente"))
            .put("tokenType", "Bearer").put("accessToken", "test-access").put("refreshToken", "test-refresh")
            .put("expiraEn", "2026-10-15T20:00:00Z").put("refreshExpiraEn", "2026-10-16T20:00:00Z")
        assertEquals("test-access", decodeGrant(response).accessToken)
        for (type in listOf("Basic", "", "bearer")) {
            response.put("tokenType", type)
            try { decodeGrant(response); fail("Expected rejection") }
            catch (_: IllegalArgumentException) { }
        }
        response.put("tokenType", "Bearer").getJSONObject("usuario").put("rol", "admin")
        try { decodeGrant(response); fail("Expected rejection") }
        catch (_: IllegalArgumentException) { }
    }
}
