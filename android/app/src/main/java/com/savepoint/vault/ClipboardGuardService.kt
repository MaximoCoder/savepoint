package com.savepoint.vault

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.savepoint.R

/**
 * Servicio corto en primer plano mientras hay una contraseña en el portapapeles.
 *
 * Samsung congela o mata las apps en segundo plano y aplaza los jobs, por eso el borrado
 * a los 30 s no ocurría en el teléfono real. Un servicio en primer plano mantiene vivo el proceso
 * exactamente el tiempo necesario y muestra una notificación con la cuenta regresiva y
 * un botón "Borrar ahora". En Android 14+ se declara como "shortService" (máximo 3 min).
 */
class ClipboardGuardService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLEAR) {
            SecureClipboard.clearNow(this)
            return START_NOT_STICKY
        }

        val expiresAt = intent?.getLongExtra(EXTRA_EXPIRES_AT, 0L) ?: 0L

        // Android exige llamar a startForeground en cuanto arranca el servicio, antes de cualquier otra cosa
        try {
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(expiresAt), type)
        } catch (e: Exception) {
            // Si el sistema no deja pasar a primer plano, quedan el job y el plazo del provider
        }

        val delay = expiresAt - System.currentTimeMillis()
        if (delay <= 0) {
            SecureClipboard.clearNow(this)
            return START_NOT_STICKY
        }

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ SecureClipboard.clearIfExpired(this) }, delay)
        return START_NOT_STICKY
    }

    /** Android 14+: el sistema avisa antes de cortar un shortService; se borra y se cierra. */
    override fun onTimeout(startId: Int) {
        SecureClipboard.clearNow(this)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun buildNotification(expiresAt: Long): android.app.Notification {
        ensureChannel(this)
        val clear = PendingIntent.getService(
            this,
            0,
            Intent(this, ClipboardGuardService::class.java).setAction(ACTION_CLEAR),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_clipboard)
            .setContentTitle("Contraseña en el portapapeles")
            .setContentText("Pégala manteniendo presionado el campo y tocando Pegar.")
            .setWhen(expiresAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setTimeoutAfter(expiresAt - System.currentTimeMillis() + 2_000)
            .addAction(0, "Borrar ahora", clear)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "clipboard"
        private const val NOTIFICATION_ID = 7002
        private const val ACTION_CLEAR = "com.savepoint.CLEAR_CLIPBOARD"
        private const val EXTRA_EXPIRES_AT = "expiresAt"

        fun start(ctx: Context, expiresAt: Long) {
            val intent = Intent(ctx, ClipboardGuardService::class.java).putExtra(EXTRA_EXPIRES_AT, expiresAt)
            try {
                ContextCompat.startForegroundService(ctx, intent)
            } catch (e: Exception) {
                // Restricciones de arranque en segundo plano: el resto de mecanismos sigue activo
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ClipboardGuardService::class.java))
        }

        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Portapapeles", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Cuenta regresiva mientras hay una contraseña copiada"
                    setShowBadge(false)
                },
            )
        }
    }
}
