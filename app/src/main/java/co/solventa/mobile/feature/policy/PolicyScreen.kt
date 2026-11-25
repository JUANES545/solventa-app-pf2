package co.solventa.mobile.feature.policy

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaLayout
import co.solventa.mobile.core.ui.SolventaBrandHeader
import co.solventa.mobile.core.ui.SolventaPage
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
internal fun PolicyHost(
    model: PolicyViewModel, report: ((Policy) -> Boolean)?, continueDraft: () -> Unit, reportBusy: Boolean = false,
    actions: PolicyActionsApi? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    var downloadMessage by remember(state.actor,state.selectedId) { mutableStateOf(0) }
    var actionBusy by remember(state.actor,state.selectedId) { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        val actor = state.actor
        val id = state.selectedId
        if (uri != null && actions != null && actor != null && id != null) scope.launch {
            try {
                val bytes = actions.document(actor,id)
                if (model.state.value.actor != actor || model.state.value.selectedId != id) return@launch
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri,"wt")).use { it.write(bytes) }
                }
                downloadMessage = R.string.wallet_downloaded
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { downloadMessage = R.string.travel_check_error }
        }
    }
    var draftConflict by remember(state.actor, state.selectedId) { mutableStateOf(false) }
    DisposableEffect(model, lifecycle, context) {
        val window = (context as? Activity)?.window
        val wasSecure = window != null && window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        if (co.solventa.mobile.BuildConfig.BUILD_TYPE !in setOf("travelTest","autoTest","videoTest") || !co.solventa.mobile.BuildConfig.SCREEN_CAPTURE_ALLOWED) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (!actionBusy) model.refresh()
                Lifecycle.Event.ON_STOP -> { draftConflict = false; if (!actionBusy) model.pause() }
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
    BackHandler { if (!actionBusy) model.back() }
    PolicyScreen(state, model::select, model::refresh, model::back,
        report?.let { action -> { policy -> draftConflict = !action(policy) } }, reportBusy, actions,
        downloadMessage, { state.selectedId?.let { save.launch("poliza-$it.html") } },actionBusy,{ actionBusy=it })
    if (draftConflict) {
        AlertDialog(onDismissRequest = { draftConflict = false },
            title = { Text(stringResource(R.string.policy_draft_title)) },
            text = { Text(stringResource(R.string.policy_draft_body)) },
            confirmButton = {
                TextButton(onClick = { draftConflict = false; continueDraft() },
                    modifier = Modifier.heightIn(min = SolventaLayout.controlHeight)) {
                    Text(stringResource(R.string.policy_continue_draft))
                }
            },
            dismissButton = {
                TextButton(onClick = { draftConflict = false },
                    modifier = Modifier.heightIn(min = SolventaLayout.controlHeight)) {
                    Text(stringResource(R.string.policy_keep_detail))
                }
            })
    }
}

@Composable
internal fun PolicyScreen(
    state: PolicyState, select: (Policy) -> Unit, refresh: () -> Unit, back: () -> Unit,
    report: ((Policy) -> Unit)? = null, reportBusy: Boolean = false, actions: PolicyActionsApi? = null,
    downloadMessage: Int = 0, download: () -> Unit = {},
    actionBusy: Boolean = false, onActionBusy: (Boolean) -> Unit = {},
) {
    SolventaPage(onRefresh = refresh, isRefreshing = state.busy, refreshEnabled = !state.busy && !actionBusy) {
        co.solventa.mobile.core.ui.SolventaScreenHeader(
            stringResource(if (state.selectedId == null) R.string.policy_title else R.string.policy_detail_title),
            stringResource(if (state.selectedId == null) R.string.policy_back_home else R.string.policy_back_list), back, !actionBusy)
        state.cachedAt?.let {
            Text(stringResource(R.string.policy_cached_at, java.text.DateFormat.getDateTimeInstance()
                .format(java.util.Date(it))), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        when {
            !state.loaded && state.error == null -> {
                co.solventa.mobile.core.ui.CollectionLoading(stringResource(R.string.policy_loading), state.selectedId != null)
            }
            state.error != null -> Text(stringResource(policyErrorText(state.error)),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
            state.detail != null -> {
                PolicyContent(state.detail)
                if (state.actor != null && state.cachedAt == null && actions != null) PolicyActions(state.actor,state.detail,actions,download,onActionBusy)
                if (downloadMessage != 0) Text(stringResource(downloadMessage), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (state.detail.status == PolicyStatus.ACTIVE && report != null) {
                    if (reportBusy) Text(stringResource(R.string.policy_report_busy),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Button(onClick = { report(state.detail) }, enabled = !reportBusy && !actionBusy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight),
                        shape = SolventaLayout.controlShape) {
                        Text(stringResource(R.string.claim_title))
                    }
                } else if (state.detail.status != PolicyStatus.ACTIVE) {
                    Text(stringResource(R.string.policy_report_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.selectedId == null && state.policies.isEmpty() -> Text(stringResource(R.string.policy_empty))
            state.selectedId == null -> PolicyCollection(state.policies, select)
        }
    }
}

@Composable
private fun PolicyContent(policy: Policy) {
    Text(stringResource(policyProductText(policy.product)), Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.policy_status, stringResource(policyStatusText(policy.status))))
    Text(stringResource(R.string.policy_coverage, policy.mainCoverage), style = MaterialTheme.typography.titleMedium)
    val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.forLanguageTag("es-CO"))
    Text(stringResource(R.string.policy_validity, dates.format(policy.start), dates.format(policy.end)))
    var expanded by remember(policy.id) { mutableStateOf(false) }
    if (policy.simulated) Text(stringResource(R.string.policy_simulated), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(if (expanded) R.string.ui_hide_details else R.string.wallet_show_number))
    }
    if (expanded) androidx.compose.foundation.text.selection.SelectionContainer { Text(stringResource(R.string.policy_number, policy.number)) }
}

internal fun policyStatusText(status: PolicyStatus): Int = when (status) {
    PolicyStatus.SCHEDULED -> R.string.policy_scheduled
    PolicyStatus.ACTIVE -> R.string.policy_active
    PolicyStatus.EXPIRED -> R.string.policy_expired
}

internal fun policyProductText(product: PolicyProduct): Int = when (product) {
    PolicyProduct.AUTO -> R.string.policy_auto
    PolicyProduct.TRAVEL -> R.string.policy_travel
    PolicyProduct.LIFE -> R.string.policy_life
    PolicyProduct.DEVICE -> R.string.policy_device
    PolicyProduct.PARAMETRIC -> R.string.policy_parametric
    PolicyProduct.WEATHER -> R.string.policy_weather
    PolicyProduct.PAYMENT -> R.string.policy_payment
    PolicyProduct.MORTGAGE -> R.string.policy_mortgage
}

internal fun policyErrorText(error: PolicyError): Int = when (error) {
    PolicyError.NETWORK -> R.string.policy_network
    PolicyError.SESSION -> R.string.policy_session
    PolicyError.UNAVAILABLE -> R.string.policy_unavailable
    PolicyError.PROTOCOL -> R.string.policy_protocol
    PolicyError.STORAGE -> R.string.policy_storage
}
