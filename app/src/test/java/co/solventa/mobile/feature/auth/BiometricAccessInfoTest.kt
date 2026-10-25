package co.solventa.mobile.feature.auth

import androidx.biometric.BiometricManager
import co.solventa.mobile.R
import org.junit.Assert.assertEquals
import org.junit.Test

class BiometricAccessInfoTest {
    @Test fun capabilityResultsHaveSpecificMessagesAndUnknownResultsNeverClaimReadiness() {
        val messages = mapOf(
            BiometricManager.BIOMETRIC_SUCCESS to R.string.biometric_device_ready,
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED to R.string.biometric_not_enrolled,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE to R.string.biometric_no_hardware,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE to R.string.biometric_hardware_unavailable,
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED to R.string.biometric_security_update,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED to R.string.biometric_unsupported,
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN to R.string.biometric_status_unknown,
            999 to R.string.biometric_status_unknown,
        )
        messages.forEach { (status, message) -> assertEquals(message, biometricCapabilityMessage(status)) }
    }
}
