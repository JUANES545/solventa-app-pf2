package co.solventa.mobile.feature.policy

import org.junit.Assert.*
import org.junit.Test

class PolicyDocumentIntegrityTest {
    @Test fun verifiesOriginalBytesWithoutNormalizingOrAcceptingMissingHash() {
        val bytes = "abc".toByteArray()
        val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertTrue(policyDocumentMatches(bytes, expected))
        assertFalse(policyDocumentMatches(bytes + 10.toByte(), expected))
        assertFalse(policyDocumentMatches(bytes, null))
        assertFalse(policyDocumentMatches(bytes, expected.drop(1)))
    }
}
