package co.solventa.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildConfigurationTest {
    @Test
    fun applicationIdDoesNotReplaceThePreviousPrototype() {
        assertEquals("co.solventa.mobile.pf2", BuildConfig.APPLICATION_ID)
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
