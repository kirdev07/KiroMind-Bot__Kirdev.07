# KiroMind Bot 1.0 — project architecture

```
app/src/main/java/com/kirdev/kiromindbot/
├── MainActivity.kt
├── core/
│   ├── designsystem/       # Material 3 + AMOLED tokens
│   ├── navigation/         # routes, bottom navigation, nested screens
│   ├── ui/                 # reusable UI components (SettingRow, SwitchRow, Panel…)
│   ├── di/                 # AppGraph: app-lifetime singletons and wiring
│   ├── database/           # KiroMindDatabase (Room v4) and migrations
│   ├── service/            # BotService (foreground, specialUse) and alerts
│   └── state/              # BotSession: on/off state and the terminal journal
└── feature/
    ├── home/ui/            # dashboard and circular power button
    ├── terminal/           # typed events, filterable monitor, journal export
    ├── answers/            # AnswerEntry (variants, media, category, exclusions), Room repository, editor
    ├── engine/             # MindCore (FuzzyMindCore, AnswerIndex), MessagePipeline, test chat
    ├── learning/           # «Без ответа» queue (Room), search and bulk hide
    ├── protection/         # anti-spam, blacklist
    ├── ai/                 # external AI providers behind one router (Claude, Gemini, YandexGPT, OpenAI-compatible)
    ├── telegram/           # Bot API client, long polling runner, file_id cache, settings UI
    ├── backup/             # .kmbackup.json export/import and local backups
    ├── update/             # self-update from GitHub Releases
    ├── settings/           # AppPreferences (DataStore) and settings screens
    └── security/           # Keystore AES-GCM SecretStore
```

## Layers

- `domain` has no Android, Compose, Room or network dependencies and is unit-tested on the JVM.
- `data` holds Room, DataStore, HTTP and Android APIs. `ui` holds Compose screens; screens get managers from `AppGraph`.
- Telegram network code lives only in `feature/telegram`. Other features see only `ConnectionStatus`. The shared database contains its file_id cache table.

## MindCore

- Accepts a plain user string and a read-only list of answers, returns a typed `MatchResult`.
- Normalization → exact phrase → trigram index candidates → token similarity with a threshold and an ambiguity margin.
- Follow-ups (`afterEntryId`) apply only in their context. Exclusion phrases (whole words) block an answer. The category does not affect matching.
- Independent clean-room code. Never copy older Kiro Bot / FP iHA Bot implementations. Third-party licenses are in `THIRD_PARTY_NOTICES.md`.

## Storage

- Room tables: `answers` (with `category`, `exclusions`), `answer_variants`, `answer_media`, `unknown_questions`, `blocked_users`, `telegram_file_cache`.
- Every schema version has an explicit migration and an exported schema in `app/schemas`. Migrations are tested on the device (`StorageTest`).
- Media via SAF (`ACTION_OPEN_DOCUMENT`) with persisted read permission. Size limits are checked before upload.

## Security

- Tokens and API keys: Android Keystore AES-GCM. Encryption errors fail closed, with no plaintext fallback.
- The journal holds no message text, tokens or IDs. Backups never include secrets.
- The bot runs only after the owner turns it on (`BotSession.running` + `BotService`).
