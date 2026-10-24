# Wrist Assistant companion for Android (v2 design)

The companion is an Android app (package `com.mrarron.wristclaude`) that talks to the watch app
through **PebbleKit Android 2** (`io.rebble.pebblekit2`, works with the Core Devices Pebble app
1.0.7.7+). When it's installed and set up, it becomes the brain: it holds the API key, calls
Claude, and carries out phone actions. The Pebble-app JS (src/pkjs) stays as the fallback for
people without the companion. Distribution: sideloaded APK. Target: Android 10 (API 29) or newer.

Watch app UUID: `12e8fcb0-c07f-4215-994f-c5e405f093fc`.

## Message keys (numbers are what the C build assigns; the companion uses the numbers)

v1 keys (see protocol.md): ASK_TEXT 10000, ASK_KIND 10001, QUICK_INDEX 10002, STATUS 10003,
CHUNK 10004, CHUNK_FINAL 10005, ERROR 10006, REMINDER_TIME 10007, REMINDER_TEXT 10008,
QUICK_NAMES 10009, READY 10010, APP_VERSION_KEY 10011.

New in v2:

| Key | # | Dir | Type | Meaning |
|---|---|---|---|---|
| `COMPANION` | 10012 | P→W | int (1) | "The companion is here." Sent by the companion when the watch app opens, and included in every READY/QUICK_NAMES message the companion sends. |
| `ROUTE` | 10013 | W→P | int | Sent with every ASK and SPEAK: 1 = for the companion, 0 = for the Pebble-app JS. Each side ignores asks not meant for it. |
| `SPEAK` | 10014 | W→P | int | 1 = read the latest answer aloud on the phone, 0 = stop speaking. |
| `CONFIRM_ID` | 10015 | both | int | Id of a confirmation request (P→W) / the answer to it (W→P). |
| `CONFIRM_TEXT` | 10016 | P→W | cstring | What the user is approving, ≤ 300 bytes, e.g. "Reply to Mom in Messages:\nI'll be there at 6". |
| `CONFIRM_RESULT` | 10017 | W→P | int | 1 = approved (Select), 0 = declined (Back or 60 s timeout). |

`ASK_KIND` gains value **3 = hand off** (see "Notes and handoffs"): ASK_TEXT is empty or an
optional spoken comment, and the companion hands off the current conversation.

### Routing

- The watch starts each launch with `companion = false`. When any message with `COMPANION=1`
  arrives it sets `companion = true` (for this launch) and sends asks with `ROUTE=1`.
- The Pebble-app JS ignores any ASK/SPEAK with `ROUTE=1`. The companion ignores ROUTE 0/missing.
- Once the companion has spoken, the watch ignores QUICK_NAMES/READY that lack `COMPANION=1`,
  so the companion's settings win.

### Answer flow (same as v1)

ASK → STATUS* → (REMINDER)* → (CONFIRM round trips)* → CHUNK… with CHUNK_FINAL=1, or one ERROR.
Chunks ≤ 180 bytes UTF-8, answer ≤ 1,500 bytes, plain text, Latin-1 only (same sanitizing as
src/pkjs/text.js). One message at a time, wait for the result before the next; retry failures
up to 3 times. While waiting on Claude for more than ~20 s, send a STATUS ("Still working...")
so the watch's 70 s timeout doesn't fire; keep the whole ask under 60 s.

### Confirmations

Anything that sends something to another person goes through the watch first: the companion
sends CONFIRM_ID + CONFIRM_TEXT, waits up to 60 s for CONFIRM_RESULT, and tells Claude the
outcome in the tool result ("Sent." / "The user declined." / "No answer from the watch.").

## Companion features (first version)

1. **Claude client** — same behavior as src/pkjs/claude.js: same settings (model, length,
   web search, units, city, about me, quick prompts), same system prompt ideas, conversation
   memory for follow-ups (last 6 messages), web search server tool, tool loop (max 6 rounds).
2. **Watch reminders** — `set_reminder` tool, unchanged: sends REMINDER_TIME/TEXT to the watch.
3. **Drafts** — `create_draft {kind: "text"|"email", to?, subject?, body}`: posts a phone
   notification "Draft ready" with actions **Open in Messages** / **Open in Email** (pre-filled
   intent: `smsto:` / `mailto:` with subject and body) and **Copy**. Also kept in a Drafts list in
   the app. `to` is used only if it's a phone number or email address.
