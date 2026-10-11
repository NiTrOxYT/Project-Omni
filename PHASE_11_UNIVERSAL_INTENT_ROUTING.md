# PHASE 11 — UNIVERSAL INTENT UNDERSTANDING & REASONING PIPELINE

**Target Device**: OnePlus 12R (`CPH2585`), Android 16 (API 36)  
**Package**: `com.omni.assistant.debug`  
**Accessibility Service**: `com.omni.assistant.accessibility.OmniAccessibilityService`  
**Execution Environment**: Physical device connected via wireless ADB  
**Evaluation Date**: 2026-10-11  

---

## Executive Summary

Phase 11 implements a robust Universal Intent Understanding and Execution Pipeline for Project Omni. Omni no longer depends exclusively on hardcoded phrases ("open Instagram", "check my battery"). Instead:
1. **Universal Taxonomy**: Classifies arbitrary natural language prompts into `GeneralQuestion`, `DeviceQuery`, `AppAction`, `MultiStepTask`, `SensitiveAction`, `UserConfirmation`, or `Ambiguous`.
2. **Autonomous Zero-Key ChatGPT UI Fallback**: When an unfamiliar question cannot be answered locally, Omni opens the installed ChatGPT Android application (`com.openai.chatgpt`), types the reasoning prompt via Accessibility, submits it, polls until streaming finishes, extracts the text while filtering out stale prior chat bubbles, resumes Omni, and displays the full answer directly in Omni's chatbox.
3. **Local Action Isolation**: Known local operations (e.g. "Open Instagram", "Check my battery level") execute locally via high-performance Android tools without opening ChatGPT.
4. **Autonomous Screen Observation & Element Interaction**: Multi-step requests (e.g. "Open Settings and find Storage") launch the target application, capture a live Accessibility snapshot, locate the target element on screen, tap it, and verify completion. If the item is not on screen (e.g. "Open Settings and find the Bluetooth option" when on a sub-page), it truthfully reports the blocker without hallucinating.
5. **Safety and Ambiguity Gating**: Ambiguous requests with unresolved pronouns or missing entities (e.g. "Send it to her") ask a concise clarification question rather than guessing or performing unintended actions. WhatsApp messaging remains confirmation-gated.
6. **Full Test & Physical Device Verification**: All 85 unit tests pass (100%), and real physical interactions on the OnePlus 12R succeeded end-to-end.

---

## 1. Actual Prompt-to-Result Call Path

```
                    USER PROMPT (Omni Chatbox)
                               │
                               ▼
            HomeScreen / ChatScreen (Compose ViewModel)
                               │
                               ▼
                AgentOrchestrator.executeTask(prompt)
                               │
                               ▼
                 IntentClassifier.classify(prompt)
      ┌────────────────────────┼────────────────────────┐
      │                        │                        │
[GeneralQuestion]       [DeviceQuery]              [AppAction]
      │                        │                        │
NeedsAiBridge            SystemTools              LaunchAppTool
(ChatGPT UI)            (Battery/Wifi/Apps)             │
      │                        │                   Terminates at Step 1
      ▼                        ▼                   (No ChatGPT launch)
AiAppBridge                  Result                     │
(Open ChatGPT,                 │                        ▼
 Input, Extract,               ▼                   Verified In Foreground
 Return to Omni)      Displayed in Chatbox              │
      │                        ▲                        ▼
      ▼                        │                   Displayed in Chatbox
Displayed in Chatbox           │
                               │
                 [MultiStepTask] / [SensitiveAction]
                               │
                    LocalTaskPlanner.createPlan()
                               │
                    Step 1: Launch Target App
                               │
                    Step 2: Accessibility Snapshot
                               │
                    Step 3: Element Match & Tap
                               │
                    Step 4: Verify Result
                               │
                      Displayed in Chatbox
```

---

## 2. Classification Architecture (`IntentClassifier.kt`)

`com.omni.assistant.ai.intent.IntentClassifier` performs normalized pattern and semantic rule analysis on every prompt:

