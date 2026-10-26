# Design contract — Fusion Minimal

- AMOLED true black background (`#000000`), charcoal panels (`#121214`), gray dividers (`#2B2B30`), white primary text, muted gray secondary.
- Color only for meaningful statuses: running green, warning amber, error red.
- No visual glows, gradients, heavy cards, or excessive quick actions.
- Five permanent bottom tabs: **Главная / Терминал / База / ИИ / Настройки**.
- Nested editor, test and settings routes retain tab bar and provide Back arrow and system Back behavior.
- Home: compact power button (54–56dp), live status, stats, system status and mini-console.
- Terminal: filter and search, timestamp rows, message severity; all logs redacted by default when real service is connected.
- Answers: dense list, question and one-line answer, media badges, search, favorites, full-screen editor allowing text, media, or both.
- MindCore: separate engine overview and test chat; avoid confusing a local matching algorithm with external AI.
- Settings: seven separate pages accessed through the fifth tab. Avoid duplicated settings controls on Home.
- Support narrow phones, font scaling, TalkBack and contrast; make touch targets 48dp when feasible.
