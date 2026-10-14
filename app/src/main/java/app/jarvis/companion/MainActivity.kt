package app.jarvis.companion

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var store: Store
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool()

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    private lateinit var content: FrameLayout
    private val tabViews = mutableMapOf<String, View>()
    private val tabButtons = mutableMapOf<String, TextView>()
    private var current = "talk"

    // Talk
    private lateinit var talkLog: LinearLayout
    private lateinit var talkScroll: ScrollView
    private lateinit var talkInput: android.widget.EditText
    private lateinit var micButton: android.widget.Button
    private lateinit var partial: TextView

    // Files
    private lateinit var fileQuery: android.widget.EditText
    private lateinit var outboxList: LinearLayout
    private lateinit var resultList: LinearLayout
    private var kind = ""

    // Relay
    private lateinit var relayLog: LinearLayout
    private lateinit var relayScroll: ScrollView
    private var lastMsgId = 0
    private val poller = object : Runnable {
        override fun run() {
            if (current == "relay" && store.paired) pollMessages()
            main.postDelayed(this, 3000)
        }
    }

    // Status
    private lateinit var statusBox: LinearLayout

    // ── lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.bg
        window.navigationBarColor = Theme.bg
        store = Store(this)
        tts = TextToSpeech(this) { s ->
            ttsReady = s == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.UK
        }
        show()
        main.postDelayed(poller, 3000)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (store.paired) intent.getStringExtra("tab")?.let { selectTab(it) }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        tts?.shutdown()
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun show() {
        if (store.paired) showMain() else showPairing()
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun <T> bg(work: () -> T, done: (Result<T>) -> Unit) {
        pool.execute {
            val r = runCatching(work)
            main.post { if (!isDestroyed && !isFinishing) done(r) }
        }
    }

    private fun errText(t: Throwable): String = (t as? ApiError)?.message ?: t.message ?: "Something went wrong."

    /** Handles the errors every screen shares. Returns true if it already dealt with it. */
    private fun handledAuth(t: Throwable): Boolean {
        if (t is ApiError && t.code == 401) {
            store.forget()
            toast("This phone is no longer paired.")
            show()
            return true
        }
        return false
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun scrollView(child: View): ScrollView =
        ScrollView(this).apply { addView(child, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }

    private fun toggle(text: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch =
        Switch(this).apply {
            this.text = text
            setTextColor(Theme.text)
            isChecked = checked
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }

    // ── pairing ────────────────────────────────────────────────────────────

    private fun showPairing() {
        val col = vertical(20)
        col.setBackgroundColor(Theme.bg)
        col.add(label("JARVIS", 30f, Theme.accent, true), 24)
        col.add(label("Companion", 16f, Theme.dim), 0)
        col.add(
            label(
                "1. Install Tailscale on this phone and on your PC and sign in to the same account.\n" +
                    "2. On the PC, open JARVIS and press \"Pair a phone\" on his display.\n" +
                    "3. Enter the address and the code it shows.",
                14f, Theme.text,
            ), 20,
        )
        val address = field("PC address, e.g. 100.101.102.103:8341").also { it.setText(store.address) }
        val code = field("Pairing code, e.g. ABCD-EFGH")
        val name = field("Name for this phone").also { it.setText(store.deviceName) }
        col.add(address, 20)
        col.add(code, 10)
        col.add(name, 10)
        val msg = label("", 14f, Theme.bad)
        val go = button("Pair", primary = true) {}
        go.setOnClickListener {
            val a = address.text.toString().trim()
            val c = code.text.toString().trim()
            val n = name.text.toString().trim().ifBlank { "Phone" }
            if (a.isBlank() || c.isBlank()) { msg.text = "Enter both the address and the code."; return@setOnClickListener }
            go.isEnabled = false
            msg.setTextColor(Theme.dim)
            msg.text = "Pairing…"
            bg({ Api(a, null).pair(c, n) }) { r ->
                go.isEnabled = true
                r.fold({ (token, topic) ->
                    store.address = a
                    store.token = token
                    store.deviceName = n
                    if (!topic.isNullOrBlank() && store.ntfyTopic.isBlank()) store.ntfyTopic = topic
                    showMain()
                }, {
                    msg.setTextColor(Theme.bad)
                    msg.text = errText(it)
                })
            }
        }
        col.add(go, 16)
        col.add(msg, 10)
        setContentView(scrollView(col).apply { setBackgroundColor(Theme.bg) })
    }

    // ── the main screen ────────────────────────────────────────────────────

    private fun showMain() {
        tabViews.clear()
        tabButtons.clear()
        val rootCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.bg)
        }
        val bar = horizontal().apply { setPadding(dp(16), dp(14), dp(16), dp(6)) }
        bar.addView(label("JARVIS", 22f, Theme.accent, true))
        rootCol.addView(bar)

        content = FrameLayout(this)
        rootCol.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val tabs = horizontal().apply { setBackgroundColor(Theme.panel) }
        for ((id, title) in listOf("talk" to "Talk", "files" to "Files", "relay" to "Relay", "status" to "Status")) {
            val t = label(title, 15f, Theme.dim, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(14))
                setOnClickListener { selectTab(id) }
            }
            tabButtons[id] = t
            tabs.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        rootCol.addView(tabs)

        tabViews["talk"] = buildTalk()
        tabViews["files"] = buildFiles()
        tabViews["relay"] = buildRelay()
        tabViews["status"] = buildStatus()
        for (v in tabViews.values) { v.visibility = View.GONE; content.addView(v) }

        setContentView(rootCol)
        selectTab(intent?.getStringExtra("tab") ?: "talk")
    }

    private fun selectTab(id: String) {
        if (!tabViews.containsKey(id)) return
        current = id
        for ((k, v) in tabViews) v.visibility = if (k == id) View.VISIBLE else View.GONE
        for ((k, b) in tabButtons) b.setTextColor(if (k == id) Theme.accent else Theme.dim)
        when (id) {
            "files" -> refreshOutbox()
            "relay" -> pollMessages()
            "status" -> refreshStatus()
        }
    }

    // ── Talk ───────────────────────────────────────────────────────────────

    private fun buildTalk(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        talkLog = vertical(12)
        talkScroll = scrollView(talkLog)
        col.addView(talkScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        partial = label("", 14f, Theme.dim).apply { setPadding(dp(16), 0, dp(16), dp(4)) }
        col.addView(partial)
        val row = horizontal().apply { setPadding(dp(12), dp(6), dp(12), dp(10)) }
        talkInput = field("Ask JARVIS…", multiline = true)
        row.addView(talkInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val send = button("Send", primary = true) { sendTalk(talkInput.text.toString()) }
        row.addView(send, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        micButton = button("🎤") { toggleListening() }
        row.addView(micButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        col.addView(row)
        bubble("Hello. Ask me anything, or say: find the logo SVG and send it to my phone.", mine = false)
        return col
    }

    private fun bubble(text: String, mine: Boolean): TextView {
        val t = label(text, 15f, if (mine) Color_text_on_accent() else Theme.text).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (mine) Theme.accent else Theme.panel)
            }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(8)
        lp.gravity = if (mine) Gravity.END else Gravity.START
        lp.leftMargin = if (mine) dp(48) else 0
        lp.rightMargin = if (mine) 0 else dp(48)
        talkLog.addView(t, lp)
        talkScroll.post { talkScroll.fullScroll(View.FOCUS_DOWN) }
        return t
    }

    @Suppress("FunctionName")
    private fun Color_text_on_accent(): Int = android.graphics.Color.parseColor("#02131F")

    private fun sendTalk(text: String) {
        val q = text.trim()
        if (q.isEmpty()) return
        talkInput.setText("")
        bubble(q, mine = true)
        val waiting = bubble("…", mine = false)
        bg({ store.api().say(q) }) { r ->
            r.fold({ reply ->
                waiting.text = reply
                if (store.speakReplies && ttsReady) tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "reply")
                refreshOutbox()
            }, {
                if (!handledAuth(it)) waiting.text = errText(it)
            })
            talkScroll.post { talkScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun toggleListening() {
        if (listening) { recognizer?.stopListening(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("This phone has no speech recogniser. Install or enable Google's, or type instead.")
            return
        }
        tts?.stop()
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { r ->
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { listening = true; micButton.text = "⏹"; partial.text = "Listening…" }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    listening = false; micButton.text = "🎤"
                    partial.text = if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) "I didn't catch that." else "Speech error $error."
                }
                override fun onResults(results: Bundle?) {
                    listening = false; micButton.text = "🎤"; partial.text = ""
                    val said = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!said.isNullOrBlank()) sendTalk(said)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    partial.text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            r.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (requestCode == 1 && granted) toggleListening()
        if (requestCode == 2) startAlerts(granted)
    }

    // ── Files ──────────────────────────────────────────────────────────────

    private fun buildFiles(): View {
        val col = vertical(12)
        col.add(label("Find a file on your PC", 17f, Theme.text, true), 0)
        fileQuery = field("Name, e.g. logo or badge")
        col.add(fileQuery, 8)
        val chips = horizontal()
        val chipViews = mutableListOf<Pair<String, android.widget.Button>>()
        fun paintChips() { for ((k, b) in chipViews) b.alpha = if (k == kind) 1f else 0.45f }
        for ((k, t) in listOf("" to "All", "laser" to "Laser", "svg" to "SVG", "png" to "PNG", "vector" to "Vector", "pdf" to "PDF")) {
            val b = button(t) { kind = k; paintChips() }
            chipViews += k to b
            chips.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) })
        }
        paintChips()
        col.add(android.widget.HorizontalScrollView(this).apply { addView(chips) }, 8)
        val actions = horizontal()
        actions.addView(button("Search", primary = true) { searchFiles() })
        actions.addView(button("Ask JARVIS to find & send") { askJarvisToSend() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        col.add(actions, 10)
        col.add(label("Waiting from JARVIS", 15f, Theme.dim, true), 18)
        outboxList = vertical(0)
        col.add(outboxList, 4)
        col.add(label("Results", 15f, Theme.dim, true), 18)
        resultList = vertical(0)
        col.add(resultList, 4)
        return scrollView(col)
    }

    private fun searchFiles() {
        val q = fileQuery.text.toString().trim()
        if (q.isEmpty() && kind.isEmpty()) { toast("Type part of the file name, or pick a type."); return }
        resultList.removeAllViews()
        resultList.addView(label("Searching…", 14f, Theme.dim))
        bg({ store.api().search(q, kind) }) { r ->
            resultList.removeAllViews()
            r.fold({ files ->
                if (files.isEmpty()) resultList.addView(label("Nothing found. Try \"Ask JARVIS\", or add the folder by saying \"also look in D:\\Laser\".", 14f, Theme.dim))
                files.forEach { addFileRow(resultList, it) }
            }, {
                if (!handledAuth(it)) resultList.addView(label(errText(it), 14f, Theme.bad))
            })
        }
    }

    private fun askJarvisToSend() {
        val q = fileQuery.text.toString().trim()
        if (q.isEmpty()) { toast("Tell me what to look for first."); return }
        selectTab("talk")
        val what = if (kind.isEmpty()) "" else " ($kind files)"
        sendTalk("Find the file $q$what on my PC and send it to my phone.")
    }

    private fun refreshOutbox() {
        if (!::outboxList.isInitialized) return
        bg({ store.api().outbox() }) { r ->
            outboxList.removeAllViews()
            r.fold({ files ->
                if (files.isEmpty()) outboxList.addView(label("Nothing waiting.", 14f, Theme.dim))
                files.forEach { addFileRow(outboxList, it) }
            }, {
                if (!handledAuth(it)) outboxList.addView(label(errText(it), 14f, Theme.bad))
            })
        }
    }

    private fun addFileRow(into: LinearLayout, f: RemoteFile) {
        val card = card()
        card.addView(label(f.name, 16f, Theme.text, true))
        val whenText = if (f.modified > 0) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(f.modified * 1000)) else ""
        card.addView(label(listOf(f.folder, Parse.humanSize(f.size), whenText).filter { it.isNotBlank() }.joinToString(" · "), 13f, Theme.dim))
        val state = label("", 13f, Theme.ok)
        val actions = horizontal()
        val dl = button("Download", primary = true) {}
        dl.setOnClickListener {
            dl.isEnabled = false
            state.setTextColor(Theme.dim)
            state.text = "Downloading…"
            bg({ downloadToDownloads(f) }) { r ->
                dl.isEnabled = true
                r.fold({ (uri, mime) ->
                    state.setTextColor(Theme.ok)
                    state.text = "Saved to Downloads/JARVIS"
                    actions.removeAllViews()
                    actions.addView(button("Open") { openFile(uri, mime) })
                    actions.addView(button("Share") { shareFile(uri, mime) },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
                }, {
                    if (!handledAuth(it)) { state.setTextColor(Theme.bad); state.text = errText(it) }
                })
            }
        }
        actions.addView(dl)
        card.add(actions, 8)
        card.add(state, 4)
        into.add(card, 8)
    }

    private fun downloadToDownloads(f: RemoteFile): Pair<Uri, String> {
        var saved: Uri? = null
        var mime = Parse.mimeFor(f.name)
        store.api().download(f.id) { name, input, _ ->
            mime = Parse.mimeFor(name)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/JARVIS")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw ApiError(0, "Couldn't create the file in Downloads.")
            contentResolver.openOutputStream(uri)?.use { out -> input.copyTo(out) }
                ?: throw ApiError(0, "Couldn't write the file.")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            saved = uri
        }
        return Pair(saved ?: throw ApiError(0, "The download didn't finish."), mime)
    }

    private fun openFile(uri: Uri, mime: String) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(Intent.createChooser(i, "Open with")) } catch (e: Exception) { toast("No app can open that. Try Share.") }
    }

    private fun shareFile(uri: Uri, mime: String) {
        val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Share"))
    }

    // ── Relay ──────────────────────────────────────────────────────────────

    private fun buildRelay(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = vertical(12)
        top.add(label("Speak to the room", 17f, Theme.text, true), 0)
        top.add(label("Your PC chimes, then says it aloud. It never listens silently: a reply is only the first thing said right after.", 13f, Theme.dim), 4)
        val msg = field("What should it say?", multiline = true)
        val from = field("From").also { it.setText(store.deviceName) }
        top.add(msg, 10)
        top.add(from, 8)
        var wait = true
        top.add(toggle("Wait for a reply", true) { wait = it }, 8)
        val result = label("", 14f, Theme.dim)
        val send = button("Send to the room", primary = true) {}
        send.setOnClickListener {
            val text = msg.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            send.isEnabled = false
            result.setTextColor(Theme.dim)
            result.text = "Sending…"
            bg({ store.api().relay(text, from.text.toString().trim(), wait) }) { r ->
                send.isEnabled = true
                r.fold({ (ok, reason) ->
                    if (ok) {
                        msg.setText("")
                        result.setTextColor(Theme.ok)
                        result.text = if (wait) "Said. Listening for a reply for 45 seconds…" else "Said."
                        addRelayLine("You", text, Theme.accent)
                        pollMessages()
                    } else {
                        result.setTextColor(Theme.bad)
                        result.text = reason.ifBlank { "Nobody could hear it." }
                    }
                }, {
                    if (!handledAuth(it)) { result.setTextColor(Theme.bad); result.text = errText(it) }
                })
            }
        }
        top.add(send, 10)
        top.add(result, 6)
        col.addView(top)
        relayLog = vertical(12)
        relayScroll = scrollView(relayLog)
        col.addView(relayScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return col
    }

    private fun addRelayLine(who: String, text: String, color: Int) {
        val t = label("$who: $text", 15f, color)
        relayLog.add(t, 6)
        relayScroll.post { relayScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun pollMessages() {
        if (!::relayLog.isInitialized) return
        bg({ store.api().messages(lastMsgId) }) { r ->
            r.onSuccess { (msgs, last) ->
                msgs.forEach {
                    if (it.from == "room") addRelayLine("Room", it.text, Theme.ok)
                    else addRelayLine("JARVIS", it.text, Theme.dim)
                }
                lastMsgId = maxOf(lastMsgId, last)
            }
            r.onFailure { handledAuth(it) }
        }
    }

    // ── Status ─────────────────────────────────────────────────────────────

    private fun buildStatus(): View {
        statusBox = vertical(12)
        return scrollView(statusBox)
    }

    private fun refreshStatus() {
        statusBox.removeAllViews()
        statusBox.addView(label("Checking…", 14f, Theme.dim))
        bg({ store.api().status() }) { r ->
            statusBox.removeAllViews()
            r.fold({ s -> renderStatus(s) }, {
                if (handledAuth(it)) return@fold
                statusBox.addView(label(errText(it), 15f, Theme.bad))
                statusBox.add(button("Try again") { refreshStatus() }, 10)
                renderSettings()
            })
        }
    }

    private fun renderStatus(s: Status) {
        val c = card()
        c.addView(label("JARVIS is ${s.jarvis}", 17f, if (s.jarvis == "ready") Theme.ok else Theme.warn, true))
        c.addView(label(if (s.pageOpen) "His page is open on the PC." else "His page isn't open on the PC (he can still speak through the speakers).", 14f, Theme.dim))
        s.problems.forEach { c.addView(label("• $it", 14f, Theme.bad)) }
        statusBox.add(c, 0)
        if (s.lockEnabled) {
            val l = card()
            l.addView(label(if (s.unlocked) "Voice lock: open" else "Voice lock: locked", 16f, if (s.unlocked) Theme.ok else Theme.warn, true))
            val row = horizontal()
            row.addView(button("Unlock 10 min", primary = true) { bg({ store.api().unlock(10) }) { refreshStatus() } })
            row.addView(button("Lock now") { bg({ store.api().lock() }) { refreshStatus() } },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
            l.add(row, 8)
            statusBox.add(l, 10)
        }
        renderSettings()
    }

    private fun renderSettings() {
        val c = card()
        c.addView(label("This phone", 16f, Theme.text, true))
        c.addView(label("PC: ${store.address}", 13f, Theme.dim))
        c.add(toggle("Read replies aloud", store.speakReplies) { store.speakReplies = it }, 10)
        val topic = field("ntfy topic (for alerts when the app is closed)", password = true).also { it.setText(store.ntfyTopic) }
        c.add(topic, 12)
        c.add(toggle("Background alerts", store.alertsOn) { on ->
            store.ntfyTopic = topic.text.toString()
            if (on) {
                if (store.ntfyTopic.length < 8) { toast("Enter your ntfy topic first (8+ characters)."); return@toggle }
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
                } else startAlerts(true)
            } else startAlerts(false)
        }, 10)
        c.add(button("Unpair this phone") {
            AlertService.stop(this)
            store.alertsOn = false
            store.forget()
            show()
        }, 14)
        statusBox.add(c, 10)
    }

    private fun startAlerts(on: Boolean) {
        store.alertsOn = on && store.ntfyTopic.length >= 8
        if (store.alertsOn) AlertService.start(this) else AlertService.stop(this)
        toast(if (store.alertsOn) "Background alerts on." else "Background alerts off.")
    }
}
