# PROJECT OMNI
## Phase 1 — Android Foundation and Device Intelligence

### 1. Your Role

Act as a senior Android engineer and software architect.

Build the first working version of Project Omni, a native Android application designed to become a personal AI agent capable of understanding natural-language instructions and performing authorized operations on the user's Android device.

Do not merely generate a design or a project plan. Implement the application, create the required source files, configure dependencies, and verify that the project builds.

Work directly in the current project directory.

If a project already exists, inspect it before modifying anything. Preserve existing work where possible.

### 2. Product Vision

Project Omni is a personal AI-operated Android environment.

The long-term system will support:

- Natural-language commands.
- Voice interaction.
- Device inspection.
- Application launching.
- UI automation.
- File operations.
- Notification access with permission.
- AI-driven task planning.
- Multi-step task execution.
- Optional cloud AI.
- Optional local AI.
- Controlled root operations on supported rooted devices.
- Task verification and error recovery.

This phase implements the foundation only.

Do not claim that capabilities have been implemented unless they actually work.

### 3. Target Platform

Primary development device:

- Device: OnePlus 12R.
- Model: CPH2585.
- Android version: Android 16.
- Development environment: Android Studio or Google Antigravity.
- Root availability: The primary development device is rooted, but Phase 1 must work without root.

Configure the project for a modern Android environment.

Use a minimum SDK of 29 unless a dependency or implementation requirement justifies a different value.

Choose a compile SDK and target SDK supported by the installed Android SDK.

Use current stable, mutually compatible versions of Kotlin, Android Gradle Plugin, Jetpack Compose, Room, and related libraries. Inspect the available build environment before selecting versions.

Do not invent dependency versions. Ensure that the selected versions are mutually compatible.

### 4. Application Identity

Application name: Omni

Suggested application ID:

`com.omni.assistant`

Use a clean, maintainable package structure.

Do not use an existing commercial application's branding, assets, or proprietary implementation.

### 5. Architecture

Use Clean Architecture with clear separation between:

- Presentation
- Domain
- Data
- Device integration
- AI integration
- Task execution

Suggested structure:

app/
  src/main/java/com/omni/assistant/
    OmniApplication.kt
    MainActivity.kt

    core/
      common/
      permissions/
      logging/

    presentation/
      navigation/
      theme/
      home/
      chat/
      dashboard/
      tasks/
      settings/

    domain/
      model/
      repository/
      usecase/
      tools/
      task/

    data/
      local/
        database/
        dao/
        entity/
      repository/
      preferences/

    device/
      DeviceInfoProvider.kt
      StorageInfoProvider.kt
      BatteryInfoProvider.kt
      AppInventoryProvider.kt

    ai/
      AiProvider.kt
      AiRequest.kt
      AiResponse.kt

    execution/
      ToolRegistry.kt
      ToolDefinition.kt
      ToolResult.kt
      CommandDispatcher.kt

    security/
      ActionPolicy.kt
      PermissionChecker.kt

    service/

Adjust the structure when necessary, but preserve the separation of concerns.

Do not place all business logic inside MainActivity or Compose screens.

Use Kotlin coroutines and Flow where appropriate.

Use dependency injection only if it materially improves maintainability. Prefer a simple, working implementation over unnecessary abstraction.

### 6. User Interface

Build a polished native Android interface using Jetpack Compose and Material 3.

Design direction:

- Modern personal AI assistant.
- Premium, minimal visual language.
- Dark theme as the initial default.
- Comfortable typography.
- Clear visual hierarchy.
- Rounded but restrained components.
- Smooth, subtle animations.
- Accessible contrast.
- Responsive layouts.
- Proper support for system insets and edge-to-edge display.

Avoid excessive gradients, visual clutter, and unnecessary decorative elements.

The interface must feel like a real application rather than a generic starter template.

Implement the following screens.

#### A. Home / Dashboard

Display:

- Omni branding.
- Greeting or assistant status.
- Device model.
- Android version.
- Battery percentage when available.
- Storage used and available.
- Root status as UNKNOWN, AVAILABLE, or NOT AVAILABLE.
- AI connection status.
- Recent tasks.
- A prominent command input.
- A microphone button placeholder for a later phase.

Root status must not be inferred from the device model. It should be detected only when a suitable check is available. Otherwise, display UNKNOWN.

Do not execute privileged commands merely to determine root status.

#### B. Chat Screen

Create a functional chat interface with:

- User messages.
- Assistant messages.
- Message timestamps.
- Loading indicator.
- Error states.
- Auto-scrolling behavior.
- Text input.
- Send button.
- Keyboard handling.
- Empty-state guidance.

Persist chat messages using Room.

For this phase, implement a local command processor. Do not pretend that a real AI model is connected.

If the user submits an unsupported natural-language request, explain that the capability is not implemented yet.

#### C. Tasks Screen

Display task history.

Each task should include:

- Task identifier.
- Original request.
- Status.
- Creation time.
- Completion time when applicable.
- Short result summary.

Supported statuses:

- PENDING
- RUNNING
- SUCCEEDED
- FAILED
- CANCELLED

Implement basic task persistence and status updates.

#### D. Settings Screen

Include:

