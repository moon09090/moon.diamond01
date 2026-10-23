package com.aryan.overlay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.accessibilityservice.AccessibilityService
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlin.math.sqrt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.zip.ZipInputStream
import java.net.HttpURLConnection
import java.net.URL

class OverlayService : Service() {

    companion object {
        const val ACTION_SHOW = "com.aryan.overlay.SHOW"
        const val ACTION_WAKE = "com.aryan.overlay.WAKE"
        const val ACTION_VOICE = "com.aryan.overlay.VOICE"
        private const val CH = "overlay_status"
        private const val NID = 1

        // ---- wake word accuracy ----
        // Offline voice model, downloaded once (about 40 MB). Empty = choose automatically:
        // Indian English model if your phone region is India, otherwise US English.
        // Or force one from https://alphacephei.com/vosk/models
        private const val MODEL_OVERRIDE = ""

        // How forgiving the wake word match is. 1.0 = must be exact.
        // Lower = hears you more often but may trigger on similar words.
        // 0.78 is a good start; try 0.85 if it triggers by mistake, 0.70 if it misses you.
        private const val WAKE_TOLERANCE = 0.78

        // when the wake word is in the model's vocabulary, each word must be this sure
        private const val GRAMMAR_MIN_CONF = 0.55

        // ---- feature switches (true = on) ----
        private const val FOLLOW_UP = true            // keep listening after an AI answer
        private const val HISTORY_PAIRS = 6           // how many past exchanges the AI remembers
        private const val HISTORY_MINUTES = 10L       // forget after this much silence
        private const val STOP_WHILE_SPEAKING = true  // say "stop" to interrupt its voice
        private const val WEB_SEARCH = true           // let the AI look things up online
        private const val CONFIRM_RISKY_TASKS = true  // ask "are you sure?" before risky agent tasks
        private const val SCREEN_PRIVATE = "~private~"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var overlay: WebView? = null
    private var mode = 0
    private var listening = false
    private var started = false
    private var destroyed = false
    private var pendingMode = 0
    private var lastHeard = "-"
    private var lastAnswer = ""
    private var afterAnswerMode = 0
    private val recentMiss = ArrayList<String>()
    @Volatile private var vocab: HashSet<String>? = null
    private var pendingGoal: String? = null
    private val history = ArrayList<Pair<String, String>>()
    private var lastChat = 0L

    private fun p() = Cfg.prefs(this)
    private fun s(k: String) = Cfg.str(p(), k)

    private val restartRunnable = Runnable { startListen(pendingMode) }
    private val hideRunnable = Runnable { hideOverlay() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                ACTION_SHOW -> showOverlay(s("reply"))
                ACTION_WAKE -> onWake()
                ACTION_VOICE -> {
                    stopRecognizer()
                    speak(s("reply"), "test")
                }
                else -> {}
            }
        }
    }

    // ---------- lifecycle ----------
    override fun onCreate() {
        super.onCreate()
        restoreIfStuckMuted()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "Assistant status", NotificationManager.IMPORTANCE_LOW))
        val n = notif("Starting...", "")
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } catch (e: Exception) {
                startForeground(NID, n)
            }
        } else {
            startForeground(NID, n)
        }
        val f = IntentFilter()
        f.addAction(ACTION_SHOW)
        f.addAction(ACTION_WAKE)
        f.addAction(ACTION_VOICE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, 4)
        else registerReceiver(receiver, f)

        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { handler.post { onSpoken(id ?: "") } }
                    override fun onError(id: String?) { handler.post { onSpoken(id ?: "") } }
                })
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) {
            started = true
            scheduleListen(0, 300)
        }
        return START_STICKY
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        stopRecognizer()
        hideOverlay()
        hideResult()
        try { tts?.shutdown() } catch (_: Exception) {}
        try { voskModel?.close() } catch (_: Throwable) {}
        voskModel = null
        super.onDestroy()
    }

    // ---------- notification ----------
    private fun notif(title: String, text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun status(title: String, text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NID, notif(title, text))
        } catch (_: Exception) {}
    }

    private fun refreshStatus() {
        val title = when (mode) {
            0 -> "Waiting for wake word"
            2 -> "Speaking (say stop to interrupt)"
            else -> "Listening for your question"
        }
        var text = "Heard: " + lastHeard
        if (mode == 0 && recentMiss.isNotEmpty())
            text += "\nNot matched: " + recentMiss.joinToString(" | ") +
                "\n(Add the spelling it hears for your wake word in the wake words setting)"
        if (lastAnswer.isNotEmpty()) text += "\n\n" + lastAnswer
        status(title, text)
    }

    // ---------- one-time cleanup of volume left muted by older versions ----------
    private fun beepPrefs() = getSharedPreferences("beep_state", Context.MODE_PRIVATE)

    private fun restoreIfStuckMuted() {
        if (beepPrefs().getBoolean("muted", false)) {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                for (st in intArrayOf(AudioManager.STREAM_NOTIFICATION,
                    AudioManager.STREAM_SYSTEM, AudioManager.STREAM_MUSIC)) {
                    try { am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0) }
                    catch (_: Exception) {}
                }
            } catch (_: Exception) {}
            beepPrefs().edit().putBoolean("muted", false).apply()
        }
    }

    // ---------- silent always-on wake word (offline, no beeps, no restarts) ----------
    // One AudioRecord stays open and feeds an offline recognizer (Vosk). Nothing is
    // restarted, nothing beeps, and no volume is ever changed, so music and video are
    // not touched. The built-in phone mic is requested so Bluetooth headphones are not
    // pushed into low-quality call mode.
    @Volatile private var voskModel: Model? = null
    @Volatile private var modelLoading = false
    @Volatile private var wakeRun = false
    private var wakeThread: Thread? = null

    private fun modelCandidates(): List<String> {
        if (MODEL_OVERRIDE.isNotEmpty()) return listOf(MODEL_OVERRIDE)
        val india = Locale.getDefault().country.equals("IN", ignoreCase = true)
        return if (india) listOf("vosk-model-small-en-in-0.4", "vosk-model-small-en-us-0.15")
        else listOf("vosk-model-small-en-us-0.15")
    }

    // reads the model's word list so we know which words it can recognize
    private fun loadVocab(dir: File) {
        vocab = try {
            val f = File(dir, "graph/words.txt")
            if (!f.exists()) null else {
                val set = HashSet<String>()
                f.bufferedReader().useLines { lines ->
                    lines.forEach { set.add(it.substringBefore(' ').lowercase()) }
                }
                set
            }
        } catch (_: Throwable) { null }
    }

    private fun ensureModel() {
        if (voskModel != null || modelLoading || destroyed) return
        modelLoading = true
        Thread {
            var lastErr: Throwable? = null
            var ok = false
            for (name in modelCandidates()) {
                val dir = File(filesDir, "vosk/" + name)
                try {
                    val ready = File(dir, ".ready")
                    if (!ready.exists()) {
                        dir.deleteRecursively()
                        handler.post { status("Wake word setup", "Downloading offline voice model (one time)...") }
                        downloadAndUnzip(dir, "https://alphacephei.com/vosk/models/" + name + ".zip")
                        ready.writeText("ok")
                    }
                    handler.post { status("Wake word setup", "Loading voice model...") }
                    voskModel = Model(dir.absolutePath)
                    loadVocab(dir)
                    ok = true
                    break
                } catch (t: Throwable) {
                    lastErr = t
                    try { dir.deleteRecursively() } catch (_: Throwable) {}
                }
            }
            if (ok) {
                handler.post {
                    if (!destroyed) {
                        refreshStatus()
                        if (mode == 0) scheduleListen(0, 100)
                    }
                }
            } else {
                handler.post {
                    status("Wake word model problem",
                        "Check internet and storage. Retrying. " + (lastErr?.message ?: "").take(80))
                }
            }
            modelLoading = false
        }.start()
    }

    private fun downloadAndUnzip(dest: File, url: String) {
        val tmp = File(cacheDir, "vosk_model.zip")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 30000
        val total = c.contentLengthLong
        c.inputStream.use { ins ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(65536)
                var done = 0L
                var lastStep = -1
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val step = (done * 100 / total).toInt() / 10
                        if (step != lastStep) {
                            lastStep = step
                            handler.post {
                                status("Wake word setup", "Downloading voice model " + (step * 10) + "%")
                            }
                        }
                    }
                }
            }
        }
        dest.mkdirs()
        val root = dest.canonicalPath
        ZipInputStream(tmp.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                // drop the zip's top-level folder name
                val rel = entry.name.split("/").drop(1).joinToString("/")
                if (rel.isNotEmpty()) {
                    val out = File(dest, rel)
                    if (!out.canonicalPath.startsWith(root)) throw Exception("bad zip entry")
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zin.copyTo(it) }
                    }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
        tmp.delete()
    }

    private fun preferBuiltInMic(rec: AudioRecord) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?.let { rec.setPreferredDevice(it) }
        } catch (_: Exception) {}
    }

    private fun wakeList(): List<String> =
        s("wake_words").split(",").map { norm(it) }.filter { it.isNotEmpty() }

    // If every wake word exists in the model's vocabulary, the recognizer is limited to
    // ONLY those phrases (plus stop words and "unknown"). That is far more accurate.
    // If a wake word is not in the vocabulary (like a rare name), returns null and the
    // recognizer runs freely with forgiving matching instead.
    private fun grammarJson(): String? {
        val v = vocab ?: return null
        val wakes = wakeList()
        if (wakes.isEmpty()) return null
        for (w in wakes) for (word in w.split(" ")) if (word !in v) return null
        val arr = JSONArray()
        for (w in wakes) arr.put(w)
        for (sw in stopWords) if (sw.split(" ").all { it in v }) arr.put(sw)
        arr.put("[unk]")
        return arr.toString()
    }

    private fun parseCandidates(json: String): List<Pair<String, Double>> {
        val out = ArrayList<Pair<String, Double>>()
        val o = JSONObject(json)
        if (o.has("alternatives")) {
            val arr = o.getJSONArray("alternatives")
            for (i in 0 until arr.length()) {
                val tx = arr.getJSONObject(i).optString("text").trim()
                if (tx.isNotEmpty()) out.add(tx to 1.0)
            }
        } else {
            val tx = o.optString("text").trim()
            if (tx.isNotEmpty()) {
                var conf = 1.0
                val r = o.optJSONArray("result")
                if (r != null && r.length() > 0) {
                    var sum = 0.0
                    for (i in 0 until r.length()) sum += r.getJSONObject(i).optDouble("conf", 1.0)
                    conf = sum / r.length()
                }
                out.add(tx to conf)
            }
        }
        return out
    }

    private fun startWake() {
        if (destroyed || wakeRun) return
        if (wakeThread?.isAlive == true) { scheduleListen(mode, 200); return }
        val model = voskModel
        if (model == null) {
            ensureModel()
            scheduleListen(mode, 3000)
            return
        }
        val grammar = grammarJson()
        wakeRun = true
        val t = Thread {
            var rec: AudioRecord? = null
            var vr: Recognizer? = null
            var agc: AutomaticGainControl? = null
            var ns: NoiseSuppressor? = null
            var failed = false
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                val rate = 16000
                val minBuf = AudioRecord.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val r = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, 32000))
                rec = r
                if (r.state != AudioRecord.STATE_INITIALIZED) throw Exception("microphone busy")
                preferBuiltInMic(r)
                // let the phone clean and level the sound when it can
                try {
                    if (AutomaticGainControl.isAvailable()) {
                        agc = AutomaticGainControl.create(r.audioSessionId)
                        agc?.setEnabled(true)
                    }
                } catch (_: Throwable) {}
                try {
                    if (NoiseSuppressor.isAvailable()) {
                        ns = NoiseSuppressor.create(r.audioSessionId)
                        ns?.setEnabled(true)
                    }
                } catch (_: Throwable) {}

                val v = if (grammar != null) Recognizer(model, rate.toFloat(), grammar)
                        else Recognizer(model, rate.toFloat())
                vr = v
                if (grammar != null) v.setWords(true) else v.setMaxAlternatives(5)
                r.startRecording()
                handler.post { if (wakeRun) refreshStatus() }
                val buf = ShortArray(2048)
                while (wakeRun) {
                    val n = r.read(buf, 0, buf.size)
                    if (n < 0) throw Exception("microphone read error")
                    if (n == 0) { Thread.sleep(10); continue }
                    // gentle software boost for quiet voices (not for silence)
                    var sum = 0.0
                    for (i in 0 until n) sum += buf[i].toDouble() * buf[i].toDouble()
                    val rms = sqrt(sum / n)
                    if (rms in 120.0..2500.0) {
                        val g = (3000.0 / rms).coerceAtMost(3.0)
                        for (i in 0 until n)
                            buf[i] = (buf[i] * g).toInt().coerceIn(-32768, 32767).toShort()
                    }
                    if (v.acceptWaveForm(buf, n)) {
                        val cands = parseCandidates(v.result)
                        if (cands.isNotEmpty()) handler.post { onWakeTexts(cands) }
                    }
                }
            } catch (e: Throwable) {
                failed = true
            } finally {
                try { agc?.release() } catch (_: Throwable) {}
                try { ns?.release() } catch (_: Throwable) {}
                try { rec?.stop() } catch (_: Throwable) {}
                try { rec?.release() } catch (_: Throwable) {}
                try { vr?.close() } catch (_: Throwable) {}
            }
            if (failed && wakeRun) {
                wakeRun = false
                handler.post {
                    status("Microphone problem", "Retrying...")
                    scheduleListen(0, 2000)
                }
            }
        }
        wakeThread = t
        t.start()
    }

    private fun stopWake() {
        wakeRun = false
    }

    private val stopWords = setOf("stop", "stop it", "stop talking", "stop speaking",
        "be quiet", "cancel", "cancel that", "enough", "never mind")

    private fun isStopWord(text: String) = norm(text) in stopWords

    // stops its voice and any running agent task
    private fun interrupt() {
        try { tts?.stop() } catch (_: Exception) {}
        cancelAgent()
        pendingGoal = null
        handler.removeCallbacks(restartRunnable)
        hideResult()
        status("Stopped", "")
        scheduleListen(0, 300)
    }

    // asks the agent service to stop, if it has a stop-type method (safe if it does not)
    private fun cancelAgent() {
        val svc = PhoneAgentService.instance ?: return
        for (name in arrayOf("stop", "cancel", "stopGoal", "cancelGoal", "abort", "stopAgent")) {
            try { svc.javaClass.getMethod(name).invoke(svc); return } catch (_: Throwable) {}
        }
    }

    private fun noteMiss(text: String) {
        if (recentMiss.lastOrNull() == text) return
        recentMiss.add(text)
        while (recentMiss.size > 3) recentMiss.removeAt(0)
    }

    // runs on the main thread for every finished phrase (best guess first, then alternatives)
    private fun onWakeTexts(c: List<Pair<String, Double>>) {
        if (!wakeRun || c.isEmpty()) return
        if (mode == 2) {                      // while it speaks: only "stop" counts
            if (c.any { isStopWord(it.first) }) interrupt()
            return
        }
        if (mode != 0) return
        val top = c.first().first
        heard(top)
        for ((text, conf) in c) {
            if (conf >= GRAMMAR_MIN_CONF && isWake(text)) { onWake(); return }
        }
        if (c.any { isStopWord(it.first) }) { interrupt(); return }
        noteMiss(top)
        refreshStatus()
    }

    // ---------- listening ----------
    private fun scheduleListen(m: Int, delay: Long) {
        handler.removeCallbacks(restartRunnable)
        pendingMode = m
        handler.postDelayed(restartRunnable, delay)
    }

    private fun stopRecognizer() {
        listening = false
        stopWake()
        val r = recognizer
        recognizer = null
        if (r != null) {
            try { r.cancel() } catch (_: Exception) {}
            try { r.destroy() } catch (_: Exception) {}
        }
    }

    private fun startListen(m: Int) {
        if (destroyed) return
        handler.removeCallbacks(restartRunnable)
        if ((m == 0 || m == 2) && wakeRun && recognizer == null && mode == m) return
        stopRecognizer()
        mode = m
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            status("Microphone permission needed", "Open the app and allow the microphone.")
            scheduleListen(m, 3000)
            return
        }
        if (m == 0 || m == 2) {
            startWake()
            return
        }
        // question mode: normal Android recognizer (one short beep = "I'm listening")
        try {
            val r = SpeechRecognizer.createSpeechRecognizer(this)
            r.setRecognitionListener(listener)
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            val lang = s("listen_lang").trim()
            if (lang.isNotEmpty()) {
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            }
            recognizer = r
            listening = true
            r.startListening(i)
            refreshStatus()
        } catch (e: Exception) {
            status("Voice recognition problem", e.message ?: "unknown")
            scheduleListen(m, 3000)
        }
    }

    private fun first(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun heard(t: String) {
        if (t == lastHeard) return
        lastHeard = t
        refreshStatus()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(b: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(t: Int, b: Bundle?) {}

        override fun onPartialResults(b: Bundle?) {
            if (!listening) return
            val t = first(b) ?: return
            heard(t)
        }

        override fun onResults(b: Bundle?) {
            if (!listening) return
            listening = false
            val top = first(b)
            if (top != null) heard(top)
            val q = top?.trim().orEmpty()
            if (q.isEmpty()) scheduleListen(0, 200) else askAI(q)
        }

        override fun onError(e: Int) {
            if (!listening) return
            listening = false
            when (e) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    status("Microphone permission needed", "Open the app and allow the microphone.")
                    scheduleListen(0, 3000)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> scheduleListen(0, 1500)
                else -> scheduleListen(0, 300)   // no question heard: go back to waiting
            }
        }
    }

    private fun norm(t: String): String =
        t.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ").trim()

    // sound-alike simplification so "arian", "aryan", "ariyan" compare as close
    private fun phon(t: String): String {
        var x = norm(t).replace(" ", "")
        x = x.replace("ph", "f").replace("ck", "k").replace("c", "k")
            .replace("q", "k").replace("y", "i").replace("w", "v")
        return x.replace(Regex("(.)\\1+"), "$1")      // double letters -> single
    }

    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length)
    }

    // The whole sentence heard must sound like one of the wake words
    // (exact for very short wake words, forgiving by WAKE_TOLERANCE otherwise).
    private fun isWake(text: String): Boolean {
        val t = phon(text)
        if (t.isEmpty()) return false
        for (w in wakeList()) {
            val ww = phon(w)
            if (ww.isEmpty()) continue
            if (t == ww) return true
            if (ww.length <= 4) continue                 // short words: exact only
            if (t[0] != ww[0]) continue                  // must start the same
            if (similarity(t, ww) >= WAKE_TOLERANCE) return true
        }
        return false
    }

    // ---------- flow ----------
    private fun onWake() {
        stopRecognizer()
        handler.removeCallbacks(restartRunnable)
        val reply = s("reply")
        showOverlay(reply)
        status("Wake word heard", reply)
        if (Cfg.bool(p(), "speak_reply")) speak(reply, "reply") else afterReply()
    }

    private fun afterReply() {
        if (Cfg.bool(p(), "ask_question")) scheduleListen(1, 300) else scheduleListen(0, 300)
    }

    private fun onSpoken(id: String) {
        when (id) {
            "reply" -> afterReply()
            "answer" -> scheduleListen(afterAnswerMode, 300)
            "test" -> scheduleListen(0, 300)
            else -> {}
        }
    }

    private val yesWords = setOf("yes", "yeah", "yep", "yup", "sure", "ok", "okay", "confirm",
        "do it", "go ahead", "yes please", "correct", "proceed", "affirmative")

    private fun askAI(q: String) {
        hideResult()   // a result card must never cover the screen during a new command
        // 1) waiting for a yes/no about a risky agent task
        val pg = pendingGoal
        if (pg != null) {
            pendingGoal = null
            val ans = if (norm(q) in yesWords) startAgent(pg) else "Okay, cancelled"
            onAnswer(q, ans)
            return
        }
        // 2) "... show me on my screen" -> answer/calculation/task shown on a result card
        val showReq = showOnScreenRequest(q)
        if (showReq != null) { handleShowOnScreen(q, showReq); return }
        // 3) screen access ONLY for: open/search/play this|that|it, and "show me"
        if (isScreenCommand(q)) { handleScreenCommand(q); return }
        // 4) "search ..." must come back with the real result, not just "searching"
        val sq = searchQuery(q)
        if (sq != null) { answerWithWeb(q, sq); return }
        // 5) plain arithmetic is solved exactly on the phone
        val calc = calcAnswer(q)
        if (calc != null) { onAnswer(q, calc); return }
        // 4) instant phone commands, no AI needed
        val local = tryCommand(q)
        if (local != null) {
            onAnswer(q, local, pendingGoal != null)
            return
        }
        // 5) normal AI answer
        status("Thinking...", "You: " + q)
        Thread {
            val ans = try {
                callAI(q, q)
            } catch (e: Exception) {
                "Sorry, the AI failed: " + (e.message ?: "unknown").take(60)
            }
            handler.post { onAnswer(q, ans, FOLLOW_UP) }
        }.start()
    }

    private fun onAnswer(q: String, a: String, followUp: Boolean = false) {
        lastQ = q
        lastA = a
        lastAnswer = "You: " + q + "\nAI: " + a
        status("Answered", lastAnswer)
        afterAnswerMode = if (followUp) 1 else 0
        if (Cfg.bool(p(), "speak_answer")) speak(a, "answer") else scheduleListen(afterAnswerMode, 300)
    }

    // ---------- privacy: apps the assistant will never read or operate ----------
    private fun sensitivePkg(pkg: String?): Boolean = isSensitivePackage(pkg)

    private val sensitiveGoalRegex =
        Regex("\\b(bank|banking|password|passwords|otp|cvv|upi|pin|card number)\\b")
    private val riskyGoalRegex = Regex(
        "\\b(pay|send|transfer|buy|order|purchase|delete|remove|uninstall|post|publish|call|" +
        "message|text|whatsapp|email|book|subscribe|reset|format)\\b")

    // checkGoal = false only for goals this app wrote itself (the current app is still checked)
    private fun startAgent(goal: String, checkGoal: Boolean = true): String {
        val svc = PhoneAgentService.instance
            ?: return "Please turn on the accessibility service for agent tasks"
        val pkg = try { svc.rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
        if ((checkGoal && sensitiveGoalRegex.containsMatchIn(norm(goal))) || sensitivePkg(pkg))
            return "I won't operate banking or password apps"
        if (PhoneAgentService.model == null)
            PhoneAgentService.model = OpenRouterModel(s("key").trim())
        svc.runGoal(goal)
        return "Okay, working on it"
    }

    // runs a task on the phone now (asks yes/no first only for risky ones)
    private fun agentTask(goal: String, say: String = goal): String {
        if (PhoneAgentService.instance == null)
            return "Please turn on the accessibility service for agent tasks"
        if (sensitiveGoalRegex.containsMatchIn(norm(goal)))
            return "I won't operate banking or password apps"
        if (CONFIRM_RISKY_TASKS && riskyGoalRegex.containsMatchIn(norm(goal))) {
            pendingGoal = goal
            return "You want me to " + say + ". Say yes to confirm"
        }
        return startAgent(goal)
    }

    // opens YouTube results, then the agent taps the first video so it really plays
    private fun playOnYouTube(what: String): String {
        val u = "https://www.youtube.com/results?search_query=" + Uri.encode(what)
        if (!launch(Intent(Intent.ACTION_VIEW, Uri.parse(u)))) return "I couldn't open YouTube"
        if (PhoneAgentService.instance == null)
            return "I opened YouTube for " + what +
                ". Turn on the accessibility service and I will press play for you"
        handler.postDelayed({
            if (!destroyed) startAgent(
                "On the YouTube search results for " + what + ", tap the first real video " +
                    "result (not an ad, not a Short) so it starts playing. Stop once the " +
                    "video is playing.", false)
        }, 3500)
        return "Playing " + what
    }

    // ---------- screen reading ----------
    // The screen is touched ONLY by: "open this/that/it", "search this/that/it",
    // "play this/that/it" and "show me ...". Nothing else reads or edits the screen.
    private val screenCmdRegex = Regex(
        "^(?:please |hey |can you |could you )?" +
        "(?:(?:open|search|play) (?:this|that|it)(?: .*)?|show me(?: .*)?)$")

    private fun isScreenCommand(q: String): Boolean = screenCmdRegex.matches(norm(q))

    private fun handleScreenCommand(q: String) {
        val t = norm(q)
        if (Regex("\\bshow me\\b").containsMatchIn(t)) { askAboutScreen(q); return }
        val goal = "The user said: \"" + q.trim() + "\". Look at the current screen, find what " +
            "\"this\" or \"it\" refers to, and complete that request fully."
        onAnswer(q, agentTask(goal, q.trim()), pendingGoal != null)
    }

    private fun searchQuery(q: String): String? =
        Regex("^(?:please )?(?:search|google|look up)(?: for| about)? (.+)$")
            .find(norm(q))?.groupValues?.get(1)

    // searches the web and speaks the actual result
    private fun answerWithWeb(q: String, ask: String) {
        status("Searching...", "You: " + q)
        Thread {
            val ans = try {
                callAI("Search the web now and give me the actual answer, not just that you " +
                    "searched. Request: " + ask, q, true)
            } catch (e: Exception) {
                "Sorry, the AI failed: " + (e.message ?: "unknown").take(60)
            }
            handler.post { onAnswer(q, ans, FOLLOW_UP) }
        }.start()
    }

    // null = service off, SCREEN_PRIVATE = sensitive app, otherwise the visible text
    private fun screenText(): String? {
        val svc = PhoneAgentService.instance ?: return null
        val root = try { svc.rootInActiveWindow } catch (_: Exception) { null } ?: return null
        if (sensitivePkg(root.packageName?.toString())) return SCREEN_PRIVATE
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 25 || sb.length > 3500) return
            if (n.isPassword) return
            val tx = n.text?.toString() ?: n.contentDescription?.toString()
            if (!tx.isNullOrBlank()) sb.append(tx.trim()).append('\n')
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return sb.toString().take(3500)
    }

    private fun askAboutScreen(q: String) {
        val txt = screenText()
        when {
            txt == SCREEN_PRIVATE -> onAnswer(q, "That looks like a private app, so I won't read it")
            txt.isNullOrBlank() -> answerWithWeb(q, q)   // no screen text: still do the request
            else -> {
                status("Reading screen...", "You: " + q)
                Thread {
                    val ans = try {
                        callAI("Here is the text currently visible on my phone screen:\n" + txt +
                            "\n\nMy request: " + q +
                            "\nDo exactly what I asked and give me the result.", q, true)
                    } catch (e: Exception) {
                        "Sorry, the AI failed: " + (e.message ?: "unknown").take(60)
                    }
                    handler.post { onAnswer(q, ans, FOLLOW_UP) }
                }.start()
            }
        }
    }

    // ---------- "show me on my screen": result card ----------
    private var resultView: WebView? = null
    private var lastQ = ""
    private var lastA = ""
    private val hideResultRunnable = Runnable { hideResult() }

    private val onScreenRegex1 = Regex(
        "\\b(?:show|display)(?: me| it| that| this| the result| the answer| up)*[ ,]*" +
        "on (?:my |the )?(?:phone )?screen\\b")
    private val onScreenRegex2 = Regex(
        "^(?:please )?(?:show|display)(?: me)? (.+?)[ ,]*on (?:my |the )?(?:phone )?screen$")

    // null = not a "show on screen" command; "" = show the last answer again; else the request
    private fun showOnScreenRequest(q: String): String? {
        val t = q.lowercase().trim().trimEnd('.', '?', '!')
        var r: String
        val m1 = onScreenRegex1.find(t)
        if (m1 != null) {
            r = t.substring(0, m1.range.first) + " " + t.substring(m1.range.last + 1)
        } else {
            val m2 = onScreenRegex2.find(t) ?: return null
            r = m2.groupValues[1]
            if (r in setOf("me", "it", "that", "this", "the result", "the answer")) r = ""
        }
        r = r.replace(Regex("\\s+"), " ").trim()
        r = r.removePrefix("please ").removePrefix("and ").removeSuffix(" please")
            .removeSuffix(" and").removeSuffix(" then").trim().trimEnd(',', '.')
        return r
    }

    private fun handleShowOnScreen(q: String, r: String) {
        if (r.isEmpty()) {
            if (lastA.isEmpty()) {
                onAnswer(q, "Ask me something first, then say show me on my screen")
            } else {
                showResult(lastQ, lastA)
                scheduleListen(0, 300)
            }
            return
        }
        val calc = calcAnswer(r)
        if (calc != null) { finishShow(q, r, calc, false); return }
        val local = tryCommand(r)
        if (local != null) { finishShow(q, r, local, pendingGoal != null); return }
        status("Working on it...", "You: " + r)
        Thread {
            val ans = try {
                callAI(r, r, needsWeb(r))
            } catch (e: Exception) {
                "Sorry, the AI failed: " + (e.message ?: "unknown").take(60)
            }
            handler.post { finishShow(q, r, ans, FOLLOW_UP) }
        }.start()
    }

    private fun finishShow(q: String, r: String, a: String, follow: Boolean) {
        onAnswer(q, a, follow)
        showResult(r, a)
    }

    private fun hideResult() {
        handler.removeCallbacks(hideResultRunnable)
        val v = resultView
        resultView = null
        if (v != null) {
            try { getSystemService(WindowManager::class.java).removeView(v) } catch (_: Exception) {}
            try { v.destroy() } catch (_: Exception) {}
        }
    }

    // tap the card to close it; it also closes by itself and when you say "stop"
    private fun showResult(q: String, a: String) {
        hideResult()
        lastQ = q
        lastA = a
        if (!Settings.canDrawOverlays(this)) return
        try {
            val ms = (6000L + a.length * 70L).coerceIn(7000L, 25000L)
            val wv = WebView(this)
            wv.setBackgroundColor(Color.TRANSPARENT)
            wv.settings.javaScriptEnabled = true
            var downY = 0f
            wv.setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> downY = e.rawY
                    MotionEvent.ACTION_UP -> if (Math.abs(e.rawY - downY) < 24f) hideResult()
                }
                false
            }
            wv.loadDataWithBaseURL("https://localhost/", resultHtml(q, a, ms),
                "text/html", "utf-8", null)
            val lp = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT)
            lp.gravity = Gravity.TOP or Gravity.START
            getSystemService(WindowManager::class.java).addView(wv, lp)
            resultView = wv
            handler.postDelayed(hideResultRunnable, ms)
        } catch (e: Exception) {
            resultView = null
        }
    }

    private fun resultHtml(q: String, a: String, ms: Long): String =
        RESULT_HTML.replace("__Q__", JSONObject.quote(q))
            .replace("__A__", JSONObject.quote(a))
            .replace("__MS__", ms.toString())

    private val RESULT_HTML = """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
*{box-sizing:border-box;margin:0;padding:0}
html,body{height:100%;overflow:hidden;font-family:-apple-system,Roboto,'Segoe UI',sans-serif}
body{display:flex;align-items:center;justify-content:center;padding:22px;
 background:radial-gradient(ellipse at 50% 40%,rgba(30,36,90,.55),rgba(0,0,0,.78));
 animation:fade .3s ease}
.card{position:relative;width:100%;max-width:480px;max-height:78%;overflow:auto;
 padding:26px 24px 20px;border-radius:30px;color:#fff;
 background:linear-gradient(150deg,rgba(52,58,128,.97),rgba(18,22,58,.97) 70%);
 border:1px solid rgba(255,255,255,.2);
 box-shadow:0 24px 70px rgba(0,0,0,.55),0 0 70px rgba(110,130,255,.3),inset 0 1px 0 rgba(255,255,255,.18);
 animation:pop .45s cubic-bezier(.2,1.25,.3,1)}
.card:before{content:"";position:absolute;top:-60px;right:-50px;width:190px;height:190px;border-radius:50%;
 background:radial-gradient(circle,rgba(94,224,255,.35),transparent 70%);pointer-events:none}
.tag{display:inline-flex;align-items:center;gap:7px;font-size:11px;font-weight:600;letter-spacing:.16em;
 text-transform:uppercase;padding:6px 12px;border-radius:99px;margin-bottom:16px;
 background:rgba(140,160,255,.18);color:#b4bfff;border:1px solid rgba(160,175,255,.25)}
.dot{width:7px;height:7px;border-radius:50%;background:#5ee0ff;box-shadow:0 0 10px #5ee0ff;animation:pulse 1.4s infinite}
.q{font-size:15px;color:#c3caee;margin-bottom:14px;line-height:1.45;font-weight:500}
.a{font-weight:800;line-height:1.22;white-space:pre-wrap;word-wrap:break-word;letter-spacing:-.01em;
 background:linear-gradient(90deg,#fff 10%,#b3c1ff 55%,#7fe6ff);
 -webkit-background-clip:text;background-clip:text;-webkit-text-fill-color:transparent}
.bar{height:4px;border-radius:4px;margin-top:22px;background:rgba(255,255,255,.12);overflow:hidden}
.bar i{display:block;height:100%;width:100%;transform-origin:left;
 background:linear-gradient(90deg,#7f8cff,#5ee0ff);animation:shrink 8s linear forwards}
.hint{margin-top:11px;font-size:11px;color:#8089bd;text-align:center;letter-spacing:.04em}
@keyframes fade{from{opacity:0}to{opacity:1}}
@keyframes pop{from{opacity:0;transform:translateY(24px) scale(.92)}to{opacity:1;transform:none}}
@keyframes pulse{0%,100%{opacity:1}50%{opacity:.35}}
@keyframes shrink{from{transform:scaleX(1)}to{transform:scaleX(0)}}
</style></head><body>
<div class="card">
 <div class="tag"><span class="dot"></span>Answer</div>
 <div class="q" id="q"></div>
 <div class="a" id="a"></div>
 <div class="bar"><i id="b"></i></div>
 <div class="hint">Tap anywhere to close</div>
</div>
<script>
var Q=__Q__,A=__A__,D=__MS__;
document.getElementById('q').textContent=Q.charAt(0).toUpperCase()+Q.slice(1);
var a=document.getElementById('a');a.textContent=A;
var n=A.length;
a.style.fontSize=(n<=10?72:n<=24?48:n<=70?30:n<=160?22:18)+'px';
document.getElementById('b').style.animationDuration=D+'ms';
</script></body></html>
""".trimIndent()

    // ---------- exact arithmetic on the phone ----------
    private val calcPrefix = Regex(
        "^(?:(?:what is|what s|what's|whats|calculate|compute|solve|work out|how much is|" +
        "tell me|the result of|result of|answer of|value of|the)\\s+)+")

    private fun fmtNum(d: Double): String {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return d.toLong().toString()
        return java.math.BigDecimal(d).setScale(8, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString()
    }

    // returns the result text, or null when the sentence is not plain arithmetic
    private fun calcAnswer(text: String): String? {
        var e = text.lowercase().trim()
        e = e.replace(calcPrefix, "")
        e = e.replace(",", "").replace("×", "*").replace("÷", "/").replace("−", "-")
        e = e.replace(Regex("(\\d+(?:\\.\\d+)?)\\s*(?:%|percent) of"), "$1/100*")
        e = e.replace(Regex("square root of\\s*(\\d+(?:\\.\\d+)?)"), "sqrt($1)")
        e = e.replace(Regex("\\b(?:multiplied by|times|into)\\b"), "*")
            .replace(Regex("\\b(?:divided by|over)\\b"), "/")
            .replace(Regex("\\bplus\\b"), "+")
            .replace(Regex("\\bminus\\b"), "-")
            .replace(Regex("\\bto the power of\\b|\\bpower\\b"), "^")
            .replace(Regex("(?<=\\d)\\s*x\\s*(?=\\d)"), "*")
        e = e.trim().trimEnd('?', '.', '=', ' ').replace(" ", "")
        val chk = e.replace("sqrt", "")
        if (chk.isEmpty() || !Regex("^[0-9+\\-*/().^]+$").matches(chk)) return null
        if (!chk.any { it.isDigit() }) return null
        if (!Regex("[+\\-*/^]").containsMatchIn(e) && !e.contains("sqrt")) return null
        return try {
            val v = CalcParser(e).parse()
            if (v.isNaN() || v.isInfinite()) "That is undefined" else fmtNum(v)
        } catch (_: Exception) { null }
    }

    private class CalcParser(val s: String) {
        var i = 0
        fun parse(): Double {
            val v = expr()
            if (i != s.length) throw Exception("bad")
            return v
        }
        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'
        private fun expr(): Double {
            var v = term()
            while (peek() == '+' || peek() == '-') {
                val op = s[i++]
                val r = term()
                v = if (op == '+') v + r else v - r
            }
            return v
        }
        private fun term(): Double {
            var v = power()
            while (peek() == '*' || peek() == '/') {
                val op = s[i++]
                val r = power()
                v = if (op == '*') v * r else v / r
            }
            return v
        }
        private fun power(): Double {
            val b = unary()
            if (peek() == '^') { i++; return Math.pow(b, power()) }
            return b
        }
        private fun unary(): Double {
            if (peek() == '-') { i++; return -unary() }
            if (peek() == '+') { i++; return unary() }
            return atom()
        }
        private fun atom(): Double {
            if (s.startsWith("sqrt", i)) { i += 4; return Math.sqrt(atom()) }
            if (peek() == '(') {
                i++
                val v = expr()
                if (peek() != ')') throw Exception("bad")
                i++
                return v
            }
            val st = i
            while (peek().isDigit() || peek() == '.') i++
            if (st == i) throw Exception("bad")
            return s.substring(st, i).toDouble()
        }
    }

    // ---------- phone commands (no AI needed) ----------
    private fun launch(i: Intent): Boolean =
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
        catch (_: Exception) { false }

    private fun findApp(name: String): Pair<String, String>? {
        val n = name.replace(" ", "")
        if (n.length < 3) return null
        val pm = packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        return apps.firstOrNull { norm(it.first).replace(" ", "") == n }
            ?: apps.firstOrNull { norm(it.first).replace(" ", "").contains(n) }
    }

    private fun mediaKey(code: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun volume(dir: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        repeat(2) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        }
    }

    private fun global(action: Int, ok: String): String {
        val svc = PhoneAgentService.instance
            ?: return "Please turn on the accessibility service first"
        return if (svc.performGlobalAction(action)) ok else "That didn't work"
    }

    private fun callTarget(arg: String): String {
        val digits = arg.replace(" ", "")
        if (Regex("^\\d{5,}$").matches(digits)) {
            return if (launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + digits))))
                "Opening the dialer" else "I couldn't open the dialer"
        }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED)
            return "To call by name, allow Contacts permission for this app in Android settings"
        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                arrayOf("%" + arg + "%"), null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val num = c.getString(0)
                    val nm = c.getString(1)
                    return if (launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + num))))
                        "Dialing " + nm + ". Tap call" else "I couldn't open the dialer"
                }
            }
        } catch (_: Exception) {}
        return "I couldn't find " + arg + " in your contacts"
    }

    private fun readNotifications(): String {
        if (!NotifListener.connected) {
            launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return "Please turn on notification access for this app, then ask again"
        }
        val list = NotifListener.recent(5)
        if (list.isEmpty()) return "You have no new notifications"
        return "Latest notifications. " + list.joinToString(". ")
    }

    private fun tryCommand(q: String): String? {
        val t = norm(q)

        // ----- agent task (with safety confirmation) -----
        Regex("^(?:agent|task) (.+)$").find(t)?.let { m ->
            val goal = m.groupValues[1]
            return agentTask(goal)
        }

        // ----- memory -----
        if (Regex("^(?:forget (?:that|everything|this)|new chat|clear (?:the )?(?:chat|memory)|start over)$")
                .matches(t)) {
            synchronized(history) { history.clear() }
            return "Okay, starting fresh"
        }

        // ----- notifications, time, date, battery -----
        if (Regex("^(?:read|check|what are|any) (?:my |the )?(?:new )?(?:notifications|messages)").containsMatchIn(t))
            return readNotifications()
        if (Regex("what time|time is it|current time|tell me the time").containsMatchIn(t))
            return "It's " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        if (Regex("what (?:is )?(?:the |today s |todays )?date|what day").containsMatchIn(t))
            return "Today is " + SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())
        if (t.contains("battery")) {
            return try {
                val bm = getSystemService(BatteryManager::class.java)
                val lvl = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val bs = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val charging = bs == BatteryManager.BATTERY_STATUS_CHARGING ||
                    bs == BatteryManager.BATTERY_STATUS_FULL
                "Battery is at " + lvl + " percent" + (if (charging) " and charging" else "")
            } catch (_: Exception) { "I couldn't read the battery" }
        }

        // ----- volume -----
        Regex("(?:set )?volume (?:to )?(\\d{1,3})").find(t)?.let { m ->
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val pct = m.groupValues[1].toInt().coerceIn(0, 100)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, max * pct / 100, AudioManager.FLAG_SHOW_UI)
            return "Volume " + pct + " percent"
        }
        if (Regex("volume up|louder|increase (?:the )?volume|raise (?:the )?volume").containsMatchIn(t)) {
            volume(AudioManager.ADJUST_RAISE); return "Volume up"
        }
        if (Regex("volume down|quieter|lower (?:the )?volume|decrease (?:the )?volume").containsMatchIn(t)) {
            volume(AudioManager.ADJUST_LOWER); return "Volume down"
        }

        // ----- music / video controls -----
        if (Regex("^(?:pause|pause (?:the )?(?:music|song|video)|stop (?:the )?(?:music|song|video))$").matches(t)) {
            mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE); return "Paused"
        }
        if (Regex("^(?:play|resume|continue|play music|resume music|resume (?:the )?(?:music|video))$").matches(t)) {
            mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY); return "Playing"
        }
        if (Regex("^(?:next|skip|next (?:song|track)|skip (?:song|track))$").matches(t)) {
            mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); return "Next"
        }
        if (Regex("^(?:previous|previous (?:song|track)|last song)$").matches(t)) {
            mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); return "Previous"
        }

        // ----- phone controls (need the accessibility service) -----
        if (Regex("^(?:go )?home(?: screen)?$|^go to (?:the )?home screen$").matches(t))
            return global(AccessibilityService.GLOBAL_ACTION_HOME, "Home")
        if (Regex("^(?:go )?back$").matches(t))
            return global(AccessibilityService.GLOBAL_ACTION_BACK, "Back")
        if (Regex("recent apps|^recents$|show recents").containsMatchIn(t))
            return global(AccessibilityService.GLOBAL_ACTION_RECENTS, "Recent apps")
        if (Regex("open (?:the )?notifications|notification (?:panel|shade)|pull down notifications").containsMatchIn(t))
            return global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Notifications")
        if (t.contains("quick settings"))
            return global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "Quick settings")
        if (Regex("lock (?:the )?(?:phone|screen)").containsMatchIn(t) && Build.VERSION.SDK_INT >= 28)
            return global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "Locked")
        if (Regex("screenshot").containsMatchIn(t) && Build.VERSION.SDK_INT >= 28)
            return global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "Screenshot taken")

        // ----- settings pages -----
        if (Regex("wi ?fi").containsMatchIn(t)) {
            if (launch(Intent(Settings.ACTION_WIFI_SETTINGS))) return "Opening Wi-Fi settings"
        }
        if (t.contains("bluetooth")) {
            if (launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))) return "Opening Bluetooth settings"
        }
        if (t == "open settings" || t == "settings") {
            if (launch(Intent(Settings.ACTION_SETTINGS))) return "Opening settings"
        }

        // ----- timer and alarm -----
        Regex("timer (?:for )?(\\d+) (second|seconds|minute|minutes|min|mins|hour|hours)").find(t)?.let { m ->
            val n = m.groupValues[1].toInt()
            val unit = m.groupValues[2]
            val secs = when {
                unit.startsWith("sec") -> n
                unit.startsWith("min") -> n * 60
                else -> n * 3600
            }
            val i = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, secs)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            return if (launch(i)) "Timer set for " + n + " " + unit else "I couldn't set the timer"
        }
        if (t.contains("alarm")) {
            Regex("alarm (?:for |at )?(\\d{1,2})(?: (\\d{2}))?(?: ?(a m|p m|am|pm))?").find(t)?.let { m ->
                var h = m.groupValues[1].toInt()
                val min = m.groupValues[2].ifEmpty { "0" }.toInt()
                val ap = m.groupValues[3].replace(" ", "")
                if (ap == "pm" && h < 12) h += 12
                if (ap == "am" && h == 12) h = 0
                if (h in 0..23 && min in 0..59) {
                    val i = Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, h)
                        .putExtra(AlarmClock.EXTRA_MINUTES, min)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    return if (launch(i))
                        "Alarm set for " + h + ":" + min.toString().padStart(2, '0')
                    else "I couldn't set the alarm"
                }
            }
        }

        // ----- calling -----
        Regex("^(?:call|dial|phone) (.+)$").find(t)?.let { m -> return callTarget(m.groupValues[1]) }

        // ----- open apps, torch, search -----
        // "open youtube" opens it; "open youtube and search cats" opens it AND does the rest
        Regex("^(?:please )?(open|launch|start) (.+)$").find(t)?.let { m ->
            val verb = m.groupValues[1]
            val parts = m.groupValues[2].split(Regex(" (?:and then|and|then) "), 2)
            val name = parts[0].removePrefix("the ").removeSuffix(" app").trim()
            val rest = parts.getOrElse(1) { "" }.trim()
            val hit = findApp(name)
            if (hit != null) {
                val i = packageManager.getLaunchIntentForPackage(hit.second)
                if (i != null) {
                    if (rest.isNotEmpty()) {
                        val goal = "Open " + hit.first + " and " + rest
                        if (PhoneAgentService.instance == null) {
                            launch(i)
                            return "Opening " + hit.first +
                                ". Turn on the accessibility service so I can also " + rest
                        }
                        if (sensitiveGoalRegex.containsMatchIn(rest))
                            return "I won't operate banking or password apps"
                        if (CONFIRM_RISKY_TASKS && riskyGoalRegex.containsMatchIn(rest)) {
                            pendingGoal = goal
                            return "You want me to " + goal + ". Say yes to confirm"
                        }
                        if (!launch(i)) return "I couldn't open " + hit.first
                        handler.postDelayed({
                            if (!destroyed) startAgent("In " + hit.first + ": " + rest)
                        }, 2500)
                        return "Opening " + hit.first + " and doing it"
                    }
                    if (launch(i)) return "Opening " + hit.first
                }
            } else if (verb == "open") {
                return "I couldn't find an app called " + name
            }
        }
        if (t.contains("torch") || t.contains("flashlight")) {
            val on = !Regex("\\boff\\b").containsMatchIn(t)
            try {
                val cm = getSystemService(CameraManager::class.java)
                cm.setTorchMode(cm.cameraIdList[0], on)
                return if (on) "Torch on" else "Torch off"
            } catch (_: Exception) {}
        }
        Regex("^play (.+)$").find(t)?.let { m -> return playOnYouTube(m.groupValues[1]) }
        return null
    }

    // ---------- speech ----------
    private fun speak(text: String, id: String) {
        val t = tts
        if (t == null || !ttsReady || text.isBlank()) {
            handler.post { onSpoken(id) }
            return
        }
        try {
            val vn = s("voice_name")
            if (vn.isNotEmpty()) {
                t.voices?.firstOrNull { it.name == vn }?.let { t.setVoice(it) }
            } else {
                t.defaultVoice?.let { t.setVoice(it) }
            }
            t.setSpeechRate(Cfg.int(p(), "speech_rate") / 100f)
            t.setPitch(Cfg.int(p(), "pitch") / 100f)
            val r = t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            if (r != TextToSpeech.SUCCESS) handler.post { onSpoken(id) }
            else if (STOP_WHILE_SPEAKING) scheduleListen(2, 600)
        } catch (e: Exception) {
            handler.post { onSpoken(id) }
        }
    }

    // ---------- AI ----------
    private fun post(url: String, headers: Map<String, String>, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.doOutput = true
        for ((k, v) in headers) c.setRequestProperty(k, v)
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val txt = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw Exception("HTTP " + code + " " + txt.take(100))
        return txt
    }

    private fun needsWeb(q: String): Boolean {
        val t = norm(q)
        return listOf("news", "today", "latest", "current", "weather", "score", "price",
            "who won", "right now", "tonight", "tomorrow", "this week", "stock", "update")
            .any { t.contains(it) }
    }

    private fun historyMessages(q: String): JSONArray {
        val msgs = JSONArray()
        synchronized(history) {
            if (System.currentTimeMillis() - lastChat > HISTORY_MINUTES * 60_000L) history.clear()
            for ((u, a) in history.takeLast(HISTORY_PAIRS)) {
                msgs.put(JSONObject().put("role", "user").put("content", u))
                msgs.put(JSONObject().put("role", "assistant").put("content", a))
            }
        }
        msgs.put(JSONObject().put("role", "user").put("content", q))
        return msgs
    }

    private fun remember(question: String, answer: String) {
        synchronized(history) {
            history.add(question to answer)
            while (history.size > HISTORY_PAIRS) history.removeAt(0)
            lastChat = System.currentTimeMillis()
        }
    }

    // q = what is sent to the AI, shown = short version stored in memory
    private fun callAI(q: String, shown: String, forceWeb: Boolean = false): String {
        val key = s("key").trim()
        if (key.isEmpty()) return "Please add your API key in the app settings."
        val sys = s("system_prompt").replace("{name}", s("ai_name")) +
            " Answer in one to three short spoken sentences. No markdown, lists or emojis."
        val model = s("model").trim()
        val answer: String
        if (key.startsWith("sk-ant-")) {
            val m = if (model.isEmpty()) "claude-haiku-5-5"
                    else model.substringAfter("/").replace(".", "-")
            val headers = mapOf("x-api-key" to key,
                "anthropic-version" to "2023-06-01",
                "content-type" to "application/json")
            fun body(web: Boolean): String {
                val b = JSONObject()
                    .put("model", m)
                    .put("max_tokens", 500)
                    .put("system", sys)
                    .put("messages", historyMessages(q))
                if (web) b.put("tools", JSONArray().put(
                    JSONObject().put("type", "web_search_20250305")
                        .put("name", "web_search").put("max_uses", 3)))
                return b.toString()
            }
            val txt = try {
                post("https://api.anthropic.com/v1/messages", headers, body(WEB_SEARCH))
            } catch (e: Exception) {
                if (WEB_SEARCH) post("https://api.anthropic.com/v1/messages", headers, body(false))
                else throw e
            }
            val arr = JSONObject(txt).getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("type") == "text") sb.append(o.optString("text"))
            }
            answer = sb.toString().trim().ifEmpty { "I couldn't find an answer." }
        } else {
            var m = if (model.isEmpty()) "anthropic/claude-haiku-5.5" else model
            if (WEB_SEARCH && (forceWeb || needsWeb(shown)) && !m.endsWith(":online")) m += ":online"
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys))
            val hist = historyMessages(q)
            for (i in 0 until hist.length()) msgs.put(hist.getJSONObject(i))
            val body = JSONObject()
                .put("model", m)
                .put("max_tokens", 500)
                .put("messages", msgs)
            val txt = post("https://openrouter.ai/api/v1/chat/completions",
                mapOf("Authorization" to "Bearer " + key,
                      "Content-Type" to "application/json"),
                body.toString())
            answer = JSONObject(txt).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
        }
        remember(shown, answer)
        return answer
    }

    // ---------- overlay ----------
    private fun hideOverlay() {
        handler.removeCallbacks(hideRunnable)
        val v = overlay
        overlay = null
        if (v != null) {
            try { getSystemService(WindowManager::class.java).removeView(v) } catch (_: Exception) {}
            try { v.destroy() } catch (_: Exception) {}
        }
    }

    private fun showOverlay(text: String) {
        hideOverlay()
        if (!Settings.canDrawOverlays(this)) return
        try {
            val pr = p()
            val dur = Cfg.int(pr, "duration").coerceIn(2, 15)
            val wv = WebView(this)
            wv.setBackgroundColor(Color.TRANSPARENT)
            wv.settings.javaScriptEnabled = true
            wv.loadDataWithBaseURL("https://localhost/", html(text, dur),
                "text/html", "utf-8", null)
            val lp = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT)
            lp.gravity = Gravity.TOP or Gravity.START
            getSystemService(WindowManager::class.java).addView(wv, lp)
            overlay = wv
            handler.postDelayed(hideRunnable, dur * 1000L)
        } catch (e: Exception) {
            overlay = null
        }
    }

    // loads assets/index.html and puts in the greeting text + duration
    private fun html(text: String, durSec: Int): String {
        val raw = try {
            assets.open("index.html").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            return "<html><body style='background:transparent'></body></html>"
        }
        return raw
            .replace("q.get('text')||'Yes Aryan'", JSONObject.quote(text))
            .replace("parseInt(q.get('ms')||'6000',10)", (durSec * 1000).toString())
    }
}

