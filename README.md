# Omni — Personal AI Agent for Android (Phase 1)

Omni is an open, extensible personal AI agent application built natively for Android using Jetpack Compose, Material 3, Clean Architecture, and Room.

Phase 1 provides the core foundation: real device telemetry inspection, type-safe tool registration and execution framework, persistent chat & task management, and an obsidian-dark personal AI interface.

---

## 🌟 Features Implemented in Phase 1

1. **Clean Architecture Foundation**:
   - **Presentation**: Jetpack Compose, Material 3, custom OLED dark palette, system insets & edge-to-edge support.
   - **Domain**: Decoupled models, tool registry abstractions, use cases, and action security policies.
   - **Data**: Room database (`chat_messages` and `tasks` tables), DAOs, repositories, and preferences.
   - **Device Telemetry**: Real Android APIs for device model, OS, battery, storage, and scoped package inventory.
   - **AI Layer**: Deterministic local command processor for normalized intent matching.
   - **Execution Engine**: Type-safe `OmniTool`, `ToolRegistry`, `CommandDispatcher`, and `ActionPolicy`.

2. **Real Device Information Providers**:
   - `DeviceInfoProvider`: Hardware manufacturer, model, Android release, SDK API level, and app version.
   - `StorageInfoProvider`: Primary user volume (`/data`) inspection with total, used, and available bytes.
   - `BatteryInfoProvider`: Real battery percentage, charging state, and power source via Android `BatteryManager`.
   - `AppInventoryProvider`: Graceful scoped application inventory respecting modern Android package visibility rules.
   - `RootDetector`: Passive, safe inspection of standard root indicators (binary presence and test-keys) returning `AVAILABLE`, `NOT_AVAILABLE`, or `UNKNOWN` without executing privileged shell commands.

3. **Registered System Tools**:
   - `get_device_info`: Hardware, model, OS release, SDK API level, app version, root status.
   - `get_storage_info`: Primary data volume capacity, available space, used space, percentage.
   - `get_battery_info`: Battery percentage, charging status, health, temperature.
   - `get_app_version`: Omni app version and phase identity.

4. **Deterministic Local Natural Language Processing**:
   - Matches and normalizes supported commands:
     - `"Show device information."`
     - `"Check my storage."`
     - `"Check my battery."`
     - `"What version of Omni am I using?"`
   - Honest fallback for unsupported commands:
     > *"This command is not supported yet. Additional AI and device-control capabilities will be added in later phases."*

5. **Screens & User Interface**:
   - **Dashboard (Home)**: Live telemetry cards (Hardware, Root status, Battery, Storage progress bar), quick action chips, command bar with send and microphone placeholder, and recent tasks preview.
   - **Chat**: Conversation stream, message bubbles, timestamps, typing indicators, suggestion chips, and clear history.
   - **Tasks**: Historical execution log with status badges (`PENDING`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`), timing, request details, and result summaries.
   - **Settings**: App version, theme mode selector (Dark, Light, System), registered tool catalog with risk levels, permissions status, and privacy manifesto.

---

## 🏗️ Project Architecture

```
app/src/main/java/com/omni/assistant/
├── OmniApplication.kt           # App container & dependency initialization
├── MainActivity.kt              # Root activity with edge-to-edge support
├── core/
│   ├── common/                  # OmniResult, DispatcherProvider
│   ├── logging/                 # OmniLogger (structured, credential-safe)
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
├── ai/                          # AiProvider, LocalCommandProcessor, AiRequest/Response
├── security/                    # ActionPolicy, StandardActionPolicy
└── presentation/
    ├── navigation/              # Screen, OmniNavHost, BottomNavigationBar
    ├── theme/                   # Color, Type, Shape, Theme (Dark OLED default)
    ├── home/                    # HomeScreen & HomeViewModel
    ├── chat/                    # ChatScreen & ChatViewModel
    ├── tasks/                   # TasksScreen & TasksViewModel
    └── settings/                # SettingsScreen & SettingsViewModel
```

---

## 🚀 Building and Testing

### Prerequisites
- **JDK**: Java 21 (OpenJDK 21)
- **Android SDK**: Compile SDK 35, Min SDK 29 (Android 10+)
- **Gradle**: 8.11.1 (via included wrapper `./gradlew`)

### Build Commands

Run unit tests:
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

Ensure USB debugging is enabled on your device (e.g. OnePlus 12R):

```bash
# Verify device connection
adb devices

# Install APK directly
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch Omni Assistant
adb shell am start -n com.omni.assistant.debug/com.omni.assistant.MainActivity
```

---

## 🔮 Roadmap: Deferred to Phase 2
- Cloud LLM & Local On-Device AI models (Gemini Nano / LiteRT / Local SLM).
- Real voice recognition and TTS audio responses.
- Accessibility service integration for authorized UI automation.
- Root execution sandbox for privileged device operations.
- Multi-step task planner and self-correcting error recovery loops.
