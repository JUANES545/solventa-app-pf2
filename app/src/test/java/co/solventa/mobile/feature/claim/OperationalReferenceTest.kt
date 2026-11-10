package co.solventa.mobile.feature.claim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OperationalReferenceTest {
    @Test fun singleAndDifferentReferencesUseCompactPrefixAndSuffix() {
        val first = "11111111-1111-4111-8111-111111111111"
        val second = "22222222-2222-4222-8222-222222222222"
        assertEquals("1111…1111", operationalReference(first, listOf(first)))
        assertEquals("1111…1111", operationalReference(first, listOf(first, second)))
        assertEquals("2222…2222", operationalReference(second, listOf(first, second)))
    }

    @Test fun suffixDistinguishesIdsThatOnlyDifferAtTheEnd() {
        val first = "11111111-1111-4111-8111-111111111111"
        val second = "11111111-1111-4111-8111-111111111112"
        val peers = listOf(first, second)
        assertNotEquals(operationalReference(first, peers), operationalReference(second, peers))
        assertEquals("1111…1111", operationalReference(first, peers))
        assertEquals("1111…1112", operationalReference(second, peers))
        assertEquals("local", operationalReference("local", listOf("local", "local-extra")))
    }

    @Test fun identicalDuplicatesDoNotForceLongReferencesAndMiddleDifferencesStayDistinct() {
        val first = "11111111-1111-4111-8111-111111111111"
        val second = "11111111-2111-4111-8111-111111111111"
        assertEquals("1111…1111", operationalReference(first, listOf(first, first)))
        val peers = listOf(first, second, first)
        assertNotEquals(operationalReference(first, peers), operationalReference(second, peers))
    }
}
