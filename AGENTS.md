# Rules for Claude Code / Codex — KiroMind Bot 1.0

1. The user approved the new product name **KiroMind Bot**, version **1.0**, engine **MindCore**. Never use Kiro Bot 4.0 / Kiro Brain for new identifiers, README, UI strings, packages, or branding.
2. This is a clean project. Do not import source code from `kirdev07/Kiro-BOT`, FP iHA Bot or other legacy implementations. Use clean-room specs and user-approved design only.
3. Respect architecture boundaries: Compose UI in `/ui`, domain model and matching in `/domain`, storage in `/data`, Telegram only in `/telegram`.
4. Before implementing new feature, make a short plan: domain contract, UI state, persistence, security, test. One feature per commit.
5. This stage is UI Alpha. All fake values/events/media must say demo; never claim real Telegram connection or file upload.
6. Do not log tokens/IDs/message content indiscriminately. Encrypted secret storage must fail closed. No broad storage permissions or HTTP cleartext.
7. Prioritize APK compile and UX on Android 10+; test Gradle Sync and `testDebugUnitTest` before shipping. If environment lacks SDK, document that rather than claiming success.
8. Maintain independent tests for MindCore and separate Room migrations before real data import.
9. Keep UI minimalist: true black, charcoal, white text and muted gray, five bottom tabs, no decorative gradients.
10. Preserve source repository and existing installs of the former Kiro Bot; KiroMind has an intentionally different `applicationId`.
