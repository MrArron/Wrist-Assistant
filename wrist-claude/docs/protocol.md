# Wrist Assistant — design and watch/phone protocol (v1)

General-purpose Claude on the wrist. Press, speak, read a short answer.

## Features (v1)

1. **Ask** — voice dictation on the watch, answer shown in a scrollable view.
2. **Follow-up** — press Select on an answer to ask a follow-up in the same conversation.
3. **Quick prompts** — up to 5 one-press prompts set on the phone settings page
   (e.g. "Give me a fun fact", "Motivate me"). Shown in the watch menu. A prompt containing
   `{topic}` gets a random seed word and angle on each press (`src/pkjs/topics.js`,
   companion `QuickTopics.kt`), plus that prompt's last 5 answers as "do not repeat".
4. **Reminders** — "remind me in 20 minutes to check the oven" makes Claude call a
   `set_reminder` tool; the watch schedules a wakeup that vibrates and shows the text,
   even if the app is closed. Snooze 10 min or dismiss.
5. **History** — last 5 Q&As kept on the watch (readable offline).
6. **Web search** (optional setting, default on) — for current info like weather, scores, news.

Later (not in v1): send answer to phone as a notification, canned replies, timeline pins.

## Message keys (package.json `messageKeys`)

| Key | Dir | Type | Meaning |
|---|---|---|---|
| `ASK_TEXT` | W→P | cstring | Dictated question (≤ 500 bytes) |
| `ASK_KIND` | W→P | int | 0 = new conversation, 1 = follow-up, 2 = quick prompt |
| `QUICK_INDEX` | W→P | int | Index of the quick prompt (with ASK_KIND 2) |
| `READY` | both | int | W→P on launch: request quick prompt names + key status. P→W reply: 1 if API key set, 0 if not |
| `QUICK_NAMES` | P→W | cstring | Quick prompt labels joined by `\n` (≤ 5 labels, each ≤ 24 bytes) |
| `STATUS` | P→W | cstring | Progress line, e.g. "Thinking...", "Searching the web..." |
| `CHUNK` | P→W | cstring | Next piece of the answer (≤ 180 bytes, UTF-8 safe split) |
| `CHUNK_FINAL` | P→W | int | 1 on the last chunk message (may be sent with the last CHUNK) |
| `ERROR` | P→W | cstring | Error text to show instead of an answer (≤ 180 bytes) |
| `REMINDER_TIME` | P→W | int | Unix time (seconds, UTC) for a reminder |
| `REMINDER_TEXT` | P→W | cstring | Reminder text (≤ 60 bytes); sent together with REMINDER_TIME |
| `APP_VERSION_KEY` | both | int | Sender's version, 2.2.1 = 221 (W→P with READY, P→W in the companion hello; see companion.md) |

Flow for one answer: watch sends ASK → phone sends STATUS (optional, repeatable) →
phone may send REMINDER_TIME+REMINDER_TEXT → phone sends CHUNK messages in order,
the last with CHUNK_FINAL=1 → or a single ERROR. The phone sends one message at a
time and waits for the ACK before the next (retry a NACK up to 3 times).

Answers are capped by the phone at 1,500 bytes total and are plain text
(no markdown), with characters the watch fonts lack replaced (smart quotes → ASCII,
emoji removed).

## Phone settings (localStorage key `wc_settings`, JSON)

| Field | Default | Notes |
|---|---|---|
| `apiKey` | "" | Anthropic API key. Never logged. |
| `model` | `claude-haiku-5-5` | also `claude-sonnet-5-5`, `claude-opus-5-5` |
| `length` | `short` | short ≈ 40 words, medium ≈ 80, long ≈ 150 |
| `webSearch` | true | adds the `web_search_20250305` server tool (max_uses 3) |
| `units` | `imperial` | or `metric` |
| `city` | "" | optional, for web search `user_location` and local answers |
| `aboutMe` | "" | optional, added to the system prompt |
| `quickPrompts` | 3 defaults | array of `{label, prompt}`, max 5 |

## Watch persistent storage

| Key | Content |
|---|---|
| 1 | quick prompt names (cstring, ≤ 128 B) |
| 2 | api key set flag (int) |
| 10–14 | history entries, newest at 10: `question\x1fanswer`, ≤ 255 B, answer truncated with "..." |
| 20–27 | reminders: struct { int32 wakeup_id; int32 time; char text[64]; } |
