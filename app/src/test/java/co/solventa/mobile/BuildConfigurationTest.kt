package co.solventa.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildConfigurationTest {
    @Test fun sampleDescriptionIsEnabledOnlyForIsolatedMockBuild() {
        assertEquals(BuildConfig.BUILD_TYPE == "mock", BuildConfig.CLAIM_SAMPLE_ENABLED)
    }
    @Test fun pastInitialClaimDateIsLimitedToTheIsolatedMockBuild() {
        assertEquals(if (BuildConfig.BUILD_TYPE == "mock") "2026-11-03" else "", BuildConfig.CLAIM_INITIAL_DATE)
    }
    @Test fun demoLocationIsEnabledOnlyForIsolatedMockBuild() {
        assertEquals(BuildConfig.BUILD_TYPE == "mock", BuildConfig.DEMO_LOCATION)
    }
    @Test
    fun captureProtectionIsKeptForPublishedBuilds() {
        assertEquals(BuildConfig.BUILD_TYPE != "release", BuildConfig.SCREEN_CAPTURE_ALLOWED)
    }

    @Test
    fun applicationIdDoesNotReplaceThePreviousPrototype() {
        if (BuildConfig.BUILD_TYPE == "mock") {
            assertEquals("co.solventa.mobile.pf2.mock", BuildConfig.APPLICATION_ID)
            assertEquals("http://127.0.0.1:18725/movil/v1", BuildConfig.AUTH_BASE_URL)
            assertEquals("urn:solventa:mock", BuildConfig.AUTH_ISSUER)
            assertTrue(BuildConfig.DEBUG)
        } else if (BuildConfig.BUILD_TYPE == "evidenceTest") {
            assertEquals("co.solventa.mobile.pf2.evidencetest", BuildConfig.APPLICATION_ID)
            assertEquals("http://127.0.0.1:18719/movil/v1", BuildConfig.AUTH_BASE_URL)
            assertEquals("urn:solventa:claims-local", BuildConfig.AUTH_ISSUER)
            assertTrue(BuildConfig.DEBUG)
        } else if (BuildConfig.BUILD_TYPE == "biometricTest") {
            assertEquals("co.solventa.mobile.pf2.biometrictest", BuildConfig.APPLICATION_ID)
            assertEquals("http://127.0.0.1:18717/movil/v1", BuildConfig.AUTH_BASE_URL)
            assertEquals("https://identidad.solventa.test", BuildConfig.AUTH_ISSUER)
            assertTrue(BuildConfig.BIOMETRIC_LOGIN_ENABLED)
            assertTrue(BuildConfig.PHOTO_REVIEW_ENABLED)
            assertTrue(BuildConfig.DEBUG)
        } else {
            assertEquals("co.solventa.mobile.pf2", BuildConfig.APPLICATION_ID)
            assertEquals("https://65qgzhody9.execute-api.us-east-2.amazonaws.com/movil/v1", BuildConfig.AUTH_BASE_URL)
            assertEquals("https://65qgzhody9.execute-api.us-east-2.amazonaws.com", BuildConfig.AUTH_ISSUER)
            assertTrue(BuildConfig.BIOMETRIC_LOGIN_ENABLED)
            assertTrue(BuildConfig.PHOTO_REVIEW_ENABLED)
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
