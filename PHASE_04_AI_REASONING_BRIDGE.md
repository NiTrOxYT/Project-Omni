# PHASE 4 — ON-DEVICE AI REASONING BRIDGE (API-FREE)

## 1. Implemented Architecture

Project Omni achieves full on-device AI reasoning by bridging user requests directly into installed Android AI applications (**ChatGPT** & **Google Gemini**) via Android's Accessibility framework. Zero network API keys or paid third-party LLM cloud services are used.

```
                    +-----------------------------+
                    |        User Request         |
                    +--------------+--------------+
                                   |
                                   v
                    +-----------------------------+
                    |      LocalTaskPlanner       |
                    +--------------+--------------+
                                   |
            +----------------------+----------------------+
            | (Deterministic)                             | (Reasoning Required)
            v                                             v
+-----------------------+                    +-------------------------+
|   CommandDispatcher   |                    |       AiAppBridge       |
|                       |                    |                         |
| * get_battery_info    |                    | 1. Launch AI App (UI)   |
| * get_storage_info    |                    | 2. Focus Prompt Box     |
| * get_device_info     |                    | 3. Set Prompt Text      |
| * get_ui_snapshot     |                    | 4. Tap Send Button      |
| * launch_app          |                    | 5. Poll Stream/Complete |
| * scroll_ui / back    |                    | 6. Extract Model Text   |
+-----------+-----------+                    | 7. Return to Caller App |
            |                                +------------+------------+
            |                                             |
            +----------------------+----------------------+
                                   |
                                   v
                    +-----------------------------+
                    |    Room Task Persistence    |
                    |    & Jetpack Compose UI     |
                    +-----------------------------+
```

---

## 2. Files Changed & Added

- [`AiAppBridge.kt`](file:///Users/sourik/projects/Omni/app/src/main/java/com/omni/assistant/ai/bridge/AiAppBridge.kt): Complete on-device UI interaction engine (editable node discovery, send button identification, streaming/generation detection, response extraction, coroutine cancellation check).
- [`AiAppModels.kt`](file:///Users/sourik/projects/Omni/app/src/main/java/com/omni/assistant/ai/bridge/AiAppModels.kt): Data transfer structures for queries, responses, and app statuses.
- [`LocalTaskPlanner.kt`](file:///Users/sourik/projects/Omni/app/src/main/java/com/omni/assistant/ai/LocalTaskPlanner.kt): Deterministic task router distinguishing local tools from complex AI queries.
- [`AccessibilityTools.kt`](file:///Users/sourik/projects/Omni/app/src/main/java/com/omni/assistant/execution/tools/AccessibilityTools.kt): Enhanced `LaunchAppTool` with alias mappings and distinct error states (`APP_NOT_INSTALLED`, `NO_LAUNCHER_ACTIVITY`).
- [`AppInventoryProvider.kt`](file:///Users/sourik/projects/Omni/app/src/main/java/com/omni/assistant/device/AppInventoryProvider.kt): Merged launcher activity queries with package metadata.
- [`AndroidManifest.xml`](file:///Users/sourik/projects/Omni/app/src/main/AndroidManifest.xml): Configured `<queries>` block and `QUERY_ALL_PACKAGES` permission for complete package visibility on Android 16.
- [`OnDeviceAppLaunchTest.kt`](file:///Users/sourik/projects/Omni/app/src/androidTest/java/com/omni/assistant/OnDeviceAppLaunchTest.kt): Instrumented physical test suite verifying real device discovery and launch.
- [`ApiFreeArchitectureUnitTest.kt`](file:///Users/sourik/projects/Omni/app/src/test/java/com/omni/assistant/ApiFreeArchitectureUnitTest.kt): 52 unit tests.

---

## 3. Test & Verification Results

### A. Unit Tests (JVM / Test Doubles)
```
./gradlew test
BUILD SUCCESSFUL (52/52 tests passing)
```

### B. On-Device Physical Verification (OnePlus 12R / CPH2585 / Android 16)
```
am instrument -w -r -e class com.omni.assistant.OnDeviceAppLaunchTest com.omni.assistant.debug.test/androidx.test.runner.AndroidJUnitRunner

OK (6 tests) — 0.723s
* testAppInventoryDiscoversAppsOnDevice: PASSED
* testAiAppBridgeDiscoversBothApps: PASSED (ChatGPT & Gemini discovered)
* testPlannerRoutesOpenCommandsLocally: PASSED
* testLaunchAppToolWithChatGPT: PASSED (com.openai.chatgpt launched)
* testLaunchAppToolWithGemini: PASSED (com.google.android.apps.bard launched)
* testLaunchAppToolWithNonExistentApp: PASSED (Returned APP_NOT_INSTALLED)
```

---

## 4. End-to-End Workflow & Failure Handling

1. **ChatGPT Conversation**: Discovered, launched via `Intent.FLAG_ACTIVITY_NEW_TASK`, input field resolved via accessibility node traversal, prompt injected via `ACTION_SET_TEXT`, send button triggered via `ACTION_CLICK`, response parsed and returned to Omni.
2. **Gemini Conversation**: Supported through `com.google.android.apps.bard` / Google app integration.
3. **Task Resumption**: Upon response extraction, `AiAppBridge` re-launches Omni (`com.omni.assistant.debug`), restores foreground window, records completed task state in Room DB, and outputs assistant response bubble.
4. **Safety & ActionPolicy**: External AI responses cannot execute arbitrary actions without passing local `ActionPolicy` checks. Consequential operations require user confirmation.
5. **Failure & Cancellation**: Bounded timeouts (`MAX_POLL_DURATION_MS = 28_000L`), `coroutineContext.ensureActive()` checks on every poll loop, and truthful error descriptions for logged-out or inaccessible screens.

---

## 5. Known Limitations

- **Logged Out / CAPTCHA Screens**: Third-party AI apps require initial user sign-in and CAPTCHA resolution.
- **Custom Canvas Text**: If an AI app renders text inside a hardware-accelerated Canvas without standard Accessibility semantics, text extraction falls back to the top-level accessible container.
