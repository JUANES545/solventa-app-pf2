package co.solventa.mobile.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.solventa.mobile.R
import co.solventa.mobile.core.ui.SolventaPage
import co.solventa.mobile.core.ui.SolventaBrandHeader
import co.solventa.mobile.core.designsystem.SolventaLayout

@Composable
fun AccountScreen(user: AuthUser, loading: Boolean, onLogout: () -> Unit, biometricContent: (@Composable () -> Unit)? = null,
    onEvidence: (() -> Unit)? = null, onClaim: (() -> Unit)? = null, offline: Boolean = false,
    onClaimStatus: (() -> Unit)? = null, onNotifications: (() -> Unit)? = null) {
    var signingOut by remember { mutableStateOf(false) }
    SolventaPage(header = {
            SolventaBrandHeader()
            Text(stringResource(R.string.account_title), modifier = Modifier.fillMaxWidth().semantics { heading() },
                style = MaterialTheme.typography.headlineSmall)
    }, actions = {
        OutlinedButton(onClick = { signingOut = true; onLogout() }, enabled = !loading,
            modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight), shape = SolventaLayout.controlShape) {
            if (loading) {
                CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(if (loading) {
                if (signingOut) R.string.signing_out else R.string.session_checking
            } else R.string.sign_out), textAlign = TextAlign.Center)
        }
    }) {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.name), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(user.name, style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.email), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(user.email, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Text(stringResource(R.string.account_security_title), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp).semantics { heading() })
            Text(stringResource(R.string.account_security_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (offline) Text(stringResource(R.string.claim_local_session), color = MaterialTheme.colorScheme.onSurfaceVariant)
            biometricContent?.invoke()
            onNotifications?.let {
                Button(onClick = it, enabled = !loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight)) {
                    Text(stringResource(R.string.notification_entry))
                }
            }
            onClaim?.let {
                Button(onClick = it, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight)) {
                    Text(stringResource(R.string.claim_title))
                }
            }
            onEvidence?.let {
                Button(onClick = it, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight)) {
                    Text(stringResource(R.string.evidence_entry))
                }
            }
            onClaimStatus?.let {
                Button(onClick = it, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight)) {
                    Text(stringResource(R.string.claim_status_title))
                }
            }
    }
}
