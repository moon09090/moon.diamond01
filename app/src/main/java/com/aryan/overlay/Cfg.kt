package com.aryan.overlay

import android.content.Context
import android.content.SharedPreferences

/** All user settings and their defaults, in one place. */
object Cfg {
    private val S = mapOf(
        "wake_words" to "moon",
        "reply" to "Yes Aryan",
        "ai_name" to "Aryan AI",
        "key" to "",
        "model" to "anthropic/claude-haiku-5.5",
        "system_prompt" to "You are {name}, a loyal, smart voice assistant. " +
            "Keep answers short (1-3 sentences), plain text, no markdown.",
        "voice_name" to "",
        "listen_lang" to "",
        "comet_color" to "#3aa0ff",
        "text_color" to "#e6f6ff",
        "font" to "Great Vibes",
        "direction" to "Down-left"
    )
    private val I = mapOf(
        "speech_rate" to 100, "pitch" to 100,
        "text_size" to 40, "text_top" to 56,
        "density" to 5, "speed" to 5, "tail" to 5,
        "blur" to 8, "dim" to 14, "duration" to 5
    )
    private val B = mapOf(
        "exact" to true, "speak_reply" to true, "ask_question" to true,
        "speak_answer" to true, "sparkles" to true
    )

    fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun str(p: SharedPreferences, k: String): String =
        p.getString(k, S[k] ?: "") ?: (S[k] ?: "")

    fun int(p: SharedPreferences, k: String): Int = p.getInt(k, I[k] ?: 0)

    fun bool(p: SharedPreferences, k: String): Boolean = p.getBoolean(k, B[k] ?: false)

    fun default(k: String): String = S[k] ?: ""
}
