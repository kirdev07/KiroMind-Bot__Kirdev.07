# KiroMind Bot 1.0 — staged plan

## Alpha 1 — independent UI foundation (current)
- [x] New name / package / folder architecture.
- [x] Five Compose sections and nested settings.
- [x] New MindCore interface and exact resolver.
- [x] Temporary answer repository / media metadata and test chat.
- [ ] Run Android Gradle Sync, emulator build, accessibility checks and fix resulting errors.

## Alpha 2 — durable answers
- [x] Add Room repository, migrations baseline (v1 + exported schema), transactional media and variants.
- [x] File picker (SAF, persisted read permission), Telegram upload with file_id cache, size limits. (Thumbnails — later.)
- [ ] Implement unanswered queue, query indexes, filtering, bulk edit and verified imports.

## Alpha 3 — MindCore engine
- [x] Independent normalization/tokenization, scoring and threshold tests (`FuzzyMindCore`, clean-room).
- [x] Variant selection, styles/persona, follow-up context, repeat prevention, unknown-question queue, message pipeline. (Categories/exclusions — later.)
- [x] 20k synthetic benchmark in unit tests: ~5 ms/query on desktop JVM (memory not yet measured).

## Alpha 4 — Telegram transport
- [x] Telegram Bot API client (HTTPS long polling), Keystore token storage, connection diagnostics.
- [x] Foreground service (specialUse) started only by the power button, with notification.
- [x] Backlog skip, retries/backoff, 429 handling, per-chat spacing, group rules, owner-only /teach. (Media upload of local files — later.)

## Postponed
- [ ] Autostart after update on MIUI: blocked by MIUI autostart permission (MIUIOP 10008), resolve later.

## Alpha 5 — external AI, privacy and quality
- [x] Claude provider (official Anthropic SDK): Keystore key, per-minute and daily limits, opt-in context sharing. Gemini, YandexGPT and OpenAI-compatible (Groq/OpenRouter/OpenAI/self-hosted) via one router. GigaChat is intentionally not included (owner decision).
- [x] Anti-spam (flood/repeat, optional auto-block), persistent blacklist, owner commands. (Log export — later.)
- [ ] Smoke test Android 10–17, architecture cleanup, release signing, licensing review.

Do not claim Alpha 1 is a working production Telegram bot.
