package co.solventa.mobile.feature.travel

import java.time.LocalDate

internal val quoteProducts = setOf("automotive", "travel", "device-protection", "payment-protection", "parametric", "parametric-weather")
internal val flightCovers = listOf("delay-cancellation", "missed-connection", "comprehensive")
internal val weatherEvents = listOf("heavy-rain", "drought")

internal fun validProductFields(product: String, fields: Map<String,String>, today: LocalDate): Boolean {
    fun text(key: String, max: Int) = fields[key]?.let { it.isNotBlank() && it.codePointCount(0,it.length) <= max } == true
    fun amount(key: String, max: Long) = fields[key]?.let {
        it.length in 1..16 && it.all { c -> c in '0'..'9' } && it.toLong() in 1..max
    } == true
    fun date(key: String) = fields[key]?.let { try { LocalDate.parse(it) } catch (_: java.time.format.DateTimeParseException) { null } }
    return when(product) {
        "device-protection" -> text("deviceType",40) && text("brandModel",80) && amount("purchaseValue",9_999_999_999_999_999)
        "payment-protection" -> text("creditType",60) && amount("monthlyPayment",9_999_999_999_999_999)
        "parametric" -> text("airline",80) && fields["flightNumber"]?.matches(Regex("[a-zA-Z0-9]{2,10}")) == true &&
            date("flightDate")?.isAfter(today) == true && fields["coverageType"] in flightCovers
        "parametric-weather" -> {
            val start=date("startDate"); val end=date("endDate")
            text("location",120) && fields["weatherEvent"] in weatherEvents && start != null && start > today &&
                end != null && end >= start && end <= start.plusDays(365) && amount("insuredAmount",1_000_000_000)
        }
        else -> false
    }
}
