package co.solventa.mobile.feature.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import co.solventa.mobile.MainActivity
import co.solventa.mobile.R

/** Solo la variante de notificaciones declara este servicio iniciado desde una pantalla visible. */
internal class DemoListenerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = "solventa_escucha_local"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, getString(R.string.demo_listener_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        startForeground(77, NotificationCompat.Builder(this, channel).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.demo_listener_active)).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).build())
        return START_NOT_STICKY
    }
}
