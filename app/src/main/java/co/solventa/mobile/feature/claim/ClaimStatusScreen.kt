package co.solventa.mobile.feature.claim

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.core.ui.SolventaPage
import co.solventa.mobile.core.ui.SolventaSection
import co.solventa.mobile.core.ui.SolventaScreenHeader
import co.solventa.mobile.core.ui.SolventaSkeletonList
import co.solventa.mobile.core.ui.SolventaLoadError
import co.solventa.mobile.core.ui.SolventaSkeletonDetail
import co.solventa.mobile.core.ui.SolventaEmptyState
import co.solventa.mobile.core.ui.SolventaMetadataDetails
import co.solventa.mobile.core.ui.SolventaRefreshing
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
        if (!BuildConfig.SCREEN_CAPTURE_ALLOWED) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
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
    SolventaPage(headerAlignment = Alignment.Start, onRefresh = refresh, isRefreshing = state.busy, header = {
        SolventaScreenHeader(stringResource(R.string.claim_status_title), stringResource(R.string.claim_status_back), back)
    }) {
        val hasContent = state.detail != null || (state.selectedId == null && state.claims.isNotEmpty())
        state.error?.takeIf { hasContent }?.let {
            Text(stringResource(claimStatusErrorText(it)),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
        }
        when {
            state.busy && !hasContent -> {
                if (state.selectedId == null) SolventaSkeletonList(stringResource(R.string.claim_status_loading))
                else SolventaSkeletonDetail(stringResource(R.string.claim_status_loading))
            }
            state.error != null && !hasContent -> SolventaLoadError(
                stringResource(R.string.ui_load_claim_status_error),
                stringResource(claimStatusErrorText(state.error)),
                detail = state.selectedId != null,
            )
            state.detail != null -> {
                SolventaRefreshing(state.busy) {
                    SolventaSection {
                        Text(stringResource(claimProgressText(state.detail.progress)),
                            Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.titleLarge)
                        Text(state.detail.description)
                        Text(stringResource(R.string.claim_status_updated, operationalDate(state.detail.updatedAt)),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SolventaMetadataDetails(reference = state.detail.id, entries = listOf(
                            stringResource(R.string.operational_updated_instant) to state.detail.updatedAt.toString(),
                            stringResource(R.string.operational_display_zone) to ZoneId.systemDefault().id,
                        ))
                    }
                }
            }
            state.selectedId == null && state.claims.isEmpty() && state.error == null ->
                SolventaEmptyState(stringResource(R.string.claim_status_empty))
            state.selectedId == null -> {
                state.claims.forEach { claim ->
                    ClaimCard(claim, state.actor, state.busy, state.claims) { select(claim) }
                }
            }
        }
    }
}

internal fun claimStatusDate(date: Instant): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault()).format(date)

@Composable
internal fun operationalDate(date: Instant): String =
    stringResource(R.string.operational_date_device_zone, claimStatusDate(date))

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
