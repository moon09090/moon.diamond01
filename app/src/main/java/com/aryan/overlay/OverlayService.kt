package com.aryan.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Always-on helper:
 *  1. Listens for the exact word "Moon" (no popup).
 *  2. Draws comets + calligraphy "Yes Aryan" over the live home screen
 *     (transparent, touch-through, lightly blurred) and says "Yes Aryan".
 *  3. Listens for your question, asks OpenRouter, speaks the answer.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_SHOW = "com.aryan.overlay.SHOW"
        const val ACTION_WAKE = "com.aryan.overlay.WAKE"
        private const val CHANNEL = "aryan_overlay"
        private const val BLUR_RADIUS = 8      // "very low" blur. 0 = off
        private const val WAKE_WORD = "moon"   // exact match only
        private const val REPLY = "Yes Aryan"
        private const val SHOW_MS = 5500
        private const val SYSTEM_PROMPT =
            "You are Aryan AI, a loyal, smart voice assistant. " +
            "Keep answers short (1-3 sentences), plain text, no markdown."
    }

    private enum class Mode { WAKE, COMMAND, BUSY }

    private lateinit var wm: WindowManager
    private lateinit var nm: NotificationManager
    private lateinit var notif: Notification.Builder
    private val handler = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var mode = Mode.BUSY
    private val history = ArrayList<JSONObject>()
    private val mutedStreams = ArrayList<Int>()
    private val hideRunnable = Runnable { hideOverlay() }

    // ---------- broadcasts (test buttons) ----------
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SHOW -> showOverlay(
                    intent.getStringExtra("text") ?: REPLY,
                    intent.getIntExtra("ms", SHOW_MS)
                )
                ACTION_WAKE -> onWake()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Aryan Overlay", NotificationManager.IMPORTANCE_LOW)
        )
        notif = Notification.Builder(this, CHANNEL)
            .setContentTitle("Say \"Moon\"")
            .setContentText("Starting...")
            .setSmallIcon(android.R.drawable.star_on)
            .setOngoing(true)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notif.build())
        }

        val filter = IntentFilter()
        filter.addAction(ACTION_SHOW)
        filter.addAction(ACTION_WAKE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        tts = TextToSpeech(this) { s -> ttsReady = (s == TextToSpeech.SUCCESS) }
        startListening(Mode.WAKE, 500)
    }

    // ---------- helpers ----------
    private fun status(text: String) {
        notif.setContentText(text)
        nm.notify(1, notif.build())
    }

    private fun isWake(text: String?): Boolean {
        val cleaned = (text ?: "").lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
        return cleaned == WAKE_WORD
    }

    private fun muteBeep() {
        if (mutedStreams.isNotEmpty()) return
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            for (s in intArrayOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)) {
                if (!am.isStreamMute(s)) {
                    try {
                        am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0)
                        mutedStreams.add(s)
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    private fun unmuteBeep() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            for (s in mutedStreams) {
                try { am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        mutedStreams.clear()
    }

    private fun speak(text: String, onDone: () -> Unit) {
        val t = tts
        if (t == null || !ttsReady) {
            handler.postDelayed({ onDone() }, 800)
            return
        }
        val id = "u" + System.currentTimeMillis()
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onError(utteranceId: String?) { handler.post { onDone() } }
            override fun onDone(utteranceId: String?) { handler.post { onDone() } }
        })
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    // ---------- speech recognition ----------
    private fun destroyRecognizer() {
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    private fun startListening(newMode: Mode, delayMs: Long = 0) {
        handler.postDelayed({
            mode = newMode
            destroyRecognizer()
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                status("Speech recognition is not available (install/enable Google app)")
                return@postDelayed
            }
            if (newMode == Mode.WAKE) {
                muteBeep()
                status("Listening for \"Moon\"...")
            } else {
                unmuteBeep()
                status("Listening for your question...")
            }
            val r = SpeechRecognizer.createSpeechRecognizer(this)
            r.setRecognitionListener(listener)
            recognizer = r
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            r.startListening(i)
        }, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            when (mode) {
                Mode.WAKE -> {
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        status("Microphone permission missing - open the app")
                        return
                    }
                    val wait = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200L else 300L
                    startListening(Mode.WAKE, wait)
                }
                Mode.COMMAND -> {
                    mode = Mode.BUSY
                    speak("Sorry, I did not hear that.") { startListening(Mode.WAKE, 300) }
                }
                Mode.BUSY -> {}
            }
        }

        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val first = list?.firstOrNull()
            when (mode) {
                Mode.WAKE -> {
                    status("Heard: " + (first ?: "-"))
                    if (isWake(first)) onWake() else startListening(Mode.WAKE, 200)
                }
                Mode.COMMAND -> {
                    val q = first?.trim().orEmpty()
                    if (q.isEmpty()) {
                        mode = Mode.BUSY
                        speak("Sorry, I did not hear that.") { startListening(Mode.WAKE, 300) }
                    } else {
                        askAi(q)
                    }
                }
                Mode.BUSY -> {}
            }
        }
    }

    // ---------- wake flow ----------
    private fun onWake() {
        mode = Mode.BUSY
        destroyRecognizer()
        unmuteBeep()
        handler.post { showOverlay(REPLY, SHOW_MS) }
        speak(REPLY) { startListening(Mode.COMMAND, 200) }
    }

    private fun askAi(question: String) {
        mode = Mode.BUSY
        status("You: $question")
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val key = prefs.getString("key", "") ?: ""
        val model = prefs.getString("model", "openai/gpt-4o-mini") ?: "openai/gpt-4o-mini"
        if (key.isBlank()) {
            speak("Please open the app and save your OpenRouter key.") { startListening(Mode.WAKE, 300) }
            return
        }
        history.add(JSONObject().put("role", "user").put("content", question))
        Thread {
            val answer = try {
                val msgs = JSONArray()
                msgs.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                for (m in history.takeLast(8)) msgs.put(m)
                val body = JSONObject().put("model", model).put("messages", msgs)

                val conn = URL("https://openrouter.ai/api/v1/chat/completions")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer $key")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                val resp = stream.bufferedReader().readText()
                val j = JSONObject(resp)
                if (!j.has("choices")) throw RuntimeException(resp)
                j.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()
            } catch (e: Exception) {
                "Sorry, I could not get an answer from the AI."
            }
            handler.post {
                history.add(JSONObject().put("role", "assistant").put("content", answer))
                status("AI: " + answer.take(80))
                speak(answer.take(3500)) { startListening(Mode.WAKE, 300) }
            }
        }.start()
    }

    // ---------- overlay ----------
    @SuppressLint("SetJavaScriptEnabled")
    private fun showOverlay(text: String, ms: Int) {
        hideOverlay()
        val w = WebView(this)
        w.setBackgroundColor(Color.TRANSPARENT)
        w.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        w.settings.javaScriptEnabled = true
        w.isClickable = false
        w.isFocusable = false

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= 31) {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            lp.blurBehindRadius = BLUR_RADIUS
        }

        w.loadUrl("file:///android_asset/index.html?text=" + Uri.encode(text) + "&ms=" + ms)
        try {
            wm.addView(w, lp)
            web = w
            handler.postDelayed(hideRunnable, ms.toLong())
        } catch (e: Exception) {
            w.destroy()
            status("Overlay blocked - allow Display over other apps")
        }
    }

    private fun hideOverlay() {
        handler.removeCallbacks(hideRunnable)
        web?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
            it.destroy()
        }
        web = null
    }

    override fun onDestroy() {
        hideOverlay()
        destroyRecognizer()
        unmuteBeep()
        try { tts?.shutdown() } catch (_: Exception) {}
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
