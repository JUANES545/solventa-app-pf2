package co.solventa.mobile.feature.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import co.solventa.mobile.MainActivity
import co.solventa.mobile.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

internal class AndroidNotificationPresenter @Inject constructor(
    @ApplicationContext private val context: Context,
) : NotificationPresenter {
    override fun permissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    override fun show(notification: MobileNotification) {
        if (!permissionGranted()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notification_channel_description)
            },
        )
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.parse("solventa://notification/${notification.id}")
            putExtra(EXTRA_NOTIFICATION_ID, notification.id)
            putExtra(EXTRA_RESOURCE_KIND, notification.resource.kind.code)
            putExtra(EXTRA_RESOURCE_ID, notification.resource.id)
        }
        val pending = PendingIntent.getActivity(
            context,
            notification.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            notification.id.hashCode(),
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(notification.title)
                .setContentText(notification.content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(notification.content))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    internal companion object {
        const val CHANNEL_ID = "solventa_avisos"
        const val EXTRA_NOTIFICATION_ID = "co.solventa.mobile.NOTIFICATION_ID"
        const val EXTRA_RESOURCE_KIND = "co.solventa.mobile.RESOURCE_KIND"
        const val EXTRA_RESOURCE_ID = "co.solventa.mobile.RESOURCE_ID"
    }
}