- Application version.
- Theme preference.
- AI provider status.
- Device-control capabilities.
- Permission status.
- Privacy information.

Do not display API keys or other credentials.

Do not create nonfunctional settings that falsely suggest a feature is available.

### 7. Device Information Providers

Implement actual device information retrieval using Android APIs.

#### DeviceInfoProvider

Retrieve supported information such as:

- Manufacturer.
- Device model.
- Android release.
- SDK version.
- Application version.

#### StorageInfoProvider

Retrieve:

- Total storage.
- Available storage.
- Used storage, calculated from total and available values where appropriate.

Clearly identify the storage volume being reported.

Do not claim to measure all physical storage volumes when only one volume is inspected.

#### BatteryInfoProvider

Retrieve the current battery information available through supported Android APIs, including percentage when available.

Handle unavailable information gracefully.

#### AppInventoryProvider

Implement a basic installed-application inventory only to the extent allowed by Android package visibility restrictions.

Do not request QUERY_ALL_PACKAGES by default.

Use narrowly scoped package visibility where possible.

Handle unavailable or restricted package information without crashing.

### 8. Tool Execution Framework

Create a generic tool registry and dispatcher.

The future AI must be able to request registered tools rather than execute arbitrary code.

Define a tool abstraction with:

- Stable tool name.
- Description.
- Typed or validated arguments.
- Required permissions.
- Risk classification.
- Execution handler.
- Structured result.
- Error information.

Implement these initial tools:

1. `get_device_info`
2. `get_storage_info`
3. `get_battery_info`
4. `get_app_version`

These tools must call the actual local implementations.

Return structured results rather than only formatted strings.

Example conceptual result:

{
  "success": true,
  "tool": "get_storage_info",
  "data": {
    "totalBytes": 0,
    "availableBytes": 0,
    "usedBytes": 0
  },
  "error": null
}

The example values are placeholders for the schema only. Never hardcode fake device measurements.

Use Kotlin data classes for internal representations. JSON serialization may be used at integration boundaries.

Do not add an unrestricted shell execution tool in this phase.

### 9. Local Command Processing

Implement a deterministic command processor for the following requests:

- "Show device information."
- "Check my storage."
- "Check my battery."
- "What version of Omni am I using?"

The command processor may normalize case and whitespace.

When a command matches, invoke the corresponding registered tool.

Display the actual result.

When a command does not match, respond honestly:

"This command is not supported yet. Additional AI and device-control capabilities will be added in later phases."

Do not simulate successful actions.

Do not invent device information.

### 10. Local Database

Use Room to persist:

- Chat messages.
- Task history.
- Relevant task status updates.

Create appropriate entities, DAOs, and database migrations or an explicit development migration strategy.

Use stable identifiers and timestamps.

Keep database access out of Compose UI functions.

Do not store API credentials, passwords, private message contents, or other sensitive data unnecessarily.

### 11. Permissions and Security

Request only permissions required by the implemented functionality.

Do not request Accessibility Service activation in Phase 1.

Do not request notification access in Phase 1.

Do not request unrestricted file access in Phase 1.

Do not request root privileges in Phase 1.

Do not run shell commands received from user input.

Do not silently execute destructive actions.

Implement a basic ActionPolicy abstraction that future phases can extend.

The application must continue to work if optional capabilities are unavailable.

### 12. Error Handling

Handle:

- Missing or unavailable system information.
- Unsupported commands.
- Database errors.
- UI lifecycle changes.
- Empty input.
- Corrupt or unavailable persisted data.
- Unexpected exceptions.

Use structured error results.

Do not swallow exceptions silently.

Avoid exposing stack traces or sensitive internal information to the user.

Provide useful diagnostic logs without recording private message contents or credentials.

### 13. Testing

Add unit tests for:

- Command normalization.
- Supported command matching.
- Unsupported command handling.
- Tool registration.
- Tool result success and failure.
- Storage calculations.
- Task status transitions.

Add Android tests where the environment supports them.

Verify that:

- The app launches.
- Navigation works.
- Chat messages persist.
- Tasks persist.
- Device information comes from real Android APIs.
- Unsupported requests are not falsely reported as successful.
- The project compiles.

Run the available Gradle build and tests.

If the build fails, investigate and fix the actual cause rather than suppressing the error.

### 14. Build and Project Quality

Create a complete, buildable Android project.

Include:

- Gradle configuration.
- Android manifest.
- Required resources.
- Theme configuration.
- Source code.
- Tests.
- README.md.
- PHASE_01_FOUNDATION.md containing this specification.

Do not commit generated build directories.

Do not hardcode credentials.

Do not disable Android security protections to make the project compile.

Do not silently remove existing project functionality.

### 15. Completion Report

When finished, provide:

1. Summary of implemented functionality.
2. Project directory structure.
3. Important implementation decisions.
4. Dependencies and their selected versions.
5. Build command used.
6. Build and test results.
7. Any outstanding errors.
8. Instructions for installing the APK on the connected Android device.
9. Features intentionally deferred to Phase 2.

Be explicit about anything you could not test.

The success criterion is a working Android application with a polished interface, persistent local data, real device information tools, and a clean foundation for future AI-driven device control.
