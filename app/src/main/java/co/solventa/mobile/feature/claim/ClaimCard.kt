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
internal fun ClaimCard(claim: EvidenceClaim, actor: String?, busy: Boolean, peers: List<EvidenceClaim>, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("claim-${claim.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.large) {
        SolventaRefreshing(busy) {
            Column {
                EvidenceThumbnail(actor?.let { EvidenceScope(it, claim.id) })
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(claim.type, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
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