4. **Alarms and timers** — `set_alarm {hour, minute, label?, days?}` and
   `set_timer {seconds, label?}` via `AlarmClock.ACTION_SET_ALARM` / `ACTION_SET_TIMER` with
   `EXTRA_SKIP_UI`. Starting activities from the background needs the "Display over other apps"
   permission (a documented background-activity-start exemption). If it's not granted, post a
   notification "Tap to set alarm 6:30" instead and say so in the tool result.
5. **Calendar** — `get_calendar_events {start, end}` (ISO local date/time) and
   `add_calendar_event {title, start, end?, duration_minutes?, location?, all_day?}` via
   CalendarContract (READ/WRITE_CALENDAR). Adds go to the user's chosen default calendar
   (picked in settings; first writable primary calendar otherwise).
6. **Notifications** — a NotificationListenerService keeps the last 50 notifications in memory
   (app name, title, text, time, whether it has a reply action; skips ongoing/system/own).
   Tools: `get_recent_notifications {app?, limit?}` and
   `reply_to_notification {notification_id, text}` (RemoteInput reply; always confirmed on the
   watch first). Never stored on disk, never sent anywhere except to Claude when asked.
7. **Read aloud** — SPEAK=1 speaks the latest answer with Android TextToSpeech (using the
   media/assistant audio stream with audio focus); SPEAK=0 stops. Setting: speech rate, and
   "Read every answer aloud automatically" (off by default).
8. **Notes and handoffs** — see "Notes and handoffs" below.
9. **Context from the main Claude** — see "Context from the main Claude" below.

## Notes and handoffs (Google Doc inbox)

Lets the user park a note or a whole conversation from the watch and pick it up later in their
regular Claude app (claude.ai), where "catch up on my notes" works through the open items.

### The notes doc

- One Google Doc, **"Wrist Assistant Notes"**, **created by the app itself** in the user's Drive (My Drive
  root) the first time Google is connected, via Docs API `documents.create`, then a `batchUpdate`
  writes the starting text: the title, the line "Notes and handoffs from the Pebble watch. New entries
  are added at the bottom. Say "catch up on my notes" in Claude to work through the OPEN ones.", and
  an empty line (entries are appended with a leading newline). No doc links are pasted anywhere.
- Google sign-in: Identity `AuthorizationClient` (play-services-auth) for the single scope
  `https://www.googleapis.com/auth/drive.file`. It is non-sensitive (no Google verification needed
  to publish) and only covers files the app itself created, which is why the app makes its own docs.
  The Docs API accepts drive.file tokens for those files (`documents.get`, `batchUpdate`). The only
  Drive API call is `files.list` (below), which under drive.file returns just the app's own files.
- App-created docs: the ids are stored in settings (`notesDoc`, `contextDoc`) with a `docsAppCreated`
  flag. Each doc is ensured on its own (a context-doc problem never blocks notes). Before creating, the
  app **adopts**: Drive `files.list` (`name='Wrist Assistant Notes' and mimeType='application/vnd.google-apps.document'
  and trashed=false`, newest `modifiedTime` first) and uses the newest match; only if none exists does it
  call `documents.create`. Lookup, create and saving the id run non-cancellable, so a timeout or leaving
  the screen can't lose a doc Google already made, and a retry adopts it instead of creating another. The
  text is written afterwards; if that fails it is retried on the next call (a doc that already has text
  is never written again). Creation is serialized by a lock. It runs on connect and lazily before any
  notes operation (the context doc only when "Use context from Claude" is on). If a stored doc returns 404
  the app re-reads to confirm, doesn't recreate if Google still lists it, recreates each id at most once
  and at most once an hour, and then adopts or creates a doc and the Notes screen says so.
- Disconnect forgets the doc ids ("Your old docs stay in Google Drive."), so the next connect adopts or
  creates this account's docs. On a 403 for its own doc the Notes tab offers **Make new docs**, which
  creates fresh docs without adopting.
- Notes still queued when the user disconnects stay queued and go to the notes doc of whichever Google
  account connects next.
- Setup (first connect, Disconnect, Make new docs) runs in the app scope, so leaving the screen can't
  cancel it. A save that finds no notes doc yet queues the note, starts setup in the background and
  tells the watch "setting up your notes doc". "Make new docs" is offered only after a doc-level
  PERMISSION_DENIED and replaces only the refused doc(s), never one made during that recovery.
