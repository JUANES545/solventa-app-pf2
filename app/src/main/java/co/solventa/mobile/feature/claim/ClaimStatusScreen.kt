package co.solventa.mobile.feature.claim

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaLayout
import co.solventa.mobile.core.ui.SolventaPage
import co.solventa.mobile.feature.evidence.EvidenceClaim
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun ClaimStatusHost(model: ClaimStatusViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by model.state.collectAsState()
    DisposableEffect(model, lifecycle, context) {
        val window = (context as? Activity)?.window
        val wasSecure = window != null && window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> model.refresh()
                Lifecycle.Event.ON_STOP -> model.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            model.pause()
            if (!wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    BackHandler(onBack = model::back)
    ClaimStatusScreen(state, model::select, model::refresh, model::back)
}

@Composable
internal fun ClaimStatusScreen(
    state: ClaimStatusState, select: (EvidenceClaim) -> Unit, refresh: () -> Unit, back: () -> Unit,
) {
    SolventaPage {
        TextButton(onClick = back, modifier = Modifier.heightIn(min = SolventaLayout.controlHeight)) {
            Text(stringResource(R.string.claim_status_back))
        }
        Text(stringResource(R.string.claim_status_title), Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineMedium)
        when {
            state.busy -> {
                CircularProgressIndicator()
                Text(stringResource(R.string.claim_status_loading), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            state.error != null -> Text(stringResource(claimStatusErrorText(state.error)),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
            state.detail != null -> {
                Text(stringResource(claimProgressText(state.detail.progress)),
                    Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.titleLarge)
                Text(state.detail.description)
                Text(stringResource(R.string.claim_status_updated, claimStatusDate(state.detail.updatedAt)))
                Text(stringResource(R.string.evidence_claim_reference, state.detail.id),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.selectedId == null && state.claims.isEmpty() -> Text(stringResource(R.string.claim_status_empty))
            state.selectedId == null -> {
                Text(stringResource(R.string.claim_status_choose))
                state.claims.forEach { claim ->
                    Button(onClick = { select(claim) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight)) {
                        Text("${claim.type}\n" +
                            stringResource(R.string.claim_status_event, claimStatusDate(Instant.parse(claim.date))) + "\n" +
                            stringResource(R.string.evidence_claim_reference, claim.id))
                    }
                }
            }
        }
        Button(onClick = refresh, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight)) {
            Text(stringResource(if (state.error == null) R.string.claim_status_refresh else R.string.claim_status_retry))
        }
    }
}

internal fun claimStatusDate(date: Instant): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(date)

internal fun claimProgressText(progress: ClaimProgress): Int = when (progress) {
    ClaimProgress.REPORTED -> R.string.claim_status_reported
    ClaimProgress.EVALUATING -> R.string.claim_status_evaluating
    ClaimProgress.REVIEWING -> R.string.claim_status_reviewing
    ClaimProgress.INSPECTING -> R.string.claim_status_inspecting
    ClaimProgress.APPROVED -> R.string.claim_status_approved
    ClaimProgress.REJECTED -> R.string.claim_status_rejected
    ClaimProgress.PAID -> R.string.claim_status_paid
    ClaimProgress.CLOSED -> R.string.claim_status_closed
}

internal fun claimStatusErrorText(error: ClaimStatusError): Int = when (error) {
    ClaimStatusError.NETWORK -> R.string.claim_status_network
    ClaimStatusError.SESSION -> R.string.claim_status_session
    ClaimStatusError.UNAVAILABLE -> R.string.claim_status_unavailable
    ClaimStatusError.PROTOCOL -> R.string.claim_status_protocol
    ClaimStatusError.STORAGE -> R.string.claim_status_storage
}
