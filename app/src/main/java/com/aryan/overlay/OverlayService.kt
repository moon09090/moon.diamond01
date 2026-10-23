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
import android.view.accessibility.AccessibilityNodeInfo
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

        // Offline wake-word model. It is downloaded ONCE (about 40 MB) the first time
        // the app runs, then stored on the phone. Other options from
        // https://alphacephei.com/vosk/models  e.g. "vosk-model-small-en-in-0.4"
        // (Indian English). Change only the name; the URL is built from it.
        private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/" + MODEL_NAME + ".zip"

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
        if (wakeThread?.isAlive == true) { scheduleListen(mode, 200); return }
        val model = voskModel
        if (model == null) {
            ensureModel()
            scheduleListen(mode, 3000)
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

    private val stopWords = setOf("stop", "stop it", "stop talking", "stop speaking",
        "be quiet", "cancel", "cancel that", "enough", "never mind")

    private fun isStopWord(text: String) = norm(text) in stopWords

    // stops its voice and any running agent task
    private fun interrupt() {
        try { tts?.stop() } catch (_: Exception) {}
        cancelAgent()
        pendingGoal = null
        handler.removeCallbacks(restartRunnable)
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

    // runs on the main thread for every finished phrase the engine hears
    private fun onWakeText(text: String) {
        if (!wakeRun) return
        if (mode == 2) {                      // while it speaks: only "stop" counts
            if (isStopWord(text)) interrupt()
            return
        }
        if (mode != 0) return
        heard(text)
        if (isWake(text)) onWake()            // EXACT match only
        else if (isStopWord(text)) interrupt()
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
            "answer" -> scheduleListen(afterAnswerMode, 300)
            "test" -> scheduleListen(0, 300)
            else -> {}
        }
    }

    private val yesWords = setOf("yes", "yeah", "yep", "yup", "sure", "ok", "okay", "confirm",
        "do it", "go ahead", "yes please", "correct", "proceed", "affirmative")

    private fun askAI(q: String) {
        // 1) waiting for a yes/no about a risky agent task
        val pg = pendingGoal
        if (pg != null) {
            pendingGoal = null
            val ans = if (norm(q) in yesWords) startAgent(pg) else "Okay, cancelled"
            onAnswer(q, ans)
            return
        }
        // 2) questions about what is on the screen
        if (isScreenQuestion(q)) { askAboutScreen(q); return }
        // 3) instant phone commands, no AI needed
        val local = tryCommand(q)
        if (local != null) {
            onAnswer(q, local, pendingGoal != null)
            return
        }
        // 4) normal AI answer
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

    private fun startAgent(goal: String): String {
        val svc = PhoneAgentService.instance
            ?: return "Please turn on the accessibility service for agent tasks"
        val pkg = try { svc.rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
        if (sensitiveGoalRegex.containsMatchIn(norm(goal)) || sensitivePkg(pkg))
            return "I won't operate banking or password apps"
        if (PhoneAgentService.model == null)
            PhoneAgentService.model = OpenRouterModel(s("key").trim())
        svc.runGoal(goal)
        return "Okay, working on it"
    }

    // ---------- screen reading ----------
    private fun isScreenQuestion(q: String): Boolean {
        val t = norm(q)
        return listOf("on my screen", "on the screen", "this screen", "this page",
            "read my screen", "what am i looking at", "explain this", "summarize this")
            .any { t.contains(it) }
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
            txt == null -> onAnswer(q, "Turn on the accessibility service so I can see the screen")
            txt == SCREEN_PRIVATE -> onAnswer(q, "That looks like a private app, so I won't read it")
            txt.isBlank() -> onAnswer(q, "I can't find any text on this screen")
            else -> {
                status("Reading screen...", "You: " + q)
                Thread {
                    val ans = try {
                        callAI("Here is the text currently visible on my phone screen:\n" + txt +
                            "\n\nMy request: " + q, q)
                    } catch (e: Exception) {
                        "Sorry, the AI failed: " + (e.message ?: "unknown").take(60)
                    }
                    handler.post { onAnswer(q, ans, FOLLOW_UP) }
                }.start()
            }
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
            if (PhoneAgentService.instance == null)
                return "Please turn on the accessibility service for agent tasks"
            if (sensitiveGoalRegex.containsMatchIn(goal))
                return "I won't operate banking or password apps"
            if (CONFIRM_RISKY_TASKS && riskyGoalRegex.containsMatchIn(goal)) {
                pendingGoal = goal
                return "You want me to " + goal + ". Say yes to confirm"
            }
            return startAgent(goal)
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
    private fun callAI(q: String, shown: String): String {
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
                        .put("name", "web_search").put("max_uses", 2)))
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
            if (WEB_SEARCH && needsWeb(shown) && !m.endsWith(":online")) m += ":online"
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
