package app.jarvis.companion

import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ApiError(val code: Int, message: String) : Exception(message)

/** The PC's companion API. Blocking calls: run them off the main thread. */
class Api(address: String, private val token: String?) {
    private val base = Parse.baseUrl(address)

    private fun open(path: String, method: String, timeoutMs: Int): HttpURLConnection {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 8000
        c.readTimeout = timeoutMs
        c.setRequestProperty("Accept", "application/json")
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    private fun fail(c: HttpURLConnection): Nothing {
        val body = try { c.errorStream?.bufferedReader()?.readText().orEmpty() } catch (e: Exception) { "" }
        val detail = try { JSONObject(body).optString("detail", "") } catch (e: Exception) { "" }
        val msg = when (c.responseCode) {
            401 -> "This phone isn't paired (any more). Pair it again."
            403 -> detail.ifBlank { "Refused." }
            404 -> detail.ifBlank { "Not found." }
            429 -> "Too many attempts. Wait a few minutes."
            503 -> "JARVIS isn't ready yet."
            504 -> "He took too long."
            else -> detail.ifBlank { "Error ${c.responseCode}." }
        }
        throw ApiError(c.responseCode, msg)
    }

    private fun call(path: String, method: String = "GET", body: JSONObject? = null, timeoutMs: Int = 20000): String {
        val c = open(path, method, timeoutMs)
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            if (c.responseCode !in 200..299) fail(c)
            return c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: ApiError) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            throw ApiError(0, "No answer from the PC. Is it on, with JARVIS running and Tailscale connected?")
        } catch (e: java.io.IOException) {
            throw ApiError(0, "Can't reach the PC. Check Tailscale is connected on both devices.")
        } finally {
            c.disconnect()
        }
    }

    /** Returns (token, ntfyTopic?). */
    fun pair(code: String, name: String): Pair<String, String?> {
        val o = JSONObject(call("/pair", "POST", JSONObject().put("code", code).put("name", name)))
        return Pair(o.getString("token"), if (o.isNull("ntfy_topic")) null else o.optString("ntfy_topic"))
    }

    fun status(): Status = Parse.status(call("/status"))

    fun say(text: String): String =
        JSONObject(call("/say", "POST", JSONObject().put("text", text), timeoutMs = 170000)).optString("reply")

    fun search(query: String, kind: String): List<RemoteFile> =
        Parse.files(call("/files/search?q=" + URLEncoder.encode(query, "UTF-8") + "&kind=" + URLEncoder.encode(kind, "UTF-8")))

    fun outbox(): List<RemoteFile> = Parse.files(call("/files/outbox"))

    fun messages(after: Int): Pair<List<Msg>, Int> = Parse.messages(call("/messages?after=$after"))

    /** True if the PC could say it aloud. */
    fun relay(text: String, label: String, waitReply: Boolean): Pair<Boolean, String> {
        val o = JSONObject(
            call("/relay", "POST", JSONObject().put("text", text).put("label", label).put("wait_reply", waitReply))
        )
        return Pair(o.optBoolean("delivered", false), o.optString("reason", ""))
    }

    fun unlock(minutes: Int) { call("/unlock", "POST", JSONObject().put("minutes", minutes)) }

    fun lock() { call("/lock", "POST", JSONObject()) }

    /** Streams a file. `sink` gets the file name, the stream and the length (-1 if unknown). */
    fun download(id: String, sink: (String, InputStream, Long) -> Unit) {
        val c = open("/files/get/$id", "GET", 120000)
        try {
            if (c.responseCode !in 200..299) fail(c)
            sink(Parse.fileName(c.getHeaderField("Content-Disposition"), "download"), c.inputStream, c.contentLengthLong)
        } catch (e: ApiError) {
            throw e
        } catch (e: java.io.IOException) {
            throw ApiError(0, "The download was interrupted.")
        } finally {
            c.disconnect()
        }
    }
}
