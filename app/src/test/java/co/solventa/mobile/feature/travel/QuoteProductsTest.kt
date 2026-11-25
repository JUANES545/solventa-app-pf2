package co.solventa.mobile.feature.travel

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class QuoteProductsTest {
    private val today=LocalDate.of(2026,12,31)
    @Test fun amountsArePositiveIntegersAndTextHasPerFieldLimits() {
        val device=mapOf("deviceType" to "Teléfono","brandModel" to "Serie C","purchaseValue" to "9999999999999999")
        assertTrue(validProductFields("device-protection",device,today))
        for(bad in listOf("0","1.5","10000000000000000")) assertFalse(validProductFields("device-protection",device+("purchaseValue" to bad),today))
        val credit=mapOf("creditType" to "a".repeat(60),"monthlyPayment" to "1")
        assertTrue(validProductFields("payment-protection",credit,today))
        assertFalse(validProductFields("payment-protection",credit+("creditType" to "a".repeat(61)),today))
    }
    @Test fun flightAndWeatherEnforceFutureDatesAndEnumeratedOptions() {
        val start=today.plusDays(1)
        val flight=mapOf("airline" to "Prueba","flightNumber" to "QA12","flightDate" to start.toString(),"coverageType" to "comprehensive")
        assertTrue(validProductFields("parametric",flight,today))
        assertFalse(validProductFields("parametric",flight+("flightDate" to today.toString()),today))
        assertFalse(validProductFields("parametric",flight+("flightNumber" to "A-12"),today))
        val weather=mapOf("location" to "Zona A","weatherEvent" to "drought","startDate" to start.toString(),"endDate" to start.plusDays(365).toString(),"insuredAmount" to "1000000000")
        assertTrue(validProductFields("parametric-weather",weather,today))
        for(pair in listOf("endDate" to start.plusDays(366).toString(),"insuredAmount" to "1000000001","weatherEvent" to "wind"))
            assertFalse(validProductFields("parametric-weather",weather+pair,today))
    }
}
