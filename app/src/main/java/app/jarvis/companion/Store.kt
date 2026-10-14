package app.jarvis.companion

import android.content.Context

/** Everything the app remembers. */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    var address: String
        get() = p.getString("address", "") ?: ""
        set(v) { p.edit().putString("address", v).apply() }

    var token: String?
        get() = p.getString("token", null)
        set(v) { p.edit().putString("token", v).apply() }

    var ntfyTopic: String
        get() = p.getString("ntfy", "") ?: ""
        set(v) { p.edit().putString("ntfy", v.trim()).apply() }

    var deviceName: String
        get() = p.getString("device", android.os.Build.MODEL ?: "Phone") ?: "Phone"
        set(v) { p.edit().putString("device", v).apply() }

    var speakReplies: Boolean
        get() = p.getBoolean("tts", true)
        set(v) { p.edit().putBoolean("tts", v).apply() }

    var alertsOn: Boolean
        get() = p.getBoolean("alerts", false)
        set(v) { p.edit().putBoolean("alerts", v).apply() }

    var lastNtfy: Long
        get() = p.getLong("lastNtfy", 0)
        set(v) { p.edit().putLong("lastNtfy", v).apply() }

    val paired: Boolean get() = !token.isNullOrBlank() && address.isNotBlank()

    fun api(): Api = Api(address, token)

    fun forget() { p.edit().remove("token").apply() }
}
