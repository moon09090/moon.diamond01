package com.aryan.overlay

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Sends the screen text to OpenRouter (model "openrouter/auto") and returns the next action. */
class OpenRouterModel(
    private val apiKey: String,
    private val modelName: String = "openrouter/auto"
) : EdgeModel {

    private val system = """
You control an Android phone. Each turn you get the user's GOAL, previous steps and the CURRENT SCREEN.
Reply with ONE JSON object only, no other text. Allowed actions:
{"action":"open_app","package":"com.google.android.youtube"}
{"action":"tap_text","text":"Search"}
{"action":"tap","x":540,"y":1200}
{"action":"type","text":"lo-fi music"}
{"action":"scroll_down"} {"action":"scroll_up"}
{"action":"back"} {"action":"home"} {"action":"wait"}
{"action":"done","message":"short result"}
Rules: take one step at a time. Say done when the goal is complete or impossible.
Never enter passwords, payment details or make purchases. If asked to, reply done.
""".trimIndent()

    override suspend fun decide(
        goal: String, screenText: String, screenshot: Bitmap?, history: List<String>
    ): AgentAction = withContext(Dispatchers.IO) {
        val user = "GOAL: $goal\n\nPREVIOUS STEPS:\n${history.takeLast(10).joinToString("\n")}" +
                "\n\nCURRENT SCREEN:\n${screenText.take(6000)}"
        val body = JSONObject()
            .put("model", modelName)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))

        val conn = (URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 60000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().readText()
        if (code !in 200..299) throw Exception("OpenRouter $code: ${text.take(200)}")

        val content = JSONObject(text).getJSONArray("choices")
            .getJSONObject(0).getJSONObject("message").getString("content")
        parse(content)
    }

    private fun parse(content: String): AgentAction {
        val s = content.indexOf('{'); val e = content.lastIndexOf('}')
        if (s < 0 || e <= s) return AgentAction.Done("Model gave no action")
        val j = JSONObject(content.substring(s, e + 1))
        return when (j.optString("action")) {
            "open_app" -> AgentAction.OpenApp(j.getString("package"))
            "tap_text" -> AgentAction.TapText(j.getString("text"))
            "tap" -> AgentAction.Tap(j.getDouble("x").toFloat(), j.getDouble("y").toFloat())
            "type" -> AgentAction.TypeText(j.getString("text"))
            "scroll_down" -> AgentAction.Scroll(true)
            "scroll_up" -> AgentAction.Scroll(false)
            "back" -> AgentAction.Back
            "home" -> AgentAction.Home
            "wait" -> AgentAction.Wait(2000)
            "done" -> AgentAction.Done(j.optString("message", "Done"))
            else -> AgentAction.Done("Unknown action from model")
        }
    }
}
