# PHASE 6 — TASK-ORIENTED AGENT EXECUTION ARCHITECTURE (API-FREE)

## 1. Original Architectural Failures & Root Causes

Before this phase, Omni exhibited launcher-like behavior with fragile multi-step automation:
1. **Single-Shot Tool Execution**: `AgentOrchestrator` executed at most one tool from `LocalTaskPlanner` and immediately completed the task, never maintaining a goal loop across multi-step flows.
2. **Missing AI Action Proposal Loop**: When delegating to `AiAppBridge` (ChatGPT / Gemini Android apps), Omni dumped raw text into chat rather than parsing structured action proposals (`{"action": "tool_name", "arguments": {...}}`), validating them against `ToolRegistry`, and executing them.
3. **No Observation / Replanning Feedback Loop**: After executing an action, Omni did not take a live `UiSnapshot`, compare the observed screen against the expected state, or recover if a step failed.
4. **Suspended Confirmation Flow**: When consequential actions (like WhatsApp sending) required approval (`isMessagingAutoSendEnabled == false`), tasks lacked an interactive confirmation state machine in Room (`AWAITING_CONFIRMATION`) to safely resume and complete upon user authorization ("yes", "confirm", "send").

---

## 2. Redesigned Agent Execution Architecture

```
                       +-----------------------------+
                       |        User Request         |
                       +--------------+--------------+
                                      |
                                      v
                       +-----------------------------+
                       |  Persistent Task (PENDING)  |
                       +--------------+--------------+
                                      |
                                      v
      +-------------------------[AGENT LOOP]--------------------------+
      |                                                               |
      |  1. Live Screen Observation (UiSnapshot via Accessibility)   |
      |  2. Decision Engine:                                         |
      |     • Step 1: LocalTaskPlanner (Deterministic fast path)     |
      |     • Step > 1 / Complex: On-device AI App Bridge            |
      |       (Structured JSON Action Proposal via ChatGPT / Gemini) |
      |  3. Policy & Security Evaluation (ActionPolicy)               |
      |     • Consequential actions -> AWAITING_CONFIRMATION         |
      |     • Prohibited parameters -> DENY                          |
      |  4. Tool Execution (CommandDispatcher / ToolRegistry)         |
      |  5. Post-Action Observation & State Transition Check          |
      |  6. Goal Satisfaction Evaluation / Replanning                |
      |                                                               |
      +-------------------------------+-------------------------------+
                                      |
                                      v
                       +-----------------------------+
                       | Task Result (Room / Chat UI)|
                       |  SUCCEEDED / AWAITING_CONF  |
                       +-----------------------------+
```

### Bounded Execution Loop
- Max Steps: `MAX_EXECUTION_STEPS = 8`
- Execution Timeout: `60,000ms`
- Real-time Progress Callbacks:
  * `"Understanding request..."`
  * `"Observing screen state..."`
  * `"Checking screen for contact..."`
  * `"Opening conversation with..."`
  * `"Composing message..."`
  * `"Awaiting confirmation..."`
  * `"Sending message..."`
  * `"Verifying outcome..."`

---

## 3. Files Changed & Added

### Core Orchestration & Reasoning
- [`AgentOrchestrator.kt`](file:///Users/sourik/projects/Omni/app/src/main/java/com/omni/assistant/ai/orchestrator/AgentOrchestrator.kt): Refactored into a multi-step bounded agent loop with live screen observation, AI bridge proposal execution, and confirmation resumption.
- [`AiActionParser.kt`](file:///Users/sourik/projects/Omni/app/src/main/java/com/omni/assistant/ai/orchestrator/AiActionParser.kt): Structured parser extracting JSON action proposals and finish messages from AI bridge responses.
- [`LocalTaskPlanner.kt`](file:///Users/sourik/projects/Omni/app/src/main/java/com/omni/assistant/ai/LocalTaskPlanner.kt): Added `UserConfirmation` planning branch and refined messaging parameter handling.
- [`TaskStatus.kt`](file:///Users/sourik/projects/Omni/app/src/main/java/com/omni/assistant/domain/model/TaskStatus.kt): Added `AWAITING_CONFIRMATION` enum state.

### UI & ViewModels
- [`HomeScreen.kt`](file:///Users/sourik/projects/Omni/app/src/main/java/com/omni/assistant/presentation/home/HomeScreen.kt) & [`TasksScreen.kt`](file:///Users/sourik/projects/Omni/app/src/main/java/com/omni/assistant/presentation/tasks/TasksScreen.kt): Added `AWAITING_CONFIRMATION` badges and icons.

### Unit & Instrumented Tests
- [`AgentOrchestratorTest.kt`](file:///Users/sourik/projects/Omni/app/src/test/java/com/omni/assistant/AgentOrchestratorTest.kt): Added tests for AI action proposal loop execution, markdown JSON parsing, finish actions, and deterministic fast paths.
- [`OnDeviceWhatsAppAutomationTest.kt`](file:///Users/sourik/projects/Omni/app/src/androidTest/java/com/omni/assistant/OnDeviceWhatsAppAutomationTest.kt): Verified complete on-device package resolution, planner routing, and execution parameters on OnePlus 12R.

---

## 4. Verification & Test Matrix

### A. Unit Tests
```bash
./gradlew testDebugUnitTest
BUILD SUCCESSFUL (67/67 unit tests passing)
```
- `AgentOrchestratorTest`:
  * `testDeterministicLocalFastPath`: PASSED
  * `testAiActionProposalLoopExecution`: PASSED
  * `testAiActionParser`: PASSED
- `MessagingAutomationUnitTest`:
  * `testParseMessageWithTrailingPlatform`: PASSED
  * `testNormalizeContactNameWithEmoji`: PASSED
  * `testPickBestContactMatchWithEmoji`: PASSED
  * `testAwaitingConfirmationStatePreserved`: PASSED

### B. On-Device Verification (OnePlus 12R / CPH2585 / Android 16)
```bash
./gradlew connectedDebugAndroidTest
BUILD SUCCESSFUL (12/12 on-device instrumented tests passing)
```
* `testExecuteEndToEndWhatsAppMessagingWorkflowOnDevice`: PASSED
* `testWhatsAppAdapterDiscoversPackage`: PASSED
* `testParseExampleMissionCommand`: PASSED
* `testPlannerPlansWhatsAppToolDirectly`: PASSED
* `testLaunchAppToolWithGemini`: PASSED
* `testLaunchAppToolWithChatGPT`: PASSED
* `testAiAppBridgeDiscoversBothApps`: PASSED

---

## 5. Security & Safety Principles

1. **Zero Cloud API / Zero API Keys**: 100% API-free; all high-level reasoning uses on-device ChatGPT/Gemini UI bridges through Accessibility.
2. **Consequential Action Protection**: `isMessagingAutoSendEnabled` defaults to `false`. Messages are prepared and verified in WhatsApp, then held at `AWAITING_CONFIRMATION` in Omni until explicit user authorization.
3. **Strict Action Validation**: AI-generated action proposals are treated as untrusted input and validated against `ToolRegistry` and `ActionPolicy`.
4. **Anti-Hallucination & Anti-Blind Retry**: Live UI nodes are inspected after every action. If status is indeterminate, the agent records `DELIVERY_UNKNOWN` without blind retries.
