# PHASE 3.5 — API-FREE PHONE CONTROL & ON-DEVICE AI APP BRIDGE

## 1. Architectural Overview

Project Omni has transitioned to an **API-free on-device automation assistant**. It no longer depends on the cloud Gemini API, any network LLM API key, or paid subscription services for normal operation.

```
+-----------------------------------------------------------------------------------+
|                                  USER QUERY                                       |
+-----------------------------------------+-----------------------------------------+
                                          |
                                          v
+-----------------------------------------------------------------------------------+
|                        com.omni.assistant.ai.LocalTaskPlanner                     |
|                                                                                   |
|  * Deterministic classification (Battery, Storage, Specs, Screen Inspection)       |
|  * Direct tool matching (Launch App, Scroll, Navigate Back)                       |
|  * Explicit AI routing ("Ask ChatGPT...", "Ask Gemini...")                        |
|  * Open-ended AI fallback -> Selected on-device AI app bridge                      |
+-------------------+---------------------------------------+-----------------------+
                    |                                       |
         [Deterministic Match]                     [Complex AI Needed]
                    |                                       |
                    v                                       v
+-----------------------------------+   +-------------------------------------------+
| com.omni.assistant.execution      |   | com.omni.assistant.ai.bridge.AiAppBridge  |
| .CommandDispatcher                |   |                                           |
|                                   |   | 1. Discovers installed AI apps             |
| * get_battery_info                |   |    (ChatGPT: com.openai.chatgpt,          |
| * get_storage_info                |   |     Gemini: com.google.android.apps.bard) |
| * get_device_info                 |   | 2. Launches target app with NEW_TASK      |
| * get_ui_snapshot                 |   | 3. Finds prompt input field via A11y      |
| * launch_app                      |   | 4. Types prompt via AccessibilityAction   |
| * tap_element                     |   | 5. Taps Send / Submit button              |
| * scroll_ui / navigate_back       |   | 6. Polls for response generation state    |
+-------------------+---------------+   | 7. Extracts latest model response text    |
                    |                   | 8. Returns to Omni task flow              |
                    |                   +-------------------+-----------------------+
                    |                                       |
                    +-------------------+-------------------+
                                        |
                                        v
+-----------------------------------------------------------------------------------+
|                     Room Database (Tasks & Chat History)                         |
|                         & Jetpack Compose UI Output                              |
+-----------------------------------------------------------------------------------+
```

---

## 2. Supported AI Applications

Omni detects and interfaces with installed Android AI applications dynamically:

1. **ChatGPT for Android** (`com.openai.chatgpt`)
   - Input field: Accessible edit text matching `Ask ChatGPT`, `Message`, `Prompt`, `Query`, `Input`, or editable text node.
   - Send action: Content description matching `Send`, `Submit`, `Send prompt`, or clickable trailing icon.
   - Generation state: Streaming indicator or stop generating icon.
   - Response extraction: Trailing accessibility node text not belonging to prompt or input bar.

2. **Google Gemini for Android** (`com.google.android.apps.bard` / `com.google.android.googlequicksearchbox`)
   - Input field: `Ask Gemini`, `Type a message`, `Enter prompt`, `Search or ask`.
   - Send action: `Send`, `Submit`, `Send message`.
   - Response extraction: Latest assistant bubble / markdown card.

3. **Auto Selection Mode**:
   - Discovers all installed AI applications on device.
   - Defaults to ChatGPT if available, otherwise Gemini.
   - Configurable per-user in Omni System Settings.

---

## 3. Task State Transitions

The execution engine maintains explicit states:

```
[IDLE]
  │
  ▼
[PLANNING] ─────────────► [EXECUTING_LOCAL_ACTION] ──► [VERIFYING_ACTION] ──► [COMPLETED]
  │                              │
  ├─ (Needs AI reasoning)         └─► (Fail) ───────────────────────────────► [FAILED]
  │
  ▼
[NEEDS_AI_REASONING]
  │
  ▼
[OPENING_AI_APP]
  │
  ▼
[WAITING_FOR_AI_APP]
  │
  ▼
[ENTERING_PROMPT]
  │
  ▼
[WAITING_FOR_AI_RESPONSE]
  │
  ▼
[READING_AI_RESPONSE]
  │
  ▼
[RETURNING_TO_TASK] ──► [VERIFYING_ACTION] ──► [COMPLETED]
```

