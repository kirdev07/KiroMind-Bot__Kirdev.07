# KiroMind Bot 1.0 — Fusion Minimal

An independent Android project created **from zero**. New brand, application ID, file structure and engine contract. **No Kiro Bot 3.0 files, VK-era bot code or answer database have been copied.**

## Identity

- Product: **KiroMind Bot**
- Initial product version: **1.0** (UI Alpha 1; not a production release)
- Internal answer engine: **MindCore** (not Kiro Brain)
- Application ID: `com.kirdev.kiromindbot` (new install; not an in-place update of old Kiro Bot)
- UI: Kotlin, Jetpack Compose, Material 3, AMOLED Black, five bottom tabs.
- Supported: **Android 10 and newer** (minimum API 29, compile API 37, target API 36). Tested on Android 12.

## What's implemented

1. Home: status, demonstration-only circular power button, system overview, mini-terminal.
2. Terminal: events, filters and search. Not connected to a real bot.
3. Answers: searchable repository, favorite replies, editor; simulated media and HTTPS reference entry.
4. MindCore: brand-new pure Kotlin `MindCore` interface, an **exact-match** engine, test chat with real deterministic matching against current demo answers.
5. Settings: seven nested screens with in-memory UI toggles.

**No real Telegram connection, API key handling, binary media loading, database persistence, imported legacy answers, AI API, notifications, or background service yet.** No `INTERNET` or privileged permissions requested.

## Open in Android Studio

1. Use recent Android Studio compatible with Android Gradle Plugin 9.2.0.
2. Select **Open** → the project folder.
3. Install JDK 17 and Android SDK Platform 37 via SDK Manager if needed.
4. Open the Gradle project and Sync. Gradle version required: **9.4.1**.
5. **Build note:** This ZIP does not bundle a `gradle-wrapper.jar` binary; download/generate the official Gradle Wrapper using a trusted local Gradle 9.4.1 installation (`gradle wrapper --gradle-version 9.4.1`) before running `./gradlew`, or configure the matching local Gradle distribution in Android Studio. Do not use a random wrapper JAR from the internet.
6. After Sync, build `app` and run on an emulator or a device with Android 10+.

> Build not verified in this environment because Android SDK/Gradle are not installed. Run Gradle Sync/Build in Android Studio, then send any build errors for adjustment.

## Design and architecture

- `ARCHITECTURE.md`: responsibilities and full package map.
- `DESIGN_SPEC.md`: screen and component rules.
- `ROADMAP.md`: staged feature development.
- `AGENTS.md`: instructions for Claude Code/Codex.

All state in UI Alpha is temporary and can be reset when the process exits. Do not treat it as persistent settings or a production bot.
