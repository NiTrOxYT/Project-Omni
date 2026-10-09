# Project Omni — Phase 3: Android Accessibility Automation Engine

## 1. Overview

Phase 3 introduces a native Android Accessibility Automation Engine to Project Omni. This enables the assistant to observe interactive on-screen elements, launch applications, tap buttons, enter text into editable fields, scroll containers, and navigate backward—all governed by strict local authorization policies and privacy safeguards.

---

## 2. Architecture and Data Flow

```
+-------------------------------------------------------------------------------+
|                             User Request                                      |
|            ("Open Android Settings and check Wi-Fi")                          |
+-------------------------------------------------------------------------------+
                                      │
                                      ▼
+-------------------------------------------------------------------------------+
|                      AgentOrchestrator (Phase 2 & 3)                          |
|  - Manages multi-turn loop (max 10 iterations)                                |
|  - Enforces loop boundaries (max 8 UI actions, max 3 consecutive failures)    |
|  - Preserves Gemini Thought Signatures across turns                           |
+-------------------------------------------------------------------------------+
           │                                                    ▲
           │ Tool Schemas & Prompts                             │ Tool Results &
           ▼                                                    │ Fresh Snapshots
+------------------------------------+                          │
|     Gemini 2.5 / Flash Brain       |                          │
|   - Selects Next Tool & Arguments  | ─────────────────────────┘
+------------------------------------+
           │
           ▼
+-------------------------------------------------------------------------------+
|                           ActionPolicy Layer                                  |
|   - Evaluates Risk Levels (LOW, MEDIUM, HIGH, CRITICAL)                       |
|   - Blocks credential & payment inputs (password, pin, card patterns)         |
+-------------------------------------------------------------------------------+
           │
           ▼
+-------------------------------------------------------------------------------+
|                         ToolRegistry & Dispatcher                             |
|   ├── get_accessibility_status                                                |
|   ├── get_ui_snapshot                                                         |
|   ├── launch_app                                                              |
|   ├── tap_element                                                             |
|   ├── enter_text                                                              |
|   ├── scroll_ui                                                               |
|   └── navigate_back                                                           |
+-------------------------------------------------------------------------------+
                                      │
                                      ▼
+-------------------------------------------------------------------------------+
|                     AccessibilityConnectionManager                            |
|   ├── OmniAccessibilityService (Android AccessibilityService)                 |
|   ├── AccessibilitySnapshotEngine (Depth/node bounds, sensitive text masking) |
|   └── Native AccessibilityNodeInfo Action Execution & Node Recycling          |
+-------------------------------------------------------------------------------+
```

---

## 3. Implemented Automation Tools

| Tool Name | Risk Level | Description | Key Parameters |
| :--- | :--- | :--- | :--- |
| `get_accessibility_status` | `LOW` | Verifies whether service is enabled in settings and connected | None |
| `get_ui_snapshot` | `LOW` | Produces compact, structured JSON snapshot of visible nodes | None |
| `launch_app` | `LOW` | Safely resolves and launches apps via Android `PackageManager` | `appName`, `packageName` |
| `tap_element` | `MEDIUM` | Validates snapshot ID, re-resolves node, triggers `ACTION_CLICK` | `snapshotId` (req), `elementId` (req) |
| `enter_text` | `MEDIUM` | Validates non-sensitive editable target, sets text via bundle | `snapshotId` (req), `elementId` (req), `text` (req) |
| `scroll_ui` | `LOW` | Dispatches `ACTION_SCROLL_FORWARD` or `ACTION_SCROLL_BACKWARD` | `snapshotId`, `elementId`, `direction` ("DOWN"/"UP") |
| `navigate_back` | `LOW` | Simulates Android global back button via `GLOBAL_ACTION_BACK` | None |

---

## 4. Privacy & Safety Safeguards

1. **Conservative Sensitive Field Filtering**:
   - `AccessibilitySnapshotEngine` detects `isPassword` nodes and sensitive keywords (`password`, `pin`, `cvv`, `otp`, `ssn`, `credit card`).
   - Sensitive text and content descriptions are replaced with `"[PROTECTED]"` or omitted entirely from AI payloads.
2. **Action Policy Rejection**:
   - `StandardActionPolicy` automatically denies `enter_text` operations containing password, bank, or card patterns.
3. **No Blind Stale Node Reuse**:
   - `AccessibilityConnectionManager` validates incoming `snapshotId` against the active screen cache and re-resolves elements against `rootInActiveWindow` before dispatching actions.
4. **Bounded Orchestration**:
   - Maximum 10 turns per task.
   - Automatically aborts if 3 consecutive tool actions fail.
   - Immediate coroutine cancellation when the user clicks Cancel in the UI.
5. **No Root / Shell Bypasses**:
   - All actions use official Android Accessibility APIs.

---

## 5. Enabling Accessibility on Physical Device

1. Open Omni **Settings** tab.
2. Scroll to **Accessibility Automation Engine** card.
3. Tap **Open Accessibility Settings**.
4. In Android Accessibility Settings, go to **Downloaded Apps** -> **Omni Assistant Automation Service**.
5. Toggle the switch to **On** and tap **Allow**.
6. Return to Omni; status will transition to **CONNECTED (A11Y READY)**.

---

## 6. Verification & Test Results

- **Unit Tests**: 38 total tests passing across all suites (`AccessibilityAutomationUnitTest`, `GeminiAiProviderPayloadAndParsingTest`, `AgentOrchestratorTest`, `ToolRegistryAndExecutionTest`, etc.).
- **Build**: `./gradlew clean test assembleDebug` succeeded with zero errors.
- **Physical Device**: APK deployed to OnePlus 12R (CPH2585, Android 16).
