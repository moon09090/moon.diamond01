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

        // Offline wake-word model. It is downloaded ONCE (about 40 MB) the first time
        // the app runs, then stored on the phone. Other options from
        // https://alphacephei.com/vosk/models  e.g. "vosk-model-small-en-in-0.4"
        // (Indian English). Change only the name; the URL is built from it.
        private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/" + MODEL_NAME + ".zip"
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
        val title = if (mode == 0) "Waiting for wake word" else "Listening for your question"
        var text = "Heard: " + lastHeard
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

    private fun modelDir() = File(filesDir, "vosk/" + MODEL_NAME)

    private fun ensureModel() {
        if (voskModel != null || modelLoading || destroyed) return
        modelLoading = true
        Thread {
            try {
                val dir = modelDir()
                val ready = File(dir, ".ready")
                if (!ready.exists()) {
                    dir.deleteRecursively()
                    handler.post { status("Wake word setup", "Downloading offline voice model (one time)...") }
                    downloadAndUnzip(dir)
                    ready.writeText("ok")
                }
                handler.post { status("Wake word setup", "Loading voice model...") }
                voskModel = Model(dir.absolutePath)
                handler.post {
                    if (!destroyed) {
                        refreshStatus()
                        if (mode == 0) scheduleListen(0, 100)
                    }
                }
            } catch (t: Throwable) {
                try { modelDir().deleteRecursively() } catch (_: Throwable) {}
                handler.post {
                    status("Wake word model problem",
                        "Check internet and storage. Retrying. " + (t.message ?: "").take(80))
                }
            } finally {
                modelLoading = false
            }
        }.start()
    }

    private fun downloadAndUnzip(dest: File) {
        val tmp = File(cacheDir, "vosk_model.zip")
        val c = URL(MODEL_URL).openConnection() as HttpURLConnection
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

    private fun startWake() {
        if (destroyed || wakeRun) return
        if (wakeThread?.isAlive == true) { scheduleListen(0, 200); return }
        val model = voskModel
        if (model == null) {
            ensureModel()
            scheduleListen(0, 3000)
            return
        }
        wakeRun = true
        val t = Thread {
            var rec: AudioRecord? = null
            var vr: Recognizer? = null
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
                val v = Recognizer(model, rate.toFloat())
                vr = v
                r.startRecording()
                handler.post { if (wakeRun) refreshStatus() }
                val buf = ShortArray(2048)
                while (wakeRun) {
                    val n = r.read(buf, 0, buf.size)
                    if (n < 0) throw Exception("microphone read error")
                    if (n == 0) { Thread.sleep(10); continue }
                    if (v.acceptWaveForm(buf, n)) {
                        val text = JSONObject(v.result).optString("text").trim()
                        if (text.isNotEmpty()) handler.post { onWakeText(text) }
                    }
                }
            } catch (e: Throwable) {
                failed = true
            } finally {
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

    // runs on the main thread for every finished phrase the wake engine hears
    private fun onWakeText(text: String) {
        if (!wakeRun || mode != 0) return
        heard(text)
        if (isWake(text)) onWake()      // EXACT match only
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
        if (m == 0 && wakeRun && recognizer == null) return   // already waiting silently
        stopRecognizer()
        mode = m
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            status("Microphone permission needed", "Open the app and allow the microphone.")
            scheduleListen(m, 3000)
            return
        }
        if (m == 0) {
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

    // EXACT match only: the whole sentence heard must equal one of the wake words
    private fun isWake(text: String): Boolean {
        val t = norm(text)
        if (t.isEmpty()) return false
        for (w in s("wake_words").split(",")) {
            val ww = norm(w)
            if (ww.isEmpty()) continue
            if (t == ww) return true
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
            "answer", "test" -> scheduleListen(0, 300)
            else -> {}
        }
    }

    private fun askAI(q: String) {
        val local = tryCommand(q)          // instant phone commands, no AI needed
        if (local != null) {
            onAnswer(q, local)
            return
        }
        status("Thinking...", "You: " + q)
        Thread {
            val ans = try {
                callAI(q)
            } catch (e: Exception) {
                "Sorry, the AI failed: " + (e.message ?: "unknown").take(60)
            }
            handler.post { onAnswer(q, ans) }
        }.start()
    }

    private fun onAnswer(q: String, a: String) {
        lastAnswer = "You: " + q + "\nAI: " + a
        status("Answered", lastAnswer)
        if (Cfg.bool(p(), "speak_answer")) speak(a, "answer") else scheduleListen(0, 300)
    }

    // ---------- phone commands (open apps, torch, search) ----------
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

    private fun tryCommand(q: String): String? {
        val t = norm(q)
        Regex("^(?:agent|task) (.+)$").find(t)?.let { m ->
            val svc = PhoneAgentService.instance
            if (svc != null) {
                if (PhoneAgentService.model == null)
                    PhoneAgentService.model = OpenRouterModel(s("key").trim())
                svc.runGoal(m.groupValues[1])
                return "Okay, working on it"
            }
        }
        Regex("^(?:please )?(?:open|launch|start) (.+)$").find(t)?.let { m ->
            val name = m.groupValues[1].removePrefix("the ").removeSuffix(" app").trim()
            val hit = findApp(name)
            if (hit != null) {
                val i = packageManager.getLaunchIntentForPackage(hit.second)
                if (i != null && launch(i)) return "Opening " + hit.first
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
        Regex("^play (.+)$").find(t)?.let { m ->
            val u = "https://www.youtube.com/results?search_query=" + Uri.encode(m.groupValues[1])
            if (launch(Intent(Intent.ACTION_VIEW, Uri.parse(u)))) return "Searching YouTube"
        }
        Regex("^(?:search|google) (?:for )?(.+)$").find(t)?.let { m ->
            val u = "https://www.google.com/search?q=" + Uri.encode(m.groupValues[1])
            if (launch(Intent(Intent.ACTION_VIEW, Uri.parse(u)))) return "Searching"
        }
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

    private fun callAI(q: String): String {
        val key = s("key").trim()
        if (key.isEmpty()) return "Please add your API key in the app settings."
        val sys = s("system_prompt").replace("{name}", s("ai_name"))
        val model = s("model").trim()
        if (key.startsWith("sk-ant-")) {
            val m = if (model.isEmpty()) "claude-haiku-5-5"
                    else model.substringAfter("/").replace(".", "-")
            val body = JSONObject()
                .put("model", m)
                .put("max_tokens", 300)
                .put("system", sys)
                .put("messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", q)))
            val txt = post("https://api.anthropic.com/v1/messages",
                mapOf("x-api-key" to key,
                      "anthropic-version" to "2023-06-01",
                      "content-type" to "application/json"),
                body.toString())
            return JSONObject(txt).getJSONArray("content")
                .getJSONObject(0).getString("text").trim()
        } else {
            val m = if (model.isEmpty()) "anthropic/claude-haiku-5.5" else model
            val msgs = JSONArray()
                .put(JSONObject().put("role", "system").put("content", sys))
                .put(JSONObject().put("role", "user").put("content", q))
            val body = JSONObject()
                .put("model", m)
                .put("max_tokens", 300)
                .put("messages", msgs)
            val txt = post("https://openrouter.ai/api/v1/chat/completions",
                mapOf("Authorization" to "Bearer " + key,
                      "Content-Type" to "application/json"),
                body.toString())
            return JSONObject(txt).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
        }
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
