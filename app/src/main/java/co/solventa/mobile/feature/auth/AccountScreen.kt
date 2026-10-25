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
fun AccountScreen(user: AuthUser, loading: Boolean, onLogout: () -> Unit, biometricContent: (@Composable () -> Unit)? = null) {
    var signingOut by remember { mutableStateOf(false) }
    SolventaPage(header = {
            SolventaBrandHeader()
            Text(stringResource(R.string.home_title), Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.session_greeting, user.name), modifier = Modifier.fillMaxWidth().semantics { heading() },
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    }, actions = {
        Button(onClick = { signingOut = true; onLogout() }, enabled = !loading,
            modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight), shape = SolventaLayout.controlShape) {
            if (loading) {
                CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(if (loading) {
                if (signingOut) R.string.signing_out else R.string.session_checking
            } else R.string.sign_out))
        }
    }) {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.account_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.name), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(user.name, style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.email), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(user.email, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Text(stringResource(R.string.account_security_title), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.account_security_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
            biometricContent?.invoke()
    }
}
