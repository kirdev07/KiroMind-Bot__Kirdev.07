# KiroMind Bot 1.0 — project architecture

```
app/src/main/java/com/kirdev/kiromindbot/
├── MainActivity.kt
├── core/
│   ├── designsystem/       # Material 3 + AMOLED tokens
│   ├── navigation/         # routes, bottom navigation, nested screens
│   ├── ui/                 # reusable UI components
│   └── state/              # isolated temporary UI Alpha session
└── feature/
    ├── home/ui/            # dashboard and circular power button
    ├── terminal/
    │   ├── domain/         # typed events
    │   └── ui/             # filterable monitor
    ├── answers/
    │   ├── domain/         # AnswerEntry, AnswerMedia, MediaKind
    │   ├── data/           # AnswerRepository, disposable demo adapter
    │   └── ui/             # answers list, media-aware editor
    ├── engine/
    │   ├── domain/         # MindCore, MatchResult, independent resolver
    │   └── ui/             # engine controls and test chat
    ├── settings/
    │   ├── domain/         # AppPreferences
    │   └── ui/             # root and seven nested sections
    ├── telegram/api/       # TelegramGateway contract, no implementation
    └── security/           # SecretStore contract, no implementation
```

## New engine: MindCore

- Engine contract accepts a **plain user string** plus a read-only list of answers and returns a typed `MatchResult`.
- No Android `Context`, Fragment, network, Bot API, service, or UI dependencies.
- UI Alpha includes only whitespace/case normalized exact matching, not AI/fuzzy matching.
- Future units: tokenization, searchable index, multiple answer variants, context, rules, permissions, async pipeline, telemetry, performance tests.
- Never copy older Kiro Bot / FP iHA Bot implementation code; document licenses of any adopted third-party library.

## New answers repository

- New typed schema (`AnswerEntry`, `AnswerMedia`), no `question|reply|vk_attachment` convention.
- Future Room tables: `answers`, `answer_variants`, `answer_media`, `unanswered_questions`, `categories`, `rules`.
- Plan indexes on normalized question and foreign keys. Perform a tested migration plan rather than writing directly to old `answer.txt`.
- Real media selection via Android Photo Picker / ACTION_OPEN_DOCUMENT; persist URI permission when appropriate, validate MIME, size, and content before sending; handle API-specific limits.
- Never assume an arbitrary HTTPS link is downloadable media. Preview only after secure validation.

## Telegram isolation

- `TelegramGateway` will implement Bot API with polling/webhooks chosen deliberately. Do not mix network calls into Compose screens or MindCore.
- Tokens and API keys: Android Keystore-backed secret storage; fail closed on encryption errors. No plaintext fallback, no token printing, no logging message content by default.
- Persist only necessary metadata, use small permissions, structured redacted logs, secure backup rules and lifecycle-safe services.
- Bot start/stop must eventually be wired to a real service state, not to temporary `DemoSession.running`.
