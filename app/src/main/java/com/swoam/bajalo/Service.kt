package com.swoam.bajalo

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(Prefs.nightMode(this))
        Notifs.createChannels(this)
        Engine.init(this)
    }
}

object Notifs {
    const val CH_PROGRESS = "progreso"
    const val CH_DONE = "terminadas"
    const val ID_PROGRESS = 1

    fun createChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Descargas en curso", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Descargas terminadas", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openApp(c: Context, target: Class<*>) = PendingIntent.getActivity(
        c, target.hashCode(),
        Intent(c, target).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun progress(c: Context, items: List<Engine.Item>): android.app.Notification {
        val active = items.filter { it.active }
        val downloading = active.filter { it.status == Engine.Status.DOWNLOADING }
        val title = when (active.size) {
            0 -> "Preparando…"
            1 -> active[0].title
            else -> "Descargando ${active.size} elementos"
        }
        val pct = downloading.map { it.progress }.filter { it >= 0 }
        val b = NotificationCompat.Builder(c, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(c, MainActivity::class.java))
        if (pct.isEmpty()) {
            b.setContentText(if (downloading.isEmpty()) "En espera…" else "Descargando…")
            b.setProgress(0, 0, true)
        } else {
            val avg = pct.average().toInt()
            b.setContentText("$avg %")
            b.setProgress(100, avg, false)
        }
        return b.build()
    }

    fun done(c: Context, title: String, count: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(c, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (count == 1) "Descarga lista" else "$count archivos listos")
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(openApp(c, DownloadsActivity::class.java))
            .build()
        try {
            NotificationManagerCompat.from(c).notify((System.nanoTime() % Int.MAX_VALUE).toInt(), n)
        } catch (_: SecurityException) {
        }
    }
}

/** Servicio en primer plano: mantiene vivas las descargas aunque salgas de la app. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, Notifs.ID_PROGRESS, Notifs.progress(this, Engine.items.value), type)
        if (watcher == null) {
            watcher = scope.launch {
                Engine.items.collect { list ->
                    if (list.none { it.active }) {
                        ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        NotificationManagerCompat.from(this@DownloadService).let { nm ->
                            try {
                                nm.notify(Notifs.ID_PROGRESS, Notifs.progress(this@DownloadService, list))
                            } catch (_: SecurityException) {
                            }
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun ensureRunning(c: Context) {
            try {
                ContextCompat.startForegroundService(c, Intent(c, DownloadService::class.java))
            } catch (e: Exception) {
                // Android 12+ no deja iniciarlo desde segundo plano; la descarga sigue igual
                AppLog.log(c, "No se pudo iniciar el servicio en primer plano: ${e.message}")
            }
        }
    }
}
