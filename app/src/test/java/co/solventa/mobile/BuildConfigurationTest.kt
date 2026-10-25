package co.solventa.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildConfigurationTest {
    @Test
    fun applicationIdDoesNotReplaceThePreviousPrototype() {
        if (BuildConfig.BUILD_TYPE == "biometricTest") {
            assertEquals("co.solventa.mobile.pf2.biometrictest", BuildConfig.APPLICATION_ID)
            assertEquals("http://127.0.0.1:18717/movil/v1", BuildConfig.AUTH_BASE_URL)
            assertEquals("https://identidad.solventa.test", BuildConfig.AUTH_ISSUER)
            assertTrue(BuildConfig.BIOMETRIC_LOGIN_ENABLED)
            assertTrue(BuildConfig.PHOTO_REVIEW_ENABLED)
            assertTrue(BuildConfig.DEBUG)
        } else {
            assertEquals("co.solventa.mobile.pf2", BuildConfig.APPLICATION_ID)
            assertTrue(BuildConfig.AUTH_BASE_URL.startsWith("https://"))
            assertEquals("", BuildConfig.AUTH_ISSUER)
            assertFalse(BuildConfig.BIOMETRIC_LOGIN_ENABLED)
            assertFalse(BuildConfig.PHOTO_REVIEW_ENABLED)
        }
    }

    @Test
    fun versionCodeIsPositive() {
        assertTrue(BuildConfig.VERSION_CODE > 0)
    }

    @Test
    fun versionNameUsesThreeNumericParts() {
        assertTrue(BuildConfig.VERSION_NAME.matches(Regex("\\d+\\.\\d+\\.\\d+")))
    }
}
