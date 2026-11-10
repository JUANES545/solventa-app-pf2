package co.solventa.mobile.feature.notification

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.solventa.mobile.R
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.core.designsystem.SolventaLayout
import co.solventa.mobile.core.ui.SolventaPage
import co.solventa.mobile.core.ui.SolventaScreenHeader
import co.solventa.mobile.core.ui.SolventaSkeletonList
import co.solventa.mobile.core.ui.SolventaLoadError
import co.solventa.mobile.core.ui.SolventaEmptyState
import co.solventa.mobile.core.ui.SolventaEmptyKind
import co.solventa.mobile.feature.claim.operationalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun NotificationHost(
    model: NotificationViewModel,
    requestPermission: () -> Unit,
) {
    val context = LocalContext.current
    val state by model.state.collectAsState()
    val runtimeGranted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
        context, Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
    val settingsRequired = !state.permissionGranted && (runtimeGranted ||
        (state.message == NotificationMessage.PERMISSION_DENIED &&
            (context as? Activity)?.let {
                !ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.POST_NOTIFICATIONS)
            } == true))
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.refreshPermission()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(context) {
        val window = (context as? Activity)?.window
        val wasSecure =
            window != null &&
                window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        if (!BuildConfig.SCREEN_CAPTURE_ALLOWED) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (!wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    BackHandler(onBack = model::close)
    NotificationScreen(
        state = state,
        requestPermission = {
            if (settingsRequired) {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                })
            } else requestPermission()
        },
        togglePush = { model.setPush(!state.pushEnabled) },
        markAllRead = model::markAllRead,
        refresh = { state.actor?.let(model::synchronize) },
        back = model::close,
        markRead = model::markRead,
        discard = model::discard,
        undo = model::undo,
        clearUndo = model::clearUndo,
        settingsRequired = settingsRequired,
    )
}

