package app.jarvis.companion

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

data class RemoteFile(val id: String, val name: String, val folder: String, val size: Long, val modified: Long)

data class Msg(val id: Int, val from: String, val text: String, val at: Long)

data class Status(
    val jarvis: String,
    val pageOpen: Boolean,
    val lockEnabled: Boolean,
    val unlocked: Boolean,
    val problems: List<String>,
    val device: String,
)

/** Everything that turns the PC's JSON into plain objects. Pure, so it is unit-tested. */
object Parse {
    fun files(json: String): List<RemoteFile> {
        val arr = JSONObject(json).optJSONArray("files") ?: JSONArray()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            RemoteFile(
                id = o.getString("id"),
                name = o.optString("name", "file"),
                folder = o.optString("folder", ""),
                size = o.optLong("size", 0),
                modified = o.optLong("modified", 0),
            )
        }
    }

    fun messages(json: String): Pair<List<Msg>, Int> {
        val o = JSONObject(json)
        val arr = o.optJSONArray("messages") ?: JSONArray()
        val list = (0 until arr.length()).map {
            val m = arr.getJSONObject(it)
            Msg(m.getInt("id"), m.optString("from", "room"), m.optString("text", ""), m.optLong("at", 0))
        }
        return Pair(list, o.optInt("last", 0))
    }

    fun status(json: String): Status {
        val o = JSONObject(json)
        val lock = o.optJSONObject("lock")
        val problems = o.optJSONArray("problems")
        return Status(
            jarvis = o.optString("jarvis", "unknown"),
            pageOpen = o.optBoolean("page_open", false),
            lockEnabled = lock?.optBoolean("enabled", false) ?: false,
            unlocked = lock?.optBoolean("unlocked", false) ?: false,
            problems = if (problems == null) emptyList() else (0 until problems.length()).map { problems.getString(it) },
            device = o.optString("device", ""),
        )
    }

    /** The file name out of a Content-Disposition header, decoded. */
    fun fileName(header: String?, fallback: String): String {
        if (header == null) return fallback
        Regex("filename\\*=(?:UTF-8|utf-8)''([^;]+)").find(header)?.let {
            return try { URLDecoder.decode(it.groupValues[1].trim(), "UTF-8") } catch (e: Exception) { fallback }
        }
        Regex("filename=\"([^\"]+)\"").find(header)?.let { return it.groupValues[1] }
        Regex("filename=([^;]+)").find(header)?.let { return it.groupValues[1].trim() }
        return fallback
    }

    /** "http://100.64.1.2:8341" from whatever the user typed. */
    fun baseUrl(address: String): String {
        var a = address.trim().trimEnd('/')
        if (!a.startsWith("http://") && !a.startsWith("https://")) a = "http://$a"
        return a
    }

    fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
    }

    fun mimeFor(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            "dxf" -> "image/vnd.dxf"
            "txt", "gcode", "nc", "md", "csv" -> "text/plain"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }
}