---

## 4. Response Extraction & Safety Limits

1. **Zero Secret Exfiltration**:
   - `AiAppBridge` filters out password fields (`isPassword = true`), biometric prompts, OTP codes, and payment UI.
2. **Anti-Hallucination & Honest Extraction**:
   - Never fabricates a response if extraction fails or the app is on a login/CAPTCHA screen.
   - Returns explicit error instructing user if app requires login or manual intervention.
3. **Loop & Polling Protection**:
   - Polling is bounded (`MAX_POLL_DURATION_MS = 25_000L`, `POLL_INTERVAL_MS = 800L`).
   - Does not create tight busy-loops that freeze the UI thread.
4. **ActionPolicy Safeguards**:
   - AI-proposed actions from external chat apps are treated as untrusted text recommendations.
   - Consequential actions (financial, data deletion, permissions) require explicit user confirmation via `ActionPolicy`.

---

## 5. Test Suite Verification

- **Total Tests**: 50 unit tests executed and passing (`./gradlew test`).
- **Test Categories**:
  1. `ApiFreeArchitectureUnitTest`:
     - `testBatteryCommandPlansLocallyWithoutApi` (PASSED)
     - `testStorageCommandPlansLocallyWithoutApi` (PASSED)
     - `testDeviceInfoCommandPlansLocallyWithoutApi` (PASSED)
     - `testDescribeScreenPlansLocallyWithoutApi` (PASSED)
     - `testLaunchAppPlansLocallyWithoutApi` (PASSED)
     - `testNavigateBackPlansLocallyWithoutApi` (PASSED)
     - `testScrollDownPlansLocallyWithoutApi` (PASSED)
     - `testExplicitChatGPTQueryRoutesToBridge` (PASSED)
     - `testExplicitGeminiQueryRoutesToBridge` (PASSED)
     - `testComplexOpenEndedQueryDefaultsToSelectedBridge` (PASSED)
     - `testAiAppDiscoveryReturnsCorrectModels` (PASSED)
     - `testAiBridgeQueryExecution` (PASSED)
  2. `AgentOrchestratorTest`:
     - `testDeterministicLocalFastPath` (PASSED)
     - `testAiBridgeQueryExecutionThroughOrchestrator` (PASSED)
  3. `GeminiAiProviderPayloadAndParsingTest` & `ActionPolicyTest`: All 36 regression tests passing.

---

## 6. Physical-Device Acceptance (OnePlus 12R / CPH2585 / Android 16)

| Test Item | Verification | Result |
|---|---|---|
| **A. Zero API Key Local Battery** | Asked battery query -> `get_battery_info` executed locally with zero cloud API calls | **PASSED** |
| **B. Local App Launch & UI Inspection** | Launched Settings -> Captured UI tree elements via `OmniAccessibilityService` | **PASSED** |
| **C. AI App Discovery** | Detected installed apps: `com.openai.chatgpt` and `com.google.android.apps.bard` | **PASSED** |
| **D. Settings UI** | "On-Device AI App Bridge (Zero API Keys)" card displayed with app dropdown & manual launch test | **PASSED** |
| **E. Accessibility Service Status** | `com.omni.assistant.debug/com.omni.assistant.accessibility.OmniAccessibilityService` active | **PASSED** |
| **F. Security Enforcement** | Dangerous actions blocked / require confirmation per `ActionPolicy` | **PASSED** |

---

## 7. Known Limitations & Boundaries

1. **Accessibility Node Visibility**:
   - Certain third-party apps render chat messages inside custom Canvas or WebViews without accessible semantics. In those cases, `AiAppBridge` reports extraction failure honestly.
2. **Authentication Screens**:
   - If ChatGPT or Gemini logged out or blocked by Cloudflare/Cloudflare Turnstile, Omni stops and notifies user to complete login manually.
3. **Android 16 Foreground App Tracking**:
   - `UsageStatsManager` or Accessibility window change events determine previous app package for returning.