@Composable
internal fun NotificationScreen(
    state: NotificationUiState,
    requestPermission: () -> Unit,
    togglePush: () -> Unit,
    markAllRead: () -> Unit,
    refresh: () -> Unit,
    back: () -> Unit,
    markRead: (NotificationItem) -> Unit = {},
    discard: (NotificationItem) -> Unit = {},
    undo: (NotificationUndo) -> Unit = {},
    clearUndo: (NotificationUndo) -> Unit = {},
    settingsRequired: Boolean = false,
) {
    val snackbar = remember { SnackbarHostState() }
    val removed = stringResource(R.string.notification_removed)
    val marked = stringResource(R.string.notification_marked)
    val undoLabel = stringResource(R.string.notification_undo)
    LaunchedEffect(state.actor, state.undo) {
        val change = state.undo ?: return@LaunchedEffect
        if (snackbar.showSnackbar(
                if (state.removedItem != null) removed else marked,
                actionLabel = undoLabel,
                withDismissAction = true,
                duration = androidx.compose.material3.SnackbarDuration.Short,
            ) == SnackbarResult.ActionPerformed) undo(change)
        clearUndo(change)
    }
    Box {
    SolventaPage(onRefresh = refresh, isRefreshing = state.loadingNotifications,
        refreshEnabled = !state.busy, headerAlignment = Alignment.Start, header = {
        SolventaScreenHeader(stringResource(R.string.notification_title), stringResource(R.string.notification_back), back)
    }) {
        if (!state.permissionGranted || (state.pushPreferencesKnown && !state.pushEnabled)) {
            NotificationPermissionCard(state, requestPermission, togglePush, settingsRequired)
        }
        if (state.items.any { !it.read }) {
            TextButton(
                onClick = markAllRead,
                enabled = !state.busy,
                modifier = Modifier.heightIn(min = SolventaLayout.controlHeight),
            ) {
                Text(stringResource(R.string.notification_mark_all_read), textAlign = TextAlign.Center)
            }
        }
        state.message?.let {
            Text(
                stringResource(notificationMessageText(it)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        when {
            state.loadingNotifications && state.items.isEmpty() -> {
                SolventaSkeletonList(stringResource(R.string.notification_loading))
            }
            state.error != null && state.items.isEmpty() -> {
                SolventaLoadError(
                    stringResource(R.string.ui_load_notifications_error),
                    stringResource(notificationErrorText(state.error)),
                )
            }
            state.items.isEmpty() && !state.busy ->
                SolventaEmptyState(stringResource(R.string.notification_empty), SolventaEmptyKind.NOTIFICATIONS)
            state.items.isEmpty() -> {
                CircularProgressIndicator()
                Text(stringResource(R.string.operational_saving_notifications), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            else -> state.items.forEach { item ->
                key(state.actor, item.notification.id) {
                    co.solventa.mobile.core.ui.SolventaRefreshing(state.busy) {
                        NotificationCard(item, !state.busy, markRead, discard)
                    }
                }
            }
        }
        if (state.error != null && state.items.isNotEmpty()) {
            Text(
                stringResource(notificationErrorText(state.error)),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
    }
}

@Composable
private fun NotificationPermissionCard(
    state: NotificationUiState,
    requestPermission: () -> Unit,
    togglePush: () -> Unit,
    settingsRequired: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(
                        when {
                            settingsRequired -> R.string.notification_permission_settings_required
                            !state.permissionGranted -> R.string.notification_permission_required
                            else -> R.string.notification_permission_disabled
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(
                    onClick = if (state.permissionGranted) togglePush else requestPermission,
                    enabled = !state.busy,
                    modifier = Modifier.heightIn(min = SolventaLayout.controlHeight),
                ) {
                    Text(
                        stringResource(
                            when {
                                settingsRequired -> R.string.notification_settings
                                !state.permissionGranted -> R.string.notification_permission_allow
                                else -> R.string.notification_permission_turn_on
                            },
                        ),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun NotificationCard(
    item: NotificationItem,
    enabled: Boolean,
    markRead: (NotificationItem) -> Unit,
    discard: (NotificationItem) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val removeLabel = stringResource(R.string.notification_remove_local)
    val readLabel = stringResource(R.string.notification_mark_read)
    val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (enabled) when (value) {
            SwipeToDismissBoxValue.EndToStart -> discard(item)
            SwipeToDismissBoxValue.StartToEnd -> markRead(item)
            else -> Unit
        }
        false
    })
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = enabled && !item.read,
        enableDismissFromEndToStart = enabled,
        backgroundContent = {
            Box(Modifier.fillMaxWidth().padding(20.dp)) {
                Text(if (dismiss.dismissDirection == SwipeToDismissBoxValue.EndToStart) removeLabel else readLabel)
            }
        },
    ) {
    Card(
        Modifier.fillMaxWidth().semantics {
            customActions = if (enabled) buildList {
                if (!item.read) add(CustomAccessibilityAction(readLabel) { markRead(item); true })
                add(CustomAccessibilityAction(removeLabel) { discard(item); true })
            } else emptyList()
        },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = if (item.read)
            MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(
                    if (item.read) R.string.notification_read else R.string.notification_unread,
                ),
                color = if (item.read) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                item.notification.title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(item.notification.content, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { expanded = !expanded }, enabled = enabled,
                    modifier = Modifier.heightIn(min = SolventaLayout.controlHeight)) {
                    Text(stringResource(if (expanded) R.string.notification_details_hide else R.string.notification_details_show))
                }
                TextButton(onClick = { discard(item) }, enabled = enabled, modifier = Modifier.heightIn(min = SolventaLayout.controlHeight)) {
                    Text(removeLabel)
                }
            }
            if (expanded) {
            Text(
                operationalDate(item.notification.deliveredAt),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            if (!item.read) {
                TextButton(onClick = { markRead(item) }, enabled = enabled,
                    modifier = Modifier.heightIn(min = SolventaLayout.controlHeight)) { Text(readLabel) }
            }
            }
        }
    }
    }
}

internal fun notificationDate(notification: MobileNotification): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withZone(ZoneId.systemDefault())
        .format(notification.deliveredAt)

internal fun notificationErrorText(error: NotificationError): Int = when (error) {
    NotificationError.NETWORK -> R.string.notification_error_network
    NotificationError.SESSION -> R.string.notification_error_session
    NotificationError.UNAVAILABLE -> R.string.notification_error_unavailable
    NotificationError.PROTOCOL -> R.string.notification_error_protocol
    NotificationError.STORAGE -> R.string.notification_error_storage
}

internal fun notificationMessageText(message: NotificationMessage): Int = when (message) {
    NotificationMessage.PERMISSION_DENIED -> R.string.notification_permission_denied
    NotificationMessage.RESOURCE_UNAVAILABLE -> R.string.notification_resource_unavailable
}
