# Phone Agent Setup (Android)

## 1. Add files
- `PhoneAgentService.kt` → `app/src/main/java/<your/package>/`
- Create `app/src/main/res/xml/accessibility_service_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows|flagReportViewIds"
    android:canPerformGestures="true"
    android:canRetrieveWindowContent="true"
    android:canTakeScreenshot="true"
    android:description="@string/agent_description"
    android:notificationTimeout="100" />
```

## 2. strings.xml
```xml
<string name="agent_description">Lets the AI assistant see the screen and perform taps and typing that you request.</string>
```

## 3. AndroidManifest.xml (inside <application>)
```xml
<service
    android:name=".PhoneAgentService"
    android:exported="false"
    android:label="Edge AI Agent"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>

<!-- needed to launch other apps on Android 11+ -->
<queries>
    <intent><action android:name="android.intent.action.MAIN" /></intent>
</queries>
```

## 4. build.gradle (app)
```gradle
implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1"
```

## 5. Connect your edge model
```kotlin
class MyModel : EdgeModel {
    override suspend fun decide(goal: String, screenText: String,
        screenshot: Bitmap?, history: List<String>): AgentAction {
        // Prompt your on-device LLM/VLM with goal + screenText (+ screenshot),
        // parse its answer into ONE AgentAction, e.g.:
        // return AgentAction.OpenApp("com.google.android.youtube")
        TODO("call your model here")
    }
}

// in your Activity, after the model loads:
PhoneAgentService.model = MyModel()
```

## 6. Enable and run
1. Phone **Settings → Accessibility → Edge AI Agent → turn ON** (Android requires you to do this manually).
2. From your app: `PhoneAgentService.instance?.runGoal("open YouTube and play lo-fi music")`
3. Stop anytime: `PhoneAgentService.instance?.stop()`

## Notes
- Everything is logged on-device to `files/agent_log.jsonl` (goal, every step). Nothing is uploaded.
- Banking apps, password fields and some games block accessibility or screenshots; that's an Android/app limit.
- The loop stops after 40 steps as a safety net.
- Tip: add a floating "Stop" button or volume-key shortcut so you can always cancel quickly.
