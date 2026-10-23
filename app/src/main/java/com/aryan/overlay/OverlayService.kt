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
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class OverlayService : Service() {

    companion object {
        const val ACTION_SHOW = "com.aryan.overlay.SHOW"
        const val ACTION_WAKE = "com.aryan.overlay.WAKE"
        const val ACTION_VOICE = "com.aryan.overlay.VOICE"
        private const val CH = "overlay_status"
        private const val NID = 1
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
                ACTION_VOICE -> { stopRecognizer(); speak(s("reply"), "test") }
                else -> {}
            }
        }
    }

    // ---------- lifecycle ----------
    override fun onCreate() {
        super.onCreate()
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
        val title = if (mode == 0) "Listening for wake word" else "Listening for your question"
        var text = "Heard: " + lastHeard
        if (lastAnswer.isNotEmpty()) text += "\n\n" + lastAnswer
        status(title, text)
    }

    // ---------- listening ----------
    private fun scheduleListen(m: Int, delay: Long) {
        handler.removeCallbacks(restartRunnable)
        pendingMode = m
        handler.postDelayed(restartRunnable, delay)
    }

    private fun stopRecognizer() {
        listening = false
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
        stopRecognizer()
        mode = m
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            status("Microphone permission needed", "Open the app and allow the microphone.")
            scheduleListen(m, 3000)
            return
        }
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
            if (mode == 0 && isWake(t)) {
                listening = false
                handler.post { onWake() }
            }
        }

        override fun onResults(b: Bundle?) {
            if (!listening) return
            listening = false
            val list = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val top = list?.firstOrNull()
            if (top != null) heard(top)
            if (mode == 0) {
                if (list != null && list.any { isWake(it) }) onWake()
                else scheduleListen(0, 150)
            } else {
                val q = top?.trim().orEmpty()
                if (q.isEmpty()) scheduleListen(0, 200) else askAI(q)
            }
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
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleListen(0, 150)
                else -> scheduleListen(0, 800)
            }
        }
    }

    private fun norm(t: String): String =
        t.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ").trim()

    private fun isWake(text: String): Boolean {
        val t = norm(text)
        if (t.isEmpty()) return false
        val exact = Cfg.bool(p(), "exact")
        for (w in s("wake_words").split(",")) {
            val ww = norm(w)
            if (ww.isEmpty()) continue
            if (exact) {
                if (t == ww) return true
            } else if ((" " + t + " ").contains(" " + ww + " ")) {
                return true
            }
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
            val dim = Cfg.int(pr, "dim")
            if (dim > 0) {
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
                lp.dimAmount = dim / 100f
            }
            val blur = Cfg.int(pr, "blur")
            if (Build.VERSION.SDK_INT >= 31 && blur > 0) {
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                lp.blurBehindRadius = (blur * resources.displayMetrics.density).toInt()
            }
            getSystemService(WindowManager::class.java).addView(wv, lp)
            overlay = wv
            handler.postDelayed(hideRunnable, dur * 1000L + 700L)
        } catch (e: Exception) {
            status("Overlay problem", e.message ?: "unknown")
        }
    }

    private fun html(text: String, dur: Int): String {
        val pr = p()
        fun col(k: String): String {
            val v = Cfg.str(pr, k).trim()
            return if (Regex("^#[0-9a-fA-F]{6}\$").matches(v)) v else Cfg.default(k)
        }
        val cc = col("comet_color")
        val tc = col("text_color")
        val c = Color.parseColor(cc)
        val font = Cfg.str(pr, "font")
        val css: String
        val link: String
        when (font) {
            "System cursive" -> { css = "cursive"; link = "" }
            "System sans" -> { css = "sans-serif"; link = "" }
            else -> {
                css = "'" + font + "', cursive"
                link = "<link href=\"https://fonts.googleapis.com/css2?family=" +
                    font.replace(" ", "+") + "&display=swap\" rel=\"stylesheet\">"
            }
        }
        val dir = listOf("Down-left", "Down-right", "Straight down")
            .indexOf(Cfg.str(pr, "direction")).coerceAtLeast(0)

        val page = """<!doctype html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
@@LINK@@
<style>
html,body{margin:0;height:100%;overflow:hidden;background:transparent;transition:opacity .6s}
canvas{position:fixed;left:0;top:0;width:100%;height:100%}
#t{position:fixed;left:0;right:0;top:@@TOP@@%;transform:translateY(-50%);text-align:center;font-family:@@FONT@@;font-size:@@SIZE@@px;color:@@TCOL@@;text-shadow:0 0 18px @@CCOL@@,0 0 4px #000;padding:0 20px;opacity:0;animation:fin .8s ease forwards}
@keyframes fin{from{opacity:0;transform:translateY(-40%)}to{opacity:1;transform:translateY(-50%)}}
</style></head><body><canvas id="c"></canvas><div id="t"></div>
<script>
document.getElementById('t').textContent=@@JSTEXT@@;
var C=document.getElementById('c'),X=C.getContext('2d');
function rs(){C.width=innerWidth;C.height=innerHeight}rs();addEventListener('resize',rs);
var DIR=@@DIR@@,SP=@@SPEED@@,TL=@@TAIL@@,DN=@@DENS@@,SPK=@@SPARK@@,COL=[@@R@@,@@G@@,@@B@@];
function rgba(a){return 'rgba('+COL[0]+','+COL[1]+','+COL[2]+','+a+')'}
var cm=[],pt=[];
function spawn(){
  var w=C.width,h=C.height,px,py;
  if(DIR==2){px=Math.random()*w;py=-20}
  else if(DIR==0){if(Math.random()<0.6){px=Math.random()*w*1.2;py=-20}else{px=w+20;py=Math.random()*h*0.5}}
  else{if(Math.random()<0.6){px=-w*0.2+Math.random()*w*1.2;py=-20}else{px=-20;py=Math.random()*h*0.5}}
  var s=(5+SP*2.5)*(0.7+Math.random()*0.6);
  var vx=DIR==0?-0.6:(DIR==1?0.6:0),vy=1,n=Math.sqrt(vx*vx+vy*vy);
  cm.push({x:px,y:py,vx:vx/n*s,vy:vy/n*s,l:TL*28+40,w:2+Math.random()*2});
}
for(var k=0;k<DN;k++)spawn();
function frame(){
  X.clearRect(0,0,C.width,C.height);
  if(Math.random()<DN*0.025)spawn();
  for(var i=cm.length-1;i>=0;i--){
    var m=cm[i];m.x+=m.vx;m.y+=m.vy;
    var sp=Math.sqrt(m.vx*m.vx+m.vy*m.vy),tx=m.x-m.vx/sp*m.l,ty=m.y-m.vy/sp*m.l;
    var g=X.createLinearGradient(m.x,m.y,tx,ty);g.addColorStop(0,rgba(1));g.addColorStop(1,rgba(0));
    X.strokeStyle=g;X.lineWidth=m.w;X.lineCap='round';X.beginPath();X.moveTo(m.x,m.y);X.lineTo(tx,ty);X.stroke();
    X.shadowColor=rgba(1);X.shadowBlur=14;X.fillStyle='#ffffff';X.beginPath();X.arc(m.x,m.y,m.w*0.9,0,6.283);X.fill();X.shadowBlur=0;
    if(SPK&&Math.random()<0.6)pt.push({x:m.x,y:m.y,vx:(Math.random()-0.5)*1.5,vy:(Math.random()-0.5)*1.5,a:1});
    if(m.y>C.height+400||m.x<-400||m.x>C.width+400)cm.splice(i,1);
  }
  for(var j=pt.length-1;j>=0;j--){
    var q=pt[j];q.x+=q.vx;q.y+=q.vy;q.a-=0.02;
    if(q.a<=0){pt.splice(j,1);continue}
    X.fillStyle=rgba(q.a);X.beginPath();X.arc(q.x,q.y,1.6,0,6.283);X.fill();
  }
  requestAnimationFrame(frame);
}
frame();
setTimeout(function(){document.body.style.opacity=0},(@@DUR@@-0.6)*1000);
</script></body></html>"""

        return page
            .replace("@@LINK@@", link)
            .replace("@@TOP@@", Cfg.int(pr, "text_top").toString())
            .replace("@@FONT@@", css)
            .replace("@@SIZE@@", Cfg.int(pr, "text_size").toString())
            .replace("@@TCOL@@", tc)
            .replace("@@CCOL@@", cc)
            .replace("@@JSTEXT@@", JSONObject.quote(text))
            .replace("@@DIR@@", dir.toString())
            .replace("@@SPEED@@", Cfg.int(pr, "speed").toString())
            .replace("@@TAIL@@", Cfg.int(pr, "tail").toString())
            .replace("@@DENS@@", Cfg.int(pr, "density").toString())
            .replace("@@SPARK@@", if (Cfg.bool(pr, "sparkles")) "1" else "0")
            .replace("@@R@@", Color.red(c).toString())
            .replace("@@G@@", Color.green(c).toString())
            .replace("@@B@@", Color.blue(c).toString())
            .replace("@@DUR@@", dur.toString())
    }
}
