package app.jarvis.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the alert listener back after a reboot, if the user had it on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val store = Store(context)
        if (store.alertsOn && store.ntfyTopic.length >= 8) {
            try { AlertService.start(context) } catch (e: Exception) { /* the system may refuse; the app can restart it */ }
        }
    }
}
