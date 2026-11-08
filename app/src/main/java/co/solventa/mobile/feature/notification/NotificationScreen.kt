package co.solventa.mobile.feature.notification

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.solventa.mobile.R
import co.solventa.mobile.core.designsystem.SolventaLayout
import co.solventa.mobile.core.ui.SolventaPage
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
    DisposableEffect(context) {
        val window = (context as? Activity)?.window
        val wasSecure =
            window != null &&
                window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (!wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    BackHandler(onBack = model::close)
    NotificationScreen(
        state = state,
        requestPermission = requestPermission,
        togglePush = { model.setPush(!state.pushEnabled) },
        markAllRead = model::markAllRead,
        open = model::open,
        refresh = { state.actor?.let(model::synchronize) },
        back = model::close,
    )
}

@Composable
internal fun NotificationScreen(
    state: NotificationUiState,
    requestPermission: () -> Unit,
    togglePush: () -> Unit,
    markAllRead: () -> Unit,
    open: (NotificationItem) -> Unit,
    refresh: () -> Unit,
    back: () -> Unit,
) {
    SolventaPage {
        TextButton(
            onClick = back,
            modifier = Modifier.heightIn(min = SolventaLayout.controlHeight),
        ) {
            Text(stringResource(R.string.notification_back))
        }
        Text(
            stringResource(R.string.notification_title),
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        NotificationPermissionCard(state, requestPermission, togglePush)
        if (state.items.any { !it.read }) {
            TextButton(
                onClick = markAllRead,
                enabled = !state.busy,
                modifier = Modifier.heightIn(min = SolventaLayout.controlHeight),
            ) {
                Text(stringResource(R.string.notification_mark_all_read))
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
            state.busy && state.items.isEmpty() -> {
                CircularProgressIndicator()
                Text(stringResource(R.string.notification_loading))
            }
            state.error != null && state.items.isEmpty() -> {
                Text(
                    stringResource(notificationErrorText(state.error)),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            state.items.isEmpty() -> Text(stringResource(R.string.notification_empty))
            else -> state.items.forEach { item -> NotificationCard(item, open) }
        }
        if (state.error != null && state.items.isNotEmpty()) {
            Text(
                stringResource(notificationErrorText(state.error)),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Button(
            onClick = refresh,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight),
        ) {
            Text(stringResource(R.string.notification_refresh))
        }
        Text(
            stringResource(R.string.notification_privacy_notice),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun NotificationPermissionCard(
    state: NotificationUiState,
    requestPermission: () -> Unit,
    togglePush: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.notification_permission_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(
                    when {
                        !state.permissionGranted -> R.string.notification_permission_required
                        state.pushEnabled -> R.string.notification_permission_enabled
                        else -> R.string.notification_permission_disabled
                    },
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = if (state.permissionGranted) togglePush else requestPermission,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = SolventaLayout.controlHeight),
            ) {
                Text(
                    stringResource(
                        when {
                            !state.permissionGranted -> R.string.notification_permission_allow
                            state.pushEnabled -> R.string.notification_permission_turn_off
                            else -> R.string.notification_permission_turn_on
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun NotificationCard(item: NotificationItem, open: (NotificationItem) -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(
            1.dp,
            if (item.read) {
                MaterialTheme.colorScheme.outline
            } else {
                MaterialTheme.colorScheme.primary
            },
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(item.notification.content, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(
                onClick = { open(item) },
                modifier = Modifier.heightIn(min = SolventaLayout.controlHeight),
            ) {
                Text(stringResource(R.string.notification_open))
            }
            Text(
                notificationDate(item.notification),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
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
