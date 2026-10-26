# KiroMind Bot 1.0 — staged plan

## Alpha 1 — independent UI foundation (current)
- [x] New name / package / folder architecture.
- [x] Five Compose sections and nested settings.
- [x] New MindCore interface and exact resolver.
- [x] Temporary answer repository / media metadata and test chat.
- [x] Gradle build on device; TalkBack pass: switch rows are one labeled switch (`SwitchRow`), power button has role Button, queue checkboxes are labeled. (Emulator matrix — with Alpha 5 smoke tests.)

## Alpha 2 — durable answers
- [x] Add Room repository, migrations baseline (v1 + exported schema), transactional media and variants.
- [x] File picker (SAF, persisted read permission), Telegram upload with file_id cache, size limits. (Thumbnails — later.)
- [x] Unanswered queue, query indexes, verified import/export (`.kmbackup.json`) and local backups. Queue search, sorting (новые/частые) and bulk hide with confirmation.

## Alpha 3 — MindCore engine
- [x] Independent normalization/tokenization, scoring and threshold tests (`FuzzyMindCore`, clean-room).
- [x] Variant selection, styles/persona, follow-up context, repeat prevention, unknown-question queue, message pipeline. Categories (grouping and base filter) and exclusion phrases (whole-word, block an answer).
- [x] 20k synthetic benchmark in unit tests: ~5 ms/query on desktop JVM. On the phone: index 6.6 MB at 15k and 13.4 MB at 30k entries (~0.45 KB per entry).

## Alpha 4 — Telegram transport
- [x] Telegram Bot API client (HTTPS long polling), Keystore token storage, connection diagnostics.
- [x] Foreground service (specialUse) started only by the power button, with notification.
- [x] Backlog skip, retries/backoff, 429 handling, per-chat spacing, group rules, owner-only /teach. (Media upload of local files — later.)

## Postponed
- [ ] Autostart after update on MIUI: blocked by MIUI autostart permission (MIUIOP 10008), resolve later.

## Alpha 5 — external AI, privacy and quality
- [x] Claude provider (official Anthropic SDK): Keystore key, per-minute and daily limits, opt-in context sharing. Gemini, YandexGPT and OpenAI-compatible (Groq/OpenRouter/OpenAI/self-hosted) via one router. GigaChat is intentionally not included (owner decision).
- [x] Anti-spam (flood/repeat, optional auto-block), persistent blacklist, owner commands. Journal export from the terminal.
- [x] Supported: Android 10 and newer (minSdk 29, targetSdk 36). Tested on a Xiaomi M2102J20SG phone (Android 12); a separate check on every version is not planned (owner decision).
- [x] Release signing (v1.0.0).
- [ ] Architecture cleanup, licensing review.

Do not claim Alpha 1 is a working production Telegram bot.
