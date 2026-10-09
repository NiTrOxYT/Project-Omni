# Omni — Personal AI Agent for Android

Omni is an open, extensible personal AI agent application built natively for Android using Jetpack Compose, Material 3, Clean Architecture, and Room.

---

## 🌟 Capabilities Overview

### Phase 1 — Foundation & Telemetry
- **Clean Architecture Foundation**: Domain, Data, Presentation, Device, Execution, and Security layers.
- **Real Device Telemetry**: `DeviceInfoProvider`, `StorageInfoProvider`, `BatteryInfoProvider`, `AppInventoryProvider`, and safe `RootDetector`.
- **Room Database Persistence**: Full persistence for multi-turn chat messages and task lifecycle execution records.

### Phase 2 — AI Brain, Natural-Language Understanding, and Tool Calling
- **Provider-Independent AI Abstraction**: `AiProvider`, `AiRequest`, `AiResponse`, `AiMessage`, `AiToolCall`, `AiToolResult`, and `AiToolSchema`.
- **Cloud AI Provider**: Official Gemini REST API (`gemini-2.0-flash`, `gemini-1.5-flash`, `gemini-1.5-pro`) with native function/tool declarations, tool execution, and grounded final answers.
- **Bounded Agent Orchestrator**: Multi-turn tool execution loop with configurable iteration limits, request timeouts (45s), cancellation support, and protection against infinite loops.
- **Deterministic Local Fallback**: Fast offline execution for recognized local commands (`Check my battery`, `Check my storage`, `Show device information`, `What version of Omni am I using?`).
- **Secure Credential Storage**: Private on-device SharedPreferences storage; zero API keys committed, logged, or hardcoded in APKs.
- **Dynamic Progress UI**: Real-time agent status indicators (e.g. *"Understanding your request..."*, *"Executing battery status..."*, *"Synthesizing results..."*) with one-tap cancellation.

---

## 🏗️ Project Architecture

```
app/src/main/java/com/omni/assistant/
├── OmniApplication.kt           # App container & dependency initialization
├── MainActivity.kt              # Root activity with edge-to-edge support
├── core/
│   ├── common/                  # OmniResult, DispatcherProvider
│   ├── logging/                 # OmniLogger (structured, credential-redacted)
│   └── permissions/             # PermissionChecker
├── domain/
│   ├── model/                   # DeviceStatus, BatteryStatus, StorageStatus, TaskItem, ChatMessage
│   ├── repository/              # Repository contracts (Chat, Task, Settings)
│   └── usecase/                 # ProcessCommandUseCase, GetDeviceInfoUseCase, etc.
├── data/
│   ├── local/
│   │   ├── database/            # OmniDatabase (Room)
│   │   ├── dao/                 # ChatMessageDao, TaskDao
│   │   └── entity/              # ChatMessageEntity, TaskEntity
│   └── repository/              # Repository implementations
├── device/                      # Telemetry providers (DeviceInfo, Battery, Storage, Root)
├── execution/                   # ToolDefinition, ToolResult, OmniTool, ToolRegistry, CommandDispatcher
├── ai/                          # AI Brain & Orchestration
│   ├── AiModels.kt              # Provider-agnostic models (AiRequest, AiResponse, AiMessage, AiToolCall)
│   ├── LocalCommandProcessor.kt # Deterministic local command matcher
│   ├── config/                  # AiConfigRepository (safe on-device API key storage)
│   ├── gemini/                  # GeminiAiProvider (Google AI Studio REST integration)
│   └── orchestrator/            # AgentOrchestrator (bounded multi-turn tool execution loop)
├── security/                    # ActionPolicy, StandardActionPolicy
└── presentation/
    ├── navigation/              # Screen, OmniNavHost, BottomNavigationBar
    ├── theme/                   # Color, Type, Shape, Theme (Dark OLED default)
    ├── home/                    # HomeScreen & HomeViewModel
    ├── chat/                    # ChatScreen & ChatViewModel (live agent progress)
    ├── tasks/                   # TasksScreen & TasksViewModel
    └── settings/                # SettingsScreen & SettingsViewModel (Gemini API key & model configuration)
```

---

## 🚀 Building and Testing

### Prerequisites
- **JDK**: Java 21 (OpenJDK 21)
- **Android SDK**: Compile SDK 35, Min SDK 29 (Android 10+)
- **Gradle**: 8.11.1 (via included wrapper `./gradlew`)

### Build Commands

Run all 25 unit tests:
```bash
./gradlew test
```

Build debug APK:
```bash
./gradlew assembleDebug
```

The compiled APK will be located at:
```
app/build/outputs/apk/debug/app-debug.apk
```

---

## 📲 Installing on Connected Android Device

```bash
# Verify device connection (e.g. OnePlus 12R via USB or Wireless ADB)
adb devices

# Install APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch Omni Assistant
adb shell am start -n com.omni.assistant.debug/com.omni.assistant.MainActivity
```

---

## 🔑 Configuring Cloud AI (Gemini API)

1. Open **Omni** on your device and navigate to the **Settings** tab.
2. In the **AI Brain & Intelligence** section, enter your [Google AI Studio](https://aistudio.google.com/) API Key (`AIzaSy...`).
3. Tap **Save Key**.
4. Select your preferred Gemini model (`gemini-2.0-flash` is selected by default).
5. Switch to the **Chat** tab to issue natural-language instructions (e.g., *"How much free space do I have left?"*, *"Inspect my battery health"*).
