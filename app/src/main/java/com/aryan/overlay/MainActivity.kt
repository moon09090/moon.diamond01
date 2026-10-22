package com.aryan.overlay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var keyBox: EditText
    private lateinit var modelBox: EditText
    private var askedOverlay = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)

        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply { textSize = 16f; setPadding(0, 0, 0, pad) }

        keyBox = EditText(this).apply {
            hint = "OpenRouter API key (sk-or-...)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", ""))
        }
        modelBox = EditText(this).apply {
            hint = "Model"
            setText(prefs.getString("model", "openai/gpt-4o-mini"))
        }
        val save = Button(this).apply {
            text = "Save key and model"
            setOnClickListener {
                prefs.edit()
                    .putString("key", keyBox.text.toString().trim())
                    .putString("model", modelBox.text.toString().trim())
                    .apply()
                Toast.makeText(this@MainActivity, "Saved", Toast.LENGTH_SHORT).show()
            }
        }
        val testOverlay = Button(this).apply {
            text = "Test overlay only"
            setOnClickListener {
                sendBroadcast(Intent(OverlayService.ACTION_SHOW)
                    .putExtra("text", "Yes Aryan").putExtra("ms", 5000))
            }
        }
        val testFlow = Button(this).apply {
            text = "Test full flow (same as saying Moon)"
            setOnClickListener { sendBroadcast(Intent(OverlayService.ACTION_WAKE)) }
        }
        root.addView(status)
        root.addView(keyBox)
        root.addView(modelBox)
        root.addView(save)
        root.addView(testOverlay)
        root.addView(testFlow)
        setContentView(ScrollView(this).apply { addView(root) })

        val perms = ArrayList<String>()
        perms.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add("android.permission.POST_NOTIFICATIONS")
        requestPermissions(perms.toTypedArray(), 1)
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
            status.text = "Ready. Say \"Moon\" (exactly) anywhere.\n" +
                "Pull down the notification bar to see what it heard."
            startForegroundService(Intent(this, OverlayService::class.java))
        } else {
            status.text = "Please allow: " + missing.joinToString(", ") +
                "\nThen come back to this app."
        }
    }
}
