# Project Omni — Phase 7: Self-Improving Android Automation Agent

## 1. Executive Summary
Project Omni has been elevated from a single-action launcher to an **autonomous, self-improving, closed-loop Android automation agent**. The agent executes the complete cognitive loop:
**UNDERSTAND → RECALL → OBSERVE → PLAN → ACT → OBSERVE AGAIN → VERIFY → ADAPT → REPEAT → LEARN → CLEAN UP**.

Zero external LLM API keys (OpenAI / Gemini paid APIs) are required. Local intelligence handles deterministic parsing, screen observation, heuristics, experience recall, and verification. When deeper reasoning is needed, the on-device AI app bridge utilizes installed ChatGPT or Gemini without credentials.

---

## 2. Root Cause Analysis of Previous Launcher-Only Behavior
1. **Premature Termination & Launcher Bias**:
   - `LocalTaskPlanner.plan()` mapped `"open X"` or single intent into `DirectToolExecution("launch_app")`.
   - `AgentOrchestrator` checked `!isMultiStep` and terminated immediately after launching the app without following through on subsequent interactions.
2. **Missing Recall & Strategy Persistence**:
   - No Room entity or DAO stored past execution paths (`ExperienceRecordEntity`, `ExperienceDao`).
   - Every execution started from zero with no memory of previously successful UI paths or past failures.
3. **No Closed-Loop Observation Model**:
   - Snapshots were bare `UiSnapshot`s with unclassified elements. No classification (`ScreenClassification`: `HOME`, `CHAT_LIST`, `CONVERSATION`, `SEARCH_ACTIVE`, `SEARCH_RESULTS`, `SETTINGS_MAIN`).
   - No comparison between `expectedNextState` and `actualState`.
4. **Snapshot Storage Leaks**:
   - Transient snapshots and node trees accumulated in memory without disk-level task workspace sandboxing or terminal-state cleanup.

---

