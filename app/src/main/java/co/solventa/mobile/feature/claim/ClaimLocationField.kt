package co.solventa.mobile.feature.claim

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.solventa.mobile.R
import co.solventa.mobile.core.location.DEVICE_LOCATION_TIMEOUT_MILLIS
import co.solventa.mobile.core.location.DeviceLocation
import co.solventa.mobile.core.location.LocationPermission
import co.solventa.mobile.core.location.currentDeviceLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun ClaimLocationField(
    state: ClaimState,
    edit: (ClaimDraft) -> Unit,
    feedback: suspend (String) -> Unit,
    readState: () -> ClaimState = { state },
    address: (suspend (DeviceLocation) -> String?)? = null,
    onActiveChange: (Boolean) -> Unit = {},
    locate: (suspend () -> DeviceLocation?)? = null,
) {
    val feedbackScope = rememberCoroutineScope()
    ClaimLocationEditor(state, edit, feedback, readState, address, onActiveChange, locate,
        fallbackFeedback = { message -> feedbackScope.launch { feedback(message) } })
}

@Composable
private fun ClaimLocationEditor(
    state: ClaimState,
    edit: (ClaimDraft) -> Unit,
    feedback: suspend (String) -> Unit,
    readState: () -> ClaimState,
    address: (suspend (DeviceLocation) -> String?)?,
    onActiveChange: (Boolean) -> Unit,
    locate: (suspend () -> DeviceLocation?)?,
    fallbackFeedback: (String) -> Unit,
) {
        val context = LocalContext.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val currentState by rememberUpdatedState(readState)
        val currentEdit by rememberUpdatedState(edit)
        val currentAddress by rememberUpdatedState(address)
        val currentOnActiveChange by rememberUpdatedState(onActiveChange)
        val currentLocate by rememberUpdatedState(locate)
        val scope = rememberCoroutineScope()
        var loading by remember { mutableStateOf(false) }
        var permission by remember { mutableStateOf(false) }
        var expected by remember { mutableStateOf<ClaimState?>(null) }
        var requestToken by remember { mutableStateOf<Any?>(null) }
        var job by remember { mutableStateOf<Job?>(null) }
        fun setLoading(value: Boolean) {
            if (loading != value) {
                loading = value
                currentOnActiveChange(value)
            }
        }
        fun cancelLocation() {
            requestToken = null
            job?.cancel()
            job = null
            permission = false
            expected = null
            setLoading(false)
        }
        LaunchedEffect(state.actor, state.correction, state.locationRevision, state.busy, state.editing) {
            cancelLocation()
        }
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    cancelLocation()
                }
            }
            lifecycle.addObserver(observer)
            onDispose {
                requestToken = null
                job?.cancel()
                currentOnActiveChange(false)
                lifecycle.removeObserver(observer)
            }
        }
        val failed = stringResource(R.string.claim_location_failed)
        val fallback = stringResource(R.string.claim_location_coordinates)
        val kept = stringResource(R.string.claim_location_address_kept)
        fun valid(snapshot: ClaimState): Boolean {
            val latest = currentState()
            return latest.actor == snapshot.actor && latest.correction == snapshot.correction &&
                latest.locationRevision == snapshot.locationRevision && latest.editing && !latest.busy &&
                latest.draft.place == snapshot.draft.place
        }
        if (permission && expected?.let(::valid) == true) LocationPermission(onGranted = {
            permission = false
            val snapshot = expected
            val token = requestToken
            if (snapshot != null && token != null && valid(snapshot)) {
                job = scope.launch {
                    try {
                        val location = withTimeoutOrNull(DEVICE_LOCATION_TIMEOUT_MILLIS) {
                            val reader = currentLocate
                            if (reader != null) reader() else currentDeviceLocation(context)
                        }
                        if (!valid(snapshot)) return@launch
                        if (location == null) feedback(failed)
                        else {
                            val resolved = withTimeoutOrNull(4_000) {
                                val reader = currentAddress
                                if (reader != null) reader(location) else claimLocationAddress(context, location)
                            }
                            if (!valid(snapshot)) return@launch
                            if (resolved != null) currentEdit(currentState().draft.copy(place = resolved))
                            else {
                                if (snapshot.draft.place.isBlank()) {
                                    currentEdit(currentState().draft.copy(place = claimCoordinatePlace(location)))
                                }
                                fallbackFeedback(if (snapshot.draft.place.isBlank()) fallback else kept)
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        if (valid(snapshot)) feedback(failed)
                    } finally {
                        if (requestToken === token) {
                            requestToken = null
                            expected = null
                            job = null
                            setLoading(false)
                        }
                    }
                }
            } else cancelLocation()
        }, onCancel = { cancelLocation() })
        ClaimLocationInput(state.draft.place, !state.busy && state.editing, loading, onPlaceChange = {
            cancelLocation()
            edit(currentState().draft.copy(place = it))
        }, onLocate = {
            val latest = currentState()
            if (!loading && !latest.busy && latest.editing) {
                expected = latest
                requestToken = Any()
                permission = true
                setLoading(true)
            }
        })
}

@Composable
internal fun ClaimLocationInput(
    place: String, enabled: Boolean, loading: Boolean, onPlaceChange: (String) -> Unit, onLocate: () -> Unit,
) {
    val label = stringResource(R.string.claim_use_location)
    val locating = stringResource(R.string.claim_locating)
    OutlinedTextField(place, onPlaceChange, Modifier.fillMaxWidth().testTag("claim-place"), enabled = enabled,
            label = { Text(stringResource(R.string.claim_place)) },
            placeholder = { Text(stringResource(R.string.claim_place_hint)) },
            trailingIcon = {
                FilledTonalIconButton(onClick = onLocate, enabled = enabled && !loading, shape = CircleShape,
                    modifier = Modifier.size(48.dp).testTag("claim-current-location").semantics {
                        contentDescription = label
                        if (loading) stateDescription = locating
                    }) {
                    if (loading) CircularProgressIndicator(Modifier.size(22.dp).clearAndSetSemantics {},
                        strokeWidth = 2.dp)
                    else Icon(Icons.Default.LocationOn, contentDescription = null)
                }
            })
}