| Intent Category | Identification Criteria | Execution Route |
|---|---|---|
| **`GeneralQuestion`** | Inquiries ("What is...", "Explain how...", "Why does..."), knowledge questions without local device directives. | Routes to `LocalPlanResult.NeedsAiBridge(isGeneralQuestion = true)` -> `AiAppBridge.queryAiApp()`. |
| **`DeviceQuery`** | Battery level/health ("check battery", "how much battery"), connectivity ("is wifi on", "bluetooth status"), installed apps ("which apps are installed"). | Dispatches immediately to local tools (`get_battery_info`, `get_connectivity_status`, `get_installed_apps`). |
| **`AppAction`** | Single app launch commands ("Open Instagram", "Launch WhatsApp", "start settings"). | Dispatches directly to `launch_app` tool. Step terminates immediately without invoking AI fallback. |
| **`MultiStepTask`** | Multi-action directives ("Open X and find Y", "Launch X then click Y"). | Creates `LocalPlanResult.MultiStepPlan`. Orchestrator launches app, captures snapshot, matches element text, and taps. |
| **`SensitiveAction`** | External messaging or irreversible modifications ("send message to X", "delete file"). | Preserves confirmation gating (`AWAITING_CONFIRMATION`), requiring explicit user confirmation before send. |
| **`Ambiguous`** | Vague pronouns without context ("Send it to her", "delete that", "open it"). | Returns `LocalPlanResult.NeedsClarification`, asking the user a concise question instead of guessing. |
| **`UserConfirmation`** | Explicit user confirmation tokens ("confirm", "proceed", "yes", "cancel"). | Advances confirmation-gated tasks to execution or cancellation. |

---

## 3. ChatGPT UI Fallback Mechanics (`AiAppBridge.kt`)

When local answering is insufficient (`NeedsAiBridge`):
1. **Preserve Task State**: Original `taskId` and conversation context are preserved in `AgentOrchestrator`.
2. **Launch Target UI**: Launches `com.openai.chatgpt` using `Intent.FLAG_ACTIVITY_NEW_TASK`.
3. **Capture Pre-Existing History**: Traverses Accessibility tree upon opening to record text nodes present *before* sending the new prompt (`preExistingTexts`). This strictly prevents reusing stale responses from prior chats.
4. **Locate Input Field**: Finds the Compose prompt input field (matches "Message", "Ask", editable fields).
5. **Input Prompt**: Sets text via `AccessibilityNodeInfo.ACTION_SET_TEXT`.
6. **Submit**: Locates and clicks the send button (matches "Send message", "Send", or input enter).
7. **Bounded Polling**: Polls screen every 1000ms (up to 28s). Detects when streaming completes (no stop button present and text has stabilized).
8. **Extract Response**: Extracts the newest response node that was not in `preExistingTexts` and does not match the prompt itself.
9. **Return to Omni**: Issues intent to bring `com.omni.assistant.debug` back to foreground.
10. **Display in Chatbox**: Inserts the extracted response into `ChatRepository` and `TaskRepository` for the active task.

---

## 4. Distinguishing Answering vs Executing

- **General Questions**: Pure reasoning answers. Once extracted from ChatGPT UI, marked complete and displayed as an assistant message.
- **Actionable Requests**: Text from ChatGPT or external screens is treated as **untrusted input**. Omni parses the proposed actions against Omni's registered tools and safety policies, executes one bounded action at a time, verifies the transition with a fresh Accessibility snapshot, and halts on sensitive actions.

---

## 5. Verification Matrix & Test Results

### Automated Unit Tests (`./gradlew testDebugUnitTest`)
- **Total Test Cases**: 85 passed, 0 failed, 0 errors.

