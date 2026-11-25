package co.solventa.mobile.feature.auth

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class RegistrationProfileTest {
    @Test fun validatesDocumentByTypeAndDoesNotRequireAnAdultOrProfilingConsent() {
        val today = LocalDate.of(2026, 11, 25)
        val profile = RegistrationProfile("passport", "TEST9", "+573005550123", today.minusDays(1), false)
        assertTrue(profile.valid(today))
        assertFalse(profile.copy(documentType = "cc").valid(today))
        assertTrue(profile.copy(documentType = "ppt", number = "1234567").valid(today))
        assertFalse(profile.copy(documentType = "ppt", number = "123456").valid(today))
        assertFalse(profile.copy(birth = today).valid(today))
        assertFalse(profile.copy(phone = "3005550123").valid(today))
    }
}
