package co.solventa.mobile.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaLayout
import co.solventa.mobile.core.ui.SolventaBrandHeader
import co.solventa.mobile.core.ui.SolventaPage
import co.solventa.mobile.core.ui.SolventaActionRow
import co.solventa.mobile.feature.auth.AuthUser
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign

internal enum class HomeDestination(val label: Int, val icon: Int) {
    HOME(R.string.home_title, R.drawable.ic_nav_home),
    NOTIFICATIONS(R.string.home_notifications, R.drawable.ic_nav_notifications),
    ACCOUNT(R.string.account_title, R.drawable.ic_nav_account),
}

@Composable
internal fun HomeNavigation(
    selected: HomeDestination,
    enabled: Boolean,
    notificationsAvailable: Boolean,
    onSelect: (HomeDestination) -> Unit,
    content: @Composable () -> Unit,
) {
    val destinations = HomeDestination.entries.filter {
        it != HomeDestination.NOTIFICATIONS || notificationsAvailable
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 600.dp) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                    destinations.forEach { destination ->
                        val label = stringResource(destination.label)
                        val visibleLabel = if (destination == HomeDestination.NOTIFICATIONS &&
                            LocalDensity.current.fontScale >= 1.5f
                        ) stringResource(R.string.notification_entry) else label
                        NavigationRailItem(
                            selected = selected == destination,
                            onClick = { if (selected != destination) onSelect(destination) },
                            enabled = enabled,
                            icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                            label = { Text(visibleLabel, textAlign = TextAlign.Center) },
                            modifier = Modifier.semantics { contentDescription = label },
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) { content() }
            }
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    NavigationBar(
                        modifier = Modifier.heightIn(min = (80 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp),
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) {
                        destinations.forEach { destination ->
                            val label = stringResource(destination.label)
                            val visibleLabel = if (destination == HomeDestination.NOTIFICATIONS &&
                                LocalDensity.current.fontScale >= 1.5f
                            ) stringResource(R.string.notification_entry) else label
                            NavigationBarItem(
                                selected = selected == destination,
                                onClick = { if (selected != destination) onSelect(destination) },
                                enabled = enabled,
                                icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                                label = { Text(visibleLabel, textAlign = TextAlign.Center) },
                                modifier = Modifier.semantics { contentDescription = label },
                            )
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) { content() }
            }
        }
    }
}

@Composable
internal fun HomeScreen(
    user: AuthUser,
    loading: Boolean,
    offline: Boolean,
    onClaim: (() -> Unit)?,
    onClaimStatus: (() -> Unit)?,
    onEvidence: (() -> Unit)?,
    onPolicies: (() -> Unit)? = null,
    onTravel: (() -> Unit)? = null,
) {
    SolventaPage(header = {
        SolventaBrandHeader()
    }) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.session_greeting, user.name), Modifier.weight(1f).semantics { heading() },
                        style = MaterialTheme.typography.headlineSmall)
                    val motion = co.solventa.mobile.core.ui.LocalAccessBackgroundState.current
                    val orbit = if (motion?.resumed == true && motion.durationScale > 0f)
                        rememberInfiniteTransition(label="Órbita").animateFloat(0f, 1f,
                            infiniteRepeatable(tween(4200, easing=LinearEasing)),label="Punto") else null
                    val accent = MaterialTheme.colorScheme.primary
                    val orbitSize = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * .102f)
                        .coerceIn(64f, 88f).dp
                    Box(Modifier.size(orbitSize), contentAlignment = Alignment.Center) {
                        co.solventa.mobile.core.ui.SolventaBrandMark(orbitSize * .84f)
                        Canvas(Modifier.fillMaxSize()) {
                            drawOval(accent.copy(alpha=.35f), topLeft=androidx.compose.ui.geometry.Offset(1.dp.toPx(), size.height*.22f),
                                size=androidx.compose.ui.geometry.Size(size.width-2.dp.toPx(), size.height*.56f), style=Stroke(1.dp.toPx()))
                            val angle = ((orbit?.value ?: 0f) * 2 * Math.PI + Math.PI * .32)
                            drawCircle(accent, 3.dp.toPx(), androidx.compose.ui.geometry.Offset(center.x+(Math.cos(angle)*size.width*.48).toFloat(),
                                center.y+(Math.sin(angle)*size.height*.28).toFloat()))
                        }
                    }
                }
                Text(stringResource(R.string.home_reassurance_body), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (offline) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium) {
                Text(stringResource(R.string.claim_local_session), Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        if (onClaim != null) {
            Button(onClick = onClaim, enabled = !loading,
                modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight),
                shape = SolventaLayout.controlShape) {
                Text(stringResource(R.string.claim_title), textAlign = TextAlign.Center)
            }
        }
        onTravel?.let { OutlinedButton(onClick=it, enabled=!loading, modifier=Modifier.fillMaxWidth().heightIn(min=SolventaLayout.controlHeight),
            shape=SolventaLayout.controlShape) { Text(stringResource(R.string.catalog_quote)) } }
        if (onClaimStatus != null || onEvidence != null || onPolicies != null) {
            Text(stringResource(R.string.home_management),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp).semantics { heading() })
            onPolicies?.let {
                SolventaActionRow(stringResource(R.string.policy_title), onClick = it, enabled = !loading, icon=R.drawable.ic_policy_wallet)
            }
            onClaimStatus?.let {
                SolventaActionRow(stringResource(R.string.home_my_claims), onClick = it, enabled = !loading,
                    icon = R.drawable.ic_claim_status)
            }
            onEvidence?.takeIf { onClaimStatus == null }?.let {
                SolventaActionRow(stringResource(R.string.home_evidence_title),
                    supportingText = stringResource(R.string.home_evidence_description), onClick = it, enabled = !loading,
                    icon = R.drawable.ic_evidence)
            }

        }
        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.session_checking))
            }
        }
    }
}
