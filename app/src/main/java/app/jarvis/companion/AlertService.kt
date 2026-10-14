package app.jarvis.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Listens to the user's private ntfy topic and turns what JARVIS pushes (reminders, "file
 * ready", a reply from the room, a failed build) into phone notifications, even with the app
 * closed. ntfy is a free public service: pick a long, random topic and keep it secret.
 */
class AlertService : Service() {
    @Volatile private var running = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannels(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val note = Notification.Builder(this, CH_SERVICE)
            .setContentTitle("JARVIS is listening for alerts")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, note)
        }
        if (!running) {
            running = true
            worker = Thread { loop() }.also { it.isDaemon = true; it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        super.onDestroy()
    }

    private fun loop() {
        val store = Store(this)
        var backoff = 3000L
        while (running) {
            val topic = store.ntfyTopic
            if (topic.length < 8) { sleep(15000); continue }
            try {
                val since = if (store.lastNtfy > 0) store.lastNtfy.toString() else "10m"
                val c = URL("https://ntfy.sh/$topic/json?since=$since").openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 120000
                c.inputStream.bufferedReader().use { r ->
                    backoff = 3000L
                    while (running) {
                        val line = r.readLine() ?: break
                        if (line.isBlank()) continue
                        handle(store, line)
                    }
                }
            } catch (e: Exception) {
                // network drop, timeout between keepalives: reconnect
            }
            sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(60000L)
        }
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (e: InterruptedException) { /* stopping */ }
    }

    private fun handle(store: Store, line: String) {
        val o = try { JSONObject(line) } catch (e: Exception) { return }
        if (o.optString("event") != "message") return
        val time = o.optLong("time", 0)
        if (time > 0) store.lastNtfy = time + 1
        val text = o.optString("message", "")
        if (text.isBlank()) return
        val title = o.optString("title", "JARVIS")
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra("tab", if (text.startsWith("File ready")) "files" else "relay"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CH_ALERTS)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify((System.currentTimeMillis() % 100000).toInt() + 10, n)
    }

    companion object {
        const val CH_SERVICE = "service"
        const val CH_ALERTS = "alerts"

        fun ensureChannels(ctx: Context) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CH_SERVICE, "Background connection", NotificationManager.IMPORTANCE_MIN)
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_ALERTS, "JARVIS alerts", NotificationManager.IMPORTANCE_HIGH)
            )
        }

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, AlertService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AlertService::class.java))
        }
    }
}
