package co.solventa.mobile.feature.identity

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import co.solventa.mobile.core.ui.SolventaBrandHeader
import co.solventa.mobile.core.ui.SolventaPage

@Composable
internal fun IdentityHost(model: IdentityViewModel) {
    val state by model.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(model, lifecycle) {
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
        }
    }
    BackHandler(onBack = model::close)
    IdentityScreen(state, model::submit, model::refresh, model::close)
}

@Composable
internal fun IdentityScreen(state: IdentityState, submit: () -> Unit, refresh: () -> Unit, back: () -> Unit) {
    SolventaPage {
        co.solventa.mobile.core.ui.SolventaScreenHeader(stringResource(R.string.identity_title),stringResource(R.string.identity_back),back)
        Text(stringResource(R.string.identity_disclaimer))
        Text(stringResource(R.string.identity_fixtures), color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            state.busy || state.verification == null && state.error == null -> {
                CircularProgressIndicator()
                Text(stringResource(R.string.identity_loading),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            state.error != null -> Text(stringResource(identityErrorText(state.error)),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
            state.verification != null -> {
                val verification = state.verification
                Text(stringResource(when (verification.status) {
                    IdentityStatus.PENDING -> R.string.identity_pending
                    IdentityStatus.APPROVED -> R.string.identity_approved
                    IdentityStatus.REJECTED -> R.string.identity_rejected
                }), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.identity_attempts, verification.attempts, verification.maxAttempts))
                verification.reason?.let { reason ->
                    Text(stringResource(if (reason == IdentityReason.DOCUMENT)
                        R.string.identity_document_rejected else R.string.identity_liveness_rejected))
                }
                if (verification.canSubmit) {
                    Button(onClick = submit, modifier = Modifier.fillMaxWidth()
                        .heightIn(min = SolventaLayout.controlHeight), shape = SolventaLayout.controlShape) {
                        Text(stringResource(if (verification.status == IdentityStatus.PENDING)
                            R.string.identity_submit else R.string.identity_retry))
                    }
                } else if (verification.status == IdentityStatus.REJECTED) {
                    Text(stringResource(R.string.identity_exhausted))
                }
            }
        }
        OutlinedButton(onClick = refresh, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight)) {
            Text(stringResource(R.string.identity_refresh))
        }
    }
}

internal fun identityErrorText(error: IdentityError): Int = when (error) {
    IdentityError.NETWORK -> R.string.identity_network
    IdentityError.SESSION -> R.string.identity_session
    IdentityError.DENIED -> R.string.identity_denied
    IdentityError.NOT_FOUND -> R.string.identity_not_found
    IdentityError.CONFLICT -> R.string.identity_conflict
    IdentityError.UNAVAILABLE -> R.string.identity_unavailable
    IdentityError.PROTOCOL -> R.string.identity_protocol
    IdentityError.STORAGE -> R.string.identity_storage
}
