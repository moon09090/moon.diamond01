package com.aryan.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/* ---------- What the AI can do ---------- */
sealed class AgentAction {
    data class Tap(val x: Float, val y: Float) : AgentAction()
    data class TapText(val text: String) : AgentAction()
    data class TypeText(val text: String) : AgentAction()
    data class Swipe(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val ms: Long = 300) : AgentAction()
    data class Scroll(val down: Boolean) : AgentAction()
    data class OpenApp(val packageName: String) : AgentAction()
    data class Wait(val ms: Long) : AgentAction()
    object Back : AgentAction()
    object Home : AgentAction()
    object Recents : AgentAction()
    data class Done(val message: String) : AgentAction()
}

/* ---------- Plug YOUR edge model in here ---------- */
interface EdgeModel {
    /**
     * goal       = what the user said ("open YouTube and play lo-fi music")
     * screenText = text description of the current screen (UI tree)
     * screenshot = current screenshot (may be null on Android < 11)
     * history    = previous steps taken
     * Return the next single action.
     */
    suspend fun decide(
        goal: String,
        screenText: String,
        screenshot: Bitmap?,
        history: List<String>
    ): AgentAction
}

class PhoneAgentService : AccessibilityService() {

    companion object {
        @Volatile var instance: PhoneAgentService? = null
        /** Set this from your app once your model is loaded: PhoneAgentService.model = MyModel() */
        @Volatile var model: EdgeModel? = null
        private const val MAX_STEPS = 40
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val shotExecutor = Executors.newSingleThreadExecutor()
    private val logFile by lazy { File(filesDir, "agent_log.jsonl") }  // stays on device only

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { stop() }
    override fun onDestroy() { stop(); instance = null; super.onDestroy() }

    /* ---------- Start / stop ---------- */
    fun runGoal(goal: String) {
        val m = model ?: return toast("No AI model loaded")
        stop()
        job = scope.launch {
            val history = mutableListOf<String>()
            log("goal", goal)
            repeat(MAX_STEPS) { step ->
                val screen = readScreen()
                val shot = takeShot()
                val action = try { m.decide(goal, screen, shot, history) }
                catch (e: Exception) { log("error", e.message ?: "model error"); return@launch }

                log("step$step", action.toString())
                history += action.toString()
                if (action is AgentAction.Done) { toast(action.message); return@launch }
                perform(action)
                delay(800) // let the UI settle
            }
            toast("Stopped: reached step limit")
        }
    }

    fun stop() { job?.cancel(); job = null }

    /* ---------- Perform an action ---------- */
    private suspend fun perform(a: AgentAction) {
        when (a) {
            is AgentAction.Tap -> tap(a.x, a.y)
            is AgentAction.TapText -> tapText(a.text)
            is AgentAction.TypeText -> typeText(a.text)
            is AgentAction.Swipe -> swipe(a.x1, a.y1, a.x2, a.y2, a.ms)
            is AgentAction.Scroll -> {
                val w = resources.displayMetrics.widthPixels / 2f
                val h = resources.displayMetrics.heightPixels.toFloat()
                if (a.down) swipe(w, h * 0.75f, w, h * 0.25f) else swipe(w, h * 0.25f, w, h * 0.75f)
            }
            is AgentAction.OpenApp -> {
                packageManager.getLaunchIntentForPackage(a.packageName)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(it)
                }
            }
            is AgentAction.Wait -> delay(a.ms)
            AgentAction.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
            AgentAction.Home -> performGlobalAction(GLOBAL_ACTION_HOME)
            AgentAction.Recents -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            is AgentAction.Done -> {}
        }
    }

    private suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 300) {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        gesture(p, ms)
    }

    private suspend fun tap(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y) }
        gesture(p, 50)
    }

    private suspend fun gesture(path: Path, ms: Long) = suspendCancellableCoroutine<Unit> { c ->
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { if (c.isActive) c.resume(Unit) {} }
            override fun onCancelled(d: GestureDescription?) { if (c.isActive) c.resume(Unit) {} }
        }, null)
        if (!ok && c.isActive) c.resume(Unit) {}
    }

    private fun tapText(text: String) {
        val root = rootInActiveWindow ?: return
        val node = root.findAccessibilityNodeInfosByText(text).firstOrNull() ?: return
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        (n ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun typeText(text: String) {
        val root = rootInActiveWindow ?: return
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /* ---------- See the screen ---------- */
    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "(no active window)"
        val sb = StringBuilder("app=${root.packageName}\n")
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 25) return
            val label = n.text ?: n.contentDescription
            if (label != null || n.isClickable || n.isEditable) {
                val r = Rect(); n.getBoundsInScreen(r)
                sb.append("  ".repeat(minOf(depth, 6)))
                  .append("[${n.className?.toString()?.substringAfterLast('.')}] ")
                  .append(if (n.isPassword) "[hidden password field]" else (label ?: ""))
                  .append(if (n.isClickable) " (clickable)" else "")
                  .append(if (n.isEditable) " (input)" else "")
                  .append(" @${r.centerX()},${r.centerY()}\n")
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return sb.toString()
    }

    private suspend fun takeShot(): Bitmap? = suspendCancellableCoroutine { c ->
        if (android.os.Build.VERSION.SDK_INT < 30) { c.resume(null) {}; return@suspendCancellableCoroutine }
        takeScreenshot(Display.DEFAULT_DISPLAY, shotExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                val bmp = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                    ?.copy(Bitmap.Config.ARGB_8888, false)
                r.hardwareBuffer.close()
                if (c.isActive) c.resume(bmp) {}
            }
            override fun onFailure(code: Int) { if (c.isActive) c.resume(null) {} }
        })
    }

    /* ---------- Memory: every step is saved locally ---------- */
    private fun log(type: String, data: String) {
        try {
            val o = JSONObject().put("t", System.currentTimeMillis()).put("type", type).put("data", data)
            logFile.appendText(o.toString() + "\n")
        } catch (_: Exception) {}
    }

    private fun toast(msg: String) {
        android.os.Handler(mainLooper).post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}