- The context doc's placeholder header (`(not filled yet)`) is never sent to Claude as background.
- Migration: ids from before this scope change (the old defaults or pasted links, `docsAppCreated`
  absent) can't be opened under drive.file and are treated as "no doc yet"; new docs are created on
  the next connect (the user must approve the new scope). Old docs are left alone in Drive. Entries
  already queued wait (the queue stores no doc id any more) and go to the new notes doc once it exists.
  The Notes tab shows "Not created yet - connect Google" until then. Steps when upgrading from
  2.1.0 dev builds: the old "Wrist Claude Notes" / "Wrist Claude Context" docs and the new
  "Wrist Assistant Notes" / "Wrist Assistant Context" (Drive root) both exist.
  Move the OPEN and WAITING entries from the old doc into the new one, rename the old docs
  "Wrist Claude Notes (old)" and "Wrist Claude Context (old)", then re-point the "catch up on my notes"
  skill (it finds the notes doc by exact title and ignores "(old)") and the scheduled task that refreshes
  the context doc at the new context doc.
- Docs API `documents.batchUpdate` with `insertText` at `endOfSegmentLocation`.
- Offline or failed writes, and anything saved before the doc exists, go to a local queue (encrypted, same Keystore key as the API key)
  and are retried when the network is back; the Notes screen shows anything still queued.

### Entry format (fixed, so Claude can parse it)

Each entry is appended at the end of the doc as plain text:

```
=== OPEN | 2026-10-15 17:49 | NOTE | kitchen ===
Buy more coffee filters.

=== OPEN | 2026-10-15 18:02 | HANDOFF | Trip planning ===
Goal: Pick a campsite within two hours' drive.
Where it stands: Claude suggested three lakeside campsites; I want distances and prices.
Next step: Compare the three campsites' fees and draft a short list.
Conversation:
Q: which campsites are within two hours' drive
A: Three lakeside campsites are close enough...
Q: ...
```

- Header fields: status (`OPEN` / `DONE` / `WAITING`), local date and time, kind
  (`NOTE`, `FOLLOWUP`, `HANDOFF`), project (free text, may be empty).
- Claude (in claude.ai) changes `OPEN` to `DONE` or `WAITING` and may add a line
  `Result: ...` under the entry. The companion never edits existing entries, with one exception:
  the daily tidy below removes DONE entries (and only those).

### Daily tidy (removing DONE entries)

So the doc doesn't grow forever (every read by the main Claude costs tokens), the companion deletes
entries whose header status is `DONE`.

- Header match: a paragraph whose whole text matches `=== DONE | ... ===` (case-sensitive `DONE`,
  surrounding whitespace ignored). Never OPEN or WAITING, never text that merely contains "DONE".
  The title and intro are never touched; only the blank line the companion wrote before an entry goes
  with it.
