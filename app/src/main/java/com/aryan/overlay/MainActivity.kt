package com.aryan.overlay

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private val savers = ArrayList<(SharedPreferences.Editor) -> Unit>()
    private var askedOverlay = false
    private var picker: TextToSpeech? = null
    private var pickerReady = false
    private var dp = 1f

    private val fonts = listOf(
        "Great Vibes", "Dancing Script", "Pacifico", "Parisienne", "Allura",
        "Lobster", "Orbitron", "Cinzel", "System cursive", "System sans"
    )
    private val directions = listOf("Down-left", "Down-right", "Straight down")

    private fun pad(v: Int) = (v * dp).toInt()

    private fun header(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, pad(26), 0, pad(2))
        })
    }

    private fun label(parent: LinearLayout, t: String): TextView {
        val tv = TextView(this).apply {
            text = t
            textSize = 13f
            setPadding(0, pad(10), 0, 0)
        }
        parent.addView(tv)
        return tv
    }

    private fun field(parent: LinearLayout, title: String, key: String,
                      multiline: Boolean = false, secret: Boolean = false) {
        label(parent, title)
        val e = EditText(this).apply {
            setText(Cfg.str(prefs, key))
            inputType = when {
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (multiline) { minLines = 3; gravity = Gravity.TOP }
        }
        parent.addView(e)
        savers.add { it.putString(key, e.text.toString().trim()) }
    }

    private fun toggle(parent: LinearLayout, title: String, key: String) {
        val sw = Switch(this).apply {
            text = title
            isChecked = Cfg.bool(prefs, key)
            setPadding(0, pad(10), 0, pad(4))
        }
        parent.addView(sw)
        savers.add { it.putBoolean(key, sw.isChecked) }
    }

    private fun slider(parent: LinearLayout, title: String, key: String,
                       min: Int, max: Int, suffix: String = "") {
        val cur = Cfg.int(prefs, key).coerceIn(min, max)
        val tv = label(parent, "$title: $cur$suffix")
        val sb = SeekBar(this).apply { this.max = max - min; progress = cur - min }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tv.text = "$title: ${p + min}$suffix"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        parent.addView(sb)
        savers.add { it.putInt(key, sb.progress + min) }
    }

    private fun choice(parent: LinearLayout, title: String, key: String, options: List<String>) {
        label(parent, title)
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        val idx = options.indexOf(Cfg.str(prefs, key))
        sp.setSelection(if (idx >= 0) idx else 0)
        parent.addView(sp)
        savers.add { it.putString(key, options[sp.selectedItemPosition]) }
    }

    private fun button(parent: LinearLayout, title: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            text = title
            setOnClickListener { action() }
        })
    }

    private fun saveAll(showToast: Boolean = true) {
        val ed = prefs.edit()
        for (s in savers) s(ed)
        ed.apply()
        if (showToast) Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dp = resources.displayMetrics.density
        prefs = Cfg.prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(18), pad(18), pad(18), pad(40))
        }
        status = TextView(this).apply { textSize = 15f }
        root.addView(status)
        button(root, "Save all settings") { saveAll() }

        header(root, "Wake word")
        field(root, "Wake words (separate several with commas)", "wake_words")
        toggle(root, "Exact match only (whole phrase must equal the wake word)", "exact")
        field(root, "Language for listening (e.g. en-US, en-IN, hi-IN). Empty = phone default",
            "listen_lang")

        header(root, "Reply when woken")
        field(root, "Reply text (shown on screen and spoken)", "reply")
        toggle(root, "Speak the reply", "speak_reply")
        toggle(root, "Then listen for my question", "ask_question")

        header(root, "Voice")
        button(root, "Choose voice") { pickVoice() }
        slider(root, "Speaking speed", "speech_rate", 50, 200, "%")
        slider(root, "Voice pitch", "pitch", 50, 200, "%")
        toggle(root, "Speak the AI answers out loud", "speak_answer")
        button(root, "Test voice") { saveAll(false); sendBroadcast(Intent(OverlayService.ACTION_VOICE)) }

        header(root, "Screen animation")
        choice(root, "Text font (needs internet the first time)", "font", fonts)
        field(root, "Text color (hex, e.g. #e6f6ff)", "text_color")
        slider(root, "Text size", "text_size", 20, 90, " px")
        slider(root, "Text height on screen (0 = top, 100 = bottom)", "text_top", 10, 90, "%")
        field(root, "Comet color (hex, e.g. #3aa0ff blue, #ffd24a gold, #ff4fa3 pink)", "comet_color")
        choice(root, "Comet direction", "direction", directions)
        slider(root, "Number of comets", "density", 1, 10)
        slider(root, "Comet speed", "speed", 1, 10)
        slider(root, "Tail length", "tail", 1, 10)
        toggle(root, "Sparkle trail", "sparkles")
        slider(root, "Background blur (needs Android 12+)", "blur", 0, 25)
        slider(root, "Background darkening", "dim", 0, 60, "%")
        slider(root, "How long it stays on screen", "duration", 2, 15, " s")
        button(root, "Test animation only") { saveAll(false); sendBroadcast(Intent(OverlayService.ACTION_SHOW)) }

        header(root, "AI")
        field(root, "API key (Anthropic sk-ant-... or OpenRouter sk-or-...)", "key", secret = true)
        field(root, "Model (OpenRouter name like anthropic/claude-haiku-5.5; " +
            "for Anthropic keys leave it or use claude-haiku-5-5)", "model")
        field(root, "AI name (used as {name} in the personality)", "ai_name")
        field(root, "Personality / instructions", "system_prompt", multiline = true)

        header(root, "Test and manage")
        button(root, "Test full flow (same as saying the wake word)") {
            saveAll(false); sendBroadcast(Intent(OverlayService.ACTION_WAKE))
        }
        button(root, "Save all settings") { saveAll() }
        button(root, "Reset everything to defaults") {
            prefs.edit().clear().apply()
            Toast.makeText(this, "Reset", Toast.LENGTH_SHORT).show()
            recreate()
        }

        setContentView(ScrollView(this).apply { addView(root) })

        picker = TextToSpeech(this) { s -> pickerReady = (s == TextToSpeech.SUCCESS) }

        val perms = ArrayList<String>()
        perms.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add("android.permission.POST_NOTIFICATIONS")
        requestPermissions(perms.toTypedArray(), 1)
    }

    private fun pickVoice() {
        val t = picker
        if (t == null || !pickerReady) {
            Toast.makeText(this, "Voice engine not ready yet, try again", Toast.LENGTH_SHORT).show()
            return
        }
        val voices: List<Voice> = try { t.voices?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }
        val sorted = voices.sortedWith(compareBy<Voice>({ it.locale.displayName }, { it.name }))
        val names = ArrayList<String>()
        names.add("Phone default")
        for (v in sorted) names.add(v.locale.displayName + " - " + v.name)
        AlertDialog.Builder(this)
            .setTitle("Choose voice")
            .setItems(names.toTypedArray()) { _, which ->
                val vn = if (which == 0) "" else sorted[which - 1].name
                prefs.edit().putString("voice_name", vn).apply()
                Toast.makeText(this, if (vn.isEmpty()) "Default voice" else "Voice saved",
                    Toast.LENGTH_SHORT).show()
                if (which > 0) {
                    try {
                        t.setVoice(sorted[which - 1])
                        t.setSpeechRate(Cfg.int(prefs, "speech_rate") / 100f)
                        t.setPitch(Cfg.int(prefs, "pitch") / 100f)
                        t.speak(Cfg.str(prefs, "reply"), TextToSpeech.QUEUE_FLUSH, null, "preview")
                    } catch (_: Exception) {}
                }
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val overlayOk = Settings.canDrawOverlays(this)

        if (!overlayOk && !askedOverlay) {
            askedOverlay = true
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"))
            )
        }

        val missing = ArrayList<String>()
        if (!micOk) missing.add("Microphone permission")
        if (!overlayOk) missing.add("Display over other apps")

        if (missing.isEmpty()) {
            status.text = "Ready. Say your wake word anywhere.\n" +
                "Pull down the notification bar to see what it heard."
            startForegroundService(Intent(this, OverlayService::class.java))
        } else {
            status.text = "Please allow: " + missing.joinToString(", ") +
                "\nThen come back to this app."
        }
    }

    override fun onDestroy() {
        try { picker?.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }
}
