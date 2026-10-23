package com.aryan.overlay

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

class AgentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("agent", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        val key = EditText(this).apply {
            hint = "OpenRouter API key (sk-or-...)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", ""))
        }
        val goal = EditText(this).apply { hint = "What should the AI do? e.g. open YouTube and play lo-fi" }
        val enable = Button(this).apply {
            text = "1. Enable accessibility service"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val start = Button(this).apply {
            text = "2. Start"
            setOnClickListener {
                val k = key.text.toString().trim()
                val g = goal.text.toString().trim()
                if (k.isEmpty() || g.isEmpty()) return@setOnClickListener toast("Enter API key and goal")
                val svc = PhoneAgentService.instance ?: return@setOnClickListener toast("Enable the accessibility service first")
                prefs.edit().putString("key", k).apply()
                PhoneAgentService.model = OpenRouterModel(k)
                svc.runGoal(g)
                toast("Agent started")
            }
        }
        val stop = Button(this).apply {
            text = "Stop"
            setOnClickListener { PhoneAgentService.instance?.stop(); toast("Stopped") }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        listOf(key, goal, enable, start, stop).forEach { root.addView(it, lp) }
        setContentView(root)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