- An entry runs from its header to the next entry header (taking its trailing blank line), or for
  the last entry to the end of the body minus the final newline (which the Docs API can't delete).
  Any paragraph starting with `===` ends the previous entry, and the last entry stops before its trailing
  empty paragraphs. An entry with an empty paragraph followed by more text (a multi-paragraph note, or
  something added by hand) is ambiguous, so it is skipped entirely rather than deleted partly. An entry containing anything other than
  paragraphs (a table, say) is skipped.
- One `documents.batchUpdate` of `deleteContentRange` requests, highest index first, with
  `writeControl.requiredRevisionId` from the same `documents.get`. If the doc changed in between,
  Google rejects it and the companion simply tries again next time (no retry loop).
- When: at most once per 24 h when the watch app opens, and when the user taps **Tidy now** on the
  Notes tab. Only when Google is connected and the offline queue is empty (it is flushed first).
  Off the main thread. Setting "Remove done notes from the doc" (default on); off turns off the
  automatic daily tidy, and **Tidy now** still removes DONE entries when tapped. After a failed
  attempt the automatic tidy waits an hour before trying again, and the Notes tab shows why it failed.
- The Notes tab shows "Last tidy: <time>, removed N done notes". Doc text is never logged.

### Tools Claude can call (on the phone)

- `save_note {text, kind: "note"|"followup", project?}` — appends a NOTE or FOLLOWUP entry.
  Tool result: "Saved to your notes." Used for "note: …", "remind me to look into …",
  "follow up on … later", "add to my notes".
- `create_handoff {title, project?, goal, status, next_step, include_conversation: true}` —
  appends a HANDOFF entry. Claude writes goal / status / next step; the companion adds the
  conversation (last 6 messages, each answer cut to 600 characters) itself, so it is exact.
  Used for "continue on Claude", "continue this on my phone/computer", "hand this off",
  "pick this up later", "send this to Claude".

The system prompt gains: "If the user wants to continue later, in the Claude app or on another
device, call create_handoff. If they want to save something to look at later, call save_note.
Reply in one short line, e.g. 'Handed off — say catch up on my notes in Claude.'"

### Watch side

- On the answer screen, **hold Down** = Hand off (shown in the hint line when the companion is
  present). It sends ASK_KIND=3 with ROUTE=1 and no text; the companion asks Claude to call
  `create_handoff` for the current conversation and answers with the one-line confirmation.
  The handoff reply isn't saved to watch history, and follow-ups continue the real conversation.
- Without the companion (Pebble-app JS route), Hand off is hidden; voice requests to hand off
  get "Hand off needs the phone companion."

### Phone side after a handoff

Post a notification "Handed off: <title>" with actions **Open in Claude** (launches the
Claude Android app with an `ACTION_SEND` text "catch up on my notes", user taps Send) and
**Open notes** (opens the doc). Setting: "Show handoff notification" (on by default).

## Context from the main Claude (Google Doc)

Gives the watch a small bit of background on what the user is working on, so answers and notes
can refer to the right project.

- Doc: **"Wrist Assistant Context"**, also created by the app (same rules as the notes doc), starting as
  `=== WRIST CONTEXT | (not filled yet) ===` / `No context yet. Your main Claude fills this in.` /
  `=== END ===`. The user points a scheduled task in their Claude account at it (the Notes tab has
  "Open context doc"); it rewrites it twice a day (6:46 and 16:46 America/New_York) from memory, recent chats and open notes.
  Plain ASCII, at most ~1,200 characters, between `=== WRIST CONTEXT | <time> ===` and `=== END ===`.
- The companion reads it with the same drive.file token (`documents.get`, join the text runs),
  keeps only the text between the two markers, cuts it to 1,500 characters, and caches it
  (encrypted, like the notes queue). It refreshes when the watch app opens if the cache is older
  than 3 hours, and on "Refresh now" in Settings. Offline: use the cache; no cache: skip it.
- It goes into the system prompt as: "Background on what the user is working on (from their main
  Claude; may be a few hours old; use only when relevant, don't mention it otherwise):\n<text>".
  When Claude calls `save_note` or `create_handoff`, it should set `project` to the matching
  project name from this list.
- Settings: "Use context from Claude" (on by default), last refreshed time,
  Refresh now. The Notes screen shows the current context text.

## Added in 2.2.0

- **Quick Launch → dictation (watch).** When `launch_reason()` is `APP_LAUNCH_QUICK_LAUNCH` and an API key is
  set, the watch opens the answer window and starts dictation right away (on both routes). Cancelling returns to
  the menu.
- **`send_to_phone {text, title?}`.** Posts a notification (channel "Sent from the watch") with the text
  (≤ 3,000 characters), **Copy** and **Share**; tapping it opens the share sheet. Offered whenever drafts are.
  Nothing goes to another person, so no watch confirmation. The prompt tells Claude the phone text may be fuller
  than the watch answer.
- **Phone location.** `ACCESS_COARSE_LOCATION` (+ `ACCESS_BACKGROUND_LOCATION`, since asks arrive in the
  background). `PhoneLocation` takes the newest last-known fix (≤ 2 h old) or a fresh fused/network fix, reverse
  geocodes it on the phone and keeps only city, region, ISO country and neighborhood (memory cache, fresh 15 min,
  used up to 6 h when a lookup fails; lookups give up after 3 s). Warmed when the watch app opens. The place
  replaces the City setting in the system prompt and in web search `user_location`. Setting "Use the phone's
  location" (on by default; does nothing without the permission). Coordinates never leave the phone.
- **Usage meter.** Every API response's `usage` is added to this month's totals (`UsageStore`, plain prefs, counts
  only): questions, input tokens (incl. cache), output tokens, web searches, and an estimate at list prices
  (Haiku 5.5 $0.10/$0.50, Sonnet 5.5 $2/$10, Opus 5.5 $4/$20 per million tokens; cache writes 1.25x, reads 0.1x
  input; $0.01 per search). Settings → Usage shows this month and the previous one. "Monthly limit" (USD, 0 = none):
  once the estimate reaches it, asks return "Monthly spending limit reached..." without calling the API.

## Companion app screens

- **Setup checklist**: API key, Pebble app connection status, permissions (notifications to
  post, calendar, location, notification access, display over other apps, battery optimization
  exemption), Google account for notes, each with a button that opens the right system screen.
- **Settings**: everything from the v1 settings page plus default calendar, speech rate,
  auto-read, notes doc (create / pick / open), handoff notification.
- **Drafts**: recent drafts with Open / Copy / Delete.
- **Notes**: recent entries written from the watch, anything still queued offline, Open doc.
- **Test**: type a question and see the exact watch-formatted answer (no watch needed).

The API key is stored encrypted (Android Keystore) and never logged.