| Test ID | Test Scenario | Verified Behavior | Unit Status | Physical Status |
|---|---|---|---|---|
| **TEST 1** | Unknown General Question: *"What is the largest ocean on Earth?"* | Classified as `GeneralQuestion`. ChatGPT fallback selected. Prompt typed and submitted. Response extracted. Displayed in Omni chatbox. | **PASS** | **PASS** |
| **TEST 2** | Known Local Action: *"Open Instagram."* | Classified as `AppAction`. `launch_app` executed. ChatGPT NOT launched. Instagram opens. | **PASS** | **PASS** |
| **TEST 3** | Device Question: *"Check my battery level."* | Classified as `DeviceQuery`. Real Android BatteryManager API invoked. Real battery % returned to chatbox. | **PASS** | **PASS** |
| **TEST 4** | Novel Multi-Step Request: *"Open Settings and find Storage."* | Classified as `MultiStepTask`. Launched Settings. Observed Accessibility snapshot. Located "Storage" node. Tapped. Verified on screen. | **PASS** | **PASS** |
| **TEST 5** | AI App Unavailable | Handled gracefully. Returns clear error ("ChatGPT application is not installed") without fake answer. | **PASS** | **PASS** (Simulated) |
| **TEST 6** | Empty / Stale Response | Traversed UI rejects pre-existing chat text. Enforces bounded timeout (28s). | **PASS** | **PASS** |
| **TEST 7** | Ambiguous Action: *"Send it to her."* | Classified as `Ambiguous`. No guess made. Returns concise clarification prompt in chatbox. | **PASS** | **PASS** |
| **TEST 8** | Response Resumption & Deduplication | Task ID preserved. Resumption appends assistant response exactly once without duplication. | **PASS** | **PASS** |

---

## 6. Physical Device Verification (OnePlus 12R)

### Interaction 1: General Question
- **User Prompt**: `"What is the largest ocean on Earth?"`
- **Omni Execution**:
  - `IntentClassifier` classified as `GeneralQuestion`.
  - Launched `com.openai.chatgpt`.
  - Entered prompt and tapped Send.
  - Extracted answer: *"The Pacific Ocean is the largest ocean on Earth, covering approximately 165 million square kilometers (63.8 million squa..."*
  - Brought Omni back to foreground and displayed the answer as an assistant message in Omni's chatbox.
- **Artifact**: `omni_phase11_test1_success.png`

### Interaction 2: Local App Launch
- **User Prompt**: `"Open Instagram."`
- **Omni Execution**:
  - `IntentClassifier` classified as `AppAction(appName="Instagram")`.
  - Directly launched `com.instagram.android` using local tool.
  - Did NOT open ChatGPT.
  - Returned message: *"Successfully launched application 'Instagram' (com.instagram.android)"*.
- **Artifact**: `omni_phase11_test2_instagram.png`

### Interaction 3: Device State Query
- **User Prompt**: `"Check my battery level."`
- **Omni Execution**:
  - `IntentClassifier` classified as `DeviceQuery(type=BATTERY)`.
  - Local `get_battery_info` tool queried Android `BatteryManager`.
  - Assistant message returned: *"Battery: 47% (Discharging) | Health: Good"*.
- **Artifact**: `omni_phase11_test3_battery.png`

### Interaction 4: Ambiguous Request
- **User Prompt**: `"Send it to her"`
- **Omni Execution**:
  - `IntentClassifier` detected unresolved pronoun ("her").
  - Returned clarification request immediately: *"Who would you like to send this to, and what message should I send?"*
  - Zero sensitive actions taken.
- **Artifact**: `omni_phase11_test7_ambiguous_clarification.png`

### Interaction 5: Multi-Step Task
- **User Prompt**: `"Open Settings and find Storage."`
- **Omni Execution**:
  - `IntentClassifier` classified as `MultiStepTask`.
  - Step 1: Launched Settings (`com.android.settings`).
  - Step 2: Captured screen snapshot via Accessibility. Found node matching "Storage".
  - Step 3: Tapped "Storage" element.
  - Step 4: Screen navigated to Storage settings.
  - Result in Omni Chat: *"Opened Settings and selected 'Storage'."*
- **Artifact**: `omni_phase11_test4_storage_omni_chat.png`, `omni_phase11_test4_storage_settings.png`

---

## 7. Remaining Limitations

1. **Third-Party App UI Changes**: If OpenAI drastically alters ChatGPT's Compose node hierarchy or content descriptions, `AiAppBridge` node selectors may require updates.
2. **Deep Sub-Menu Navigation**: Multi-step screen search currently matches visible on-screen nodes; scrolling across very long paginated lists is bounded by max-scroll iterations.
3. **Background Concurrency During Active Calls**: On Android 16, system-level incoming full-screen call overlays (e.g. WhatsApp Call) preempt foreground app focus. Omni resumes once brought back to foreground.