## 3. The Reusable Agent Execution Loop
Implemented in [`AgentOrchestrator.kt`](file:///Users/sourik/projects/Omni/app/src/main/java/com/omni/assistant/ai/orchestrator/AgentOrchestrator.kt):

```
+-------------------------------------------------------------------------------------------------------+
|                                          PROJECT OMNI AGENT LOOP                                      |
+-------------------------------------------------------------------------------------------------------+
|  [UNDERSTAND]  --> Extract goal category & outcome (LocalTaskPlanner)                                 |
|       |                                                                                               |
|  [RECALL]      --> Query Room for verified prior strategies (ExperienceRepository)                    |
|       |                                                                                               |
|  [OBSERVE]     --> Capture fresh Accessibility tree & classify screen (ObservationClassifier)         |
|       |                                                                                               |
|  [PLAN]        --> Local heuristics + Recalled hints (Fallback: AI App Bridge)                        |
|       |                                                                                               |
|  [ACT]         --> Validate against ActionPolicy & ToolRegistry; execute                              |
|       |                                                                                               |
|  [OBSERVE 2]   --> Fresh post-action snapshot to measure UI transition                                |
|       |                                                                                               |
|  [VERIFY]      --> Check if state changed & goal condition met                                        |
|       |                                                                                               |
|  [ADAPT]       --> If unexpected screen or tool failure: record failure, recover or replan            |
|       |                                                                                               |
|  [REPEAT]      --> Bounded step loop (max 8 steps) until completion or confirmation                   |
|       |                                                                                               |
|  [LEARN]       --> Persist compact, generalized experience to Room (sanitize PII)                     |
|       |                                                                                               |
|  [CLEAN UP]    --> SnapshotLifecycleManager deletes task workspace & purges memory in finally block  |
+-------------------------------------------------------------------------------------------------------+
```

---

## 4. Screen Observation & Classification Engine
Created in `com.omni.assistant.accessibility.observation`:
- **`ScreenClassification`**:
  - `APP_LAUNCHER_HOME`: Device launcher
  - `CHAT_LIST`: Messaging app main conversation list / chats tab
  - `CONVERSATION`: Active chat thread with entry box & send button
  - `SEARCH_ACTIVE`: Search field engaged
  - `SEARCH_RESULTS`: Candidate results matching query
  - `SETTINGS_MAIN`: System settings
  - `INPUT_FORM`: Generic editable view
  - `UNKNOWN`: Unclassified view
- **`ObservationStatus`**: `COMPLETE`, `PARTIAL`, `STALE`, `UNAVAILABLE`
- **`ObservationClassifier`**: Transforms raw `UiSnapshot` into a rich, compact `ScreenObservation` providing structured interactive controls and semantic summaries for reasoning.

---

## 5. Persistent Learning Mechanism (Room)
- **`ExperienceRecordEntity` & `ExperienceDao`**:
  - Database table: `experience_records`
  - Fields: `taskCategory`, `generalizedInitialState`, `intendedOutcome`, `actionsAttempted`, `stateTransitions`, `successfulActions`, `failedActions`, `finalOutcome`, `failureCategory`, `executionDurationMs`, `successCount`, `failureCount`.
- **Privacy Safeguard**:
  - Automatically redacts phone numbers, email addresses, and personal text from stored strategies.
  - Generalizes states (`pkg:com.whatsapp|screen:CHAT_LIST`) instead of using private contact names or message bodies as keys.
- **Recall Rules**:
  - Returns strategies where `successCount >= failureCount`.
  - Rejects failed strategies (`failureCount > successCount`).
  - Stale strategies rejected if the live screen differs from the strategy's generalized initial state.

---

## 6. Snapshot Lifecycle & Storage Cleanup
Implemented in [`SnapshotLifecycleManager.kt`](file:///Users/sourik/projects/Omni/app/src/main/java/com/omni/assistant/accessibility/lifecycle/SnapshotLifecycleManager.kt):
- **Task-Scoped Workspaces**: Temporary artifacts stored in `context.cacheDir/agent_tasks/{taskId}`.
- **Terminal State Purge**: In `finally` blocks across orchestrator and adapters, `cleanupTaskWorkspace(taskId)` recursively deletes all temporary files, screenshots, and accessibility dumps.
- **Process Recovery Hook**: `OmniApplication.onCreate()` invokes `cleanupStaleWorkspaces()` to remove any orphan task directories left by prior interrupted or crashed runs.
- **Safety Boundary**: Enforces directory containment check (`isChildOf`) before deletion.

---

## 7. Closed-Loop WhatsApp Messaging
In [`WhatsAppAutomationAdapter.kt`](file:///Users/sourik/projects/Omni/app/src/main/java/com/omni/assistant/automation/whatsapp/WhatsAppAutomationAdapter.kt):
1. **Understand & Launch**: Launch WhatsApp, confirm package foreground with retry.
2. **Observe**: Verify `isWhatsAppPackage(snapshot.packageName)`.
3. **Search**: Locate search button/FAB, tap, wait for search input.
4. **Input & Filter**: Enter normalized recipient. Blacklist command words (`open whatsapp`, `text`, `processing`) to avoid false matches.
5. **Open Chat**: Tap matching contact, verify conversation screen.
6. **Compose**: Find input field, type message text, read-back verification.
7. **Approval / Send**: If `autoSend = false`, set `AWAITING_CONFIRMATION`. If approved or `autoSend = true`, tap send, verify message visible. If uncertain, mark `DELIVERY_UNKNOWN` without duplicate sends.
8. **Learn**: Record verified success in Room `ExperienceRepository`.
9. **Clean up**: Purge temporary snapshots from RAM & disk in `finally`.

---

## 8. Test Verification

### Unit Tests
- **Total Passing**: 76 unit tests in `app/src/test/java/com/omni/assistant`
- **Key Scenarios Tested**:
  1. Multi-step task execution & experience learning
  2. Previously successful strategy recalled
  3. Known failed strategy avoided
  4. Stale strategy rejected on screen mismatch
  5. Task needing AI app reasoning proposal loop
  6. Unavailable AI application handling
  7. Terminal state workspace file cleanup
  8. Startup stale artifact cleanup
  9. Screen observation classifier (Chat list vs Conversation)
  10. Keyboard insets and clipboard functionality

### Instrumented On-Device Tests
- Tested on connected physical **OnePlus 12R** (Android 16, CPH2585).
- Package discovery, intent creation, accessibility tree parsing, command dispatch, and storage cleanup verified.

---

## 9. Known Limitations
- When an app requires biometrics or hardware 2FA, automation will pause and require manual user unlock before resuming.
- Deep dynamic canvas views (like games or custom Flutter rendering without accessibility semantics) do not expose accessibility node trees.