// ---------- privacy helper shared by the service and the notification reader ----------
internal fun isSensitivePackage(pkg: String?): Boolean {
    if (pkg == null) return false
    val p = pkg.lowercase()
    return listOf("bank", "paytm", "phonepe", "paisa", "gpay", "bhim", "wallet", "paypal",
        "bitwarden", "1password", "lastpass", "keepass", "authenticator", "passwords",
        "upi", "finance", "creditcard", "sbi", "hdfc", "icici", "axis").any { p.contains(it) }
}

// ---------- reads recent notifications so you can say "read my notifications" ----------
// Needs: the <service> entry in AndroidManifest.xml and Notification access turned on
// for this app in Android settings. Notification text stays on the phone.
class NotifListener : NotificationListenerService() {

    companion object {
        @Volatile var connected = false
        private val items = ArrayList<String>()

        fun recent(max: Int): List<String> =
            synchronized(items) { items.takeLast(max).reversed() }
    }

    override fun onListenerConnected() { connected = true }
    override fun onListenerDisconnected() { connected = false }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.packageName == packageName) return
        val notif = n.notification ?: return
        if ((notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if ((notif.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        val ex = notif.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val label = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(n.packageName, 0)).toString()
        } catch (_: Exception) { n.packageName }
        val line = if (isSensitivePackage(n.packageName)) label + ": private notification"
                   else label + ": " + title + ". " + text
        synchronized(items) {
            items.add(line)
            while (items.size > 15) items.removeAt(0)
        }
    }
}
