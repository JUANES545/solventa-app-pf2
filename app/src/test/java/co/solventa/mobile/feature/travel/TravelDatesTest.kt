package co.solventa.mobile.feature.travel

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TravelDatesTest {
    @Test fun acceptsTodayAndSameDayReturnWithoutAllowingPastOrReversedDates() {
        val today = LocalDate.of(2026, 12, 31)
        val tomorrow = today.plusDays(1)
        assertTrue(travelDatesAllowed(today, today, today))
        assertTrue(travelDatesAllowed(today, tomorrow, tomorrow))
        assertFalse(travelDatesAllowed(today, tomorrow, today))
        assertFalse(travelDatesAllowed(today, today.minusDays(1), today))
        assertFalse(travelDatesAllowed(today, today, null))
    }
}
