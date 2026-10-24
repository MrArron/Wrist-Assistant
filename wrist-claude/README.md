# Wrist Assistant

Ask Claude from your Pebble. Press, speak, read a short answer.

Not affiliated with or endorsed by Anthropic. This is the detailed user guide; the project overview is in the [root README](../README.md).

Works on Pebble Time 2, Pebble Round 2, Pebble 2 Duo, Pebble Time, Pebble Time Round and Pebble 2.

## What it does

- **Ask** — press Select, speak your question, get a short plain-text answer that fits the screen.
- **Follow up** — press Select on an answer to keep the conversation going.
- **New question** — hold Select on an answer.
- **Quick prompts** — up to 5 one-press prompts you set on your phone ("Fun fact", "Motivate me"...).
- **Reminders** — say "remind me in 20 minutes to check the oven" or "remind me at 5:30 to call Mom".
  The watch buzzes at that time even if the app is closed. Down = snooze 10 min, Select = done.
  See or cancel them under **Reminders** (hold Select to cancel).
- **History** — your last 5 questions and answers stay on the watch, readable offline.
- **Web search** (on by default) — current info like weather, scores and news.
- **Notes and hand off** (with the phone companion) — save a note for later, or hold Down on an
  answer to hand the conversation off. See "Notes and handoffs" below.

Up/Down scroll an answer a screen at a time.

**Quick Launch.** Set Wrist Assistant as a Quick Launch app (Pebble app → Settings → Quick Launch, then
hold a button on the watch face). Opened that way, it goes straight to the microphone. Back takes you to the menu.

## Android companion (optional, v2)

Install the Wrist Assistant companion app (`wrist-assistant-companion-*.apk`, from GitHub Releases) on your Android phone and
it takes over: your API key lives there, and Claude can also
- draft texts and emails (a phone notification opens them pre-filled in Messages or your email app),
- set alarms and timers in your Clock app,
- check and add calendar events,
- read your recent notifications and reply to them,
- read an answer aloud on your phone (hold Up on an answer),
- save notes and hand off conversations to your notes doc (version 2.1.0),
- send something to your phone: say "send that to my phone" and a notification with **Copy** and **Share**
  shows the text, often in more detail than fits on the watch (2.2.0),
- use your phone's location for "near me" questions and local weather (2.2.0, optional),
- keep a running estimate of what your API key has spent this month, with an optional monthly limit (2.2.0).

Anything sent to another person, and anything triggered after Claude has read notifications or
web results, asks you on the watch first: Select to send, Back to cancel.
Without the companion, the watch app works exactly as before. Design: `docs/companion.md`.

## Notes and handoffs (companion 2.1.0)

Park a note, or a whole conversation, from the watch and pick it up later in the Claude app.
Everything is added to a Google Doc named **Wrist Assistant Notes**, which the app creates in your Drive.

**Save a note.** Say something like:
- "note: buy more coffee filters"
- "save for later: look into a cheaper coffee supplier"
- "follow up on the garden plan later"
- "add to my notes ..."

**Hand off a conversation.** Hold Down on an answer. The hint line shows "Hold Down: hand off to
Claude" when the phone companion is set up; without it, Hand off is hidden. Claude writes a short
summary (goal, where it stands, next step) and the phone adds the recent conversation to the doc.
The phone then posts a "Handed off: ..." notification with **Open in Claude** and **Open notes**.
Then say **"catch up on my notes"** in the Claude app, and it works through the open items.

**Notes tab** (in the phone app):
- **Google account**: tap **Connect Google account** and sign in. The app creates its two docs itself
  and can only open files it created (`drive.file`). You do not need a Google Cloud project. Once
  connected, **Reconnect** signs in again and **Disconnect** forgets the account and the docs (they stay
  in your Drive). **Make new docs** appears only when Google refuses access to the existing docs; it
  creates fresh ones.
- **Notes doc**: **Open notes** opens it and **Open in Claude** opens the Claude app. **Retry now**
  appears when notes are waiting to sync (for example, you were offline; they are kept encrypted on
  the phone). **Tidy now** removes done notes from the doc; a switch does this automatically.
- **Context from Claude**: the context text the watch uses, and when it was last loaded.
  **Refresh now** reloads it, **Open context doc** opens the doc, and the switch
  **Use context from Claude** turns it off.
- **Show a notification after a handoff**: on by default.

## Location and usage (companion 2.2.0)

**Location.** Allow Location on the Setup tab and choose **Allow all the time** (questions from the watch arrive
while the phone app is in the background). The phone works out the city on the phone itself and sends only
the place name (for example "Cocoa Beach, Florida, US") to Claude, never coordinates. Without the permission,
or with **Use the phone's location** off in Settings, the City setting is used as before.

**Usage.** Settings → Usage shows this month's estimate (and last month's): questions asked, tokens and web
searches, priced at Anthropic's list prices. It is an estimate; your bill at console.anthropic.com is the real
number. Set **Monthly limit** to stop asking Claude once the estimate reaches it; the watch then says so.

## Context from Claude

Your main Claude rewrites a short summary, **Wrist Assistant Context**, in your Drive
twice a day, based on what you're working on. The companion reads it and gives it to the watch as
background, so answers and notes can refer to the right project. It is used only when relevant.

## Setup

1. Install Wrist Assistant from the Pebble app store (or sideload the `.pbw` from GitHub Releases).
2. Get an API key at console.anthropic.com (API usage is billed to that account).
3. In the Pebble app, open Wrist Assistant's settings, paste the key and press Save.
   The key is stored only on your phone and sent only to api.anthropic.com.

Settings: model (Haiku 5.5 is the fast, cheap default; Sonnet 5.5 and Opus 5.5 for harder questions),
answer length, web search, units, your city, an optional "about me" note, and quick prompts.

## Updating to companion 2.1.0

Companion 2.1.0 is signed with a release key, so it can't install over 2.0.0 (which was
debug-signed). Uninstall the old companion app first, then install 2.1.0, then re-enter your API
key and grant the permissions again (the Setup checklist shows which are missing). Builds before
the rename were called "Wrist Claude"; it is the same app.

## Building your own fork: Google Cloud setup (maintainers and forks only)

**End users do not need this.** The official companion APK already has Google sign-in set up; users
just tap Connect in the Notes tab. Do this only if you build and sign the companion yourself, because
Google matches sign-in requests to your own package name and signing key.

The app asks only for the non-sensitive `drive.file` scope (files the app creates itself).

1. Go to console.cloud.google.com and create a project (any name).
2. APIs & Services → Library → enable **Google Drive API** and **Google Docs API**.
3. OAuth consent screen → External. Add only the scope
   `https://www.googleapis.com/auth/drive.file`, then **Publish app** to Production. A
   non-sensitive scope needs no Google verification, so anyone can sign in (no test-user list).
4. Credentials → Create OAuth client ID → **Android**. Package name = your `applicationId`
   (`com.mrarron.wristclaude` unless you changed it), and the SHA-1 of your release key
   (`keytool -list -v -keystore <your>.jks`). No client ID goes into the code.

The SHA-1 must always match the key that signs the APK you ship. See `../docs/RELEASING.md`.

## Building

Pebble SDK 4.33.1 (`pebble build`). Phone-side tests: `for f in test/pkjs/*.test.js; do node $f; done`.
Size report: `python3 tools/watch_size.py`. Design and protocol: `docs/protocol.md`.
