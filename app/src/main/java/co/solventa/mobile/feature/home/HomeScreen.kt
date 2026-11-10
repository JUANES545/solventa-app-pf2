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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.clearAndSetSemantics
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
) {
    SolventaPage(header = {
        HomeBrand()
    }) {
        Text(stringResource(R.string.session_greeting, user.name),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() })
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
        if (onClaimStatus != null || onEvidence != null) {
            Text(stringResource(R.string.home_management),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp).semantics { heading() })
            onClaimStatus?.let {
                SolventaActionRow(stringResource(R.string.home_status_title),
                    supportingText = stringResource(R.string.home_status_description), onClick = it, enabled = !loading,
                    icon = R.drawable.ic_claim_status)
            }
            onEvidence?.let {
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

@Composable
private fun HomeBrand() {
    val accent = MaterialTheme.colorScheme.secondary.copy(alpha = .14f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 320.dp && LocalDensity.current.fontScale < 1.3f) {
            Canvas(Modifier.align(Alignment.CenterEnd).size(44.dp, 32.dp).clearAndSetSemantics {}) {
                val curve = Path().apply {
                    moveTo(size.width, 0f)
                    cubicTo(0f, 0f, 0f, size.height * .5f, size.width * .55f, size.height * .5f)
                    cubicTo(size.width, size.height * .5f, size.width, size.height, 0f, size.height)
                }
                drawPath(curve, accent, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        SolventaBrandHeader()
    }
}
