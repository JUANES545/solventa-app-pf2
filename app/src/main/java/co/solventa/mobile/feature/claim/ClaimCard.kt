package co.solventa.mobile.feature.claim

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import co.solventa.mobile.R
import androidx.compose.ui.unit.dp
import co.solventa.mobile.core.ui.SolventaRefreshing
import co.solventa.mobile.feature.evidence.EvidenceClaim
import co.solventa.mobile.feature.evidence.EvidenceScope
import co.solventa.mobile.feature.evidence.EvidenceThumbnail
import java.time.Instant

@Composable
internal fun claimDisplayType(type: String) =
    if (type == "lluvia_acumulada") stringResource(R.string.claim_accumulated_rain) else type

internal fun claimIllustration(type: String): Int {
    val label = type.trim().lowercase(java.util.Locale.ROOT)
    return when (label) {
        "choque", "choque sintetico", "choque sintético", "choque vehicular", "colisión vehicular",
        "colisión de vehículos", "choque de auto", "accidente de tránsito" -> co.solventa.mobile.R.drawable.wallet_auto_collision
        "auto", "automotive", "robo de vehículo", "daño vehicular" -> co.solventa.mobile.R.drawable.wallet_auto
        else -> co.solventa.mobile.R.drawable.ic_claim_status
    }
}

@Composable
internal fun ClaimCard(claim: EvidenceClaim, actor: String?, busy: Boolean, peers: List<EvidenceClaim>, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("claim-${claim.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.large) {
        SolventaRefreshing(busy) {
            Column {
                EvidenceThumbnail(actor?.let { EvidenceScope(it, claim.id) }, illustration = claimIllustration(claim.type))
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(claimDisplayType(claim.type), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    Text(operationalDate(Instant.parse(claim.date)), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val twins = peers.filter { it.type == claim.type && it.date == claim.date }
                    if (twins.size > 1) Text(stringResource(R.string.evidence_claim_reference,
                        operationalReference(claim.id, twins.map { it.id })),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
