package co.solventa.mobile.feature.claim

internal fun operationalReference(reference: String, peers: List<String> = emptyList()): String {
    val others = peers.distinct().filter { it != reference }
    for (length in 4 until reference.length / 2) {
        val short = reference.take(length) + "…" + reference.takeLast(length)
        if (others.none { it.take(length) + "…" + it.takeLast(length) == short }) return short
    }
    return reference
}
