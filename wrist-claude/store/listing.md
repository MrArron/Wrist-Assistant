# Store listing: Wrist Assistant

Paste the fields below into the Developer Dashboard. The description is plain text and
must stay within 1600 characters (limit from the Rebble guide; the Core Devices guide gives none).

## Name

Wrist Assistant

## Category

Tools & Utilities (alternative: Daily)

## Short description (one line, for tweets/links)

Ask Claude by voice from your wrist. Needs your own Anthropic API key.

## Description (plain text, paste as is)

```
Ask Claude, Anthropic's AI, a question by voice and read a short answer on your wrist.

Wrist Assistant is designed to work only with Claude. It needs your own Anthropic API key (from console.anthropic.com). Usage is billed to your Anthropic account.

FEATURES
- Voice questions: press Select, speak, read the answer
- Follow-ups in the same conversation
- Quick prompts: one-press questions you set on your phone
- Reminders: say "remind me in 10 minutes to..." and the watch vibrates when it is due (snooze or dismiss)
- History: your last 5 answers, readable offline
- Web search for current info such as weather and news (optional)

SETUP
1. Install the watch app from the store.
2. Open its settings in the Pebble phone app and paste an Anthropic API key.
3. Optional: install the Android companion APK from GitHub Releases for phone actions, notes and read-aloud.

USAGE
Press Select to ask by voice. Select on an answer to follow up. Hold Select for a new question. Quick prompts are listed in the menu.

PRIVACY
Your API key stays on your phone. Your questions go only to Anthropic's API. There is no other server and no analytics.

Full setup and details: README on GitHub
https://github.com/MrArron/Wrist-Assistant#readme

Not affiliated with or endorsed by Anthropic.
```

## Links

| Field | Value |
|---|---|
| Source code URL | https://github.com/MrArron/Wrist-Assistant |
| Website URL | https://github.com/MrArron/Wrist-Assistant#readme |
| Support | https://github.com/MrArron/Wrist-Assistant/issues (the form asks for an email; use the developer account email if blank) |

## Android companion (optional)

The Android companion stays a sideloaded APK, not a Play Store app.

- Name: Wrist Assistant Companion (Android)
- Download: https://github.com/MrArron/Wrist-Assistant/releases
- Package: `com.mrarron.wristclaude` (already in `package.json` `companionApp.android`)
- The dashboard's companion field asks for a Play Store URL and a 144x144 icon; this app has no Play Store listing, so leave the companion entry empty and rely on the description text.

## Release notes (first release)

First public release. Voice questions, follow-ups, quick prompts, reminders, history and optional web search. Requires your own Anthropic API key.

## Screenshots to upload

See `README.md` in this folder for the file list and the 5-per-platform rule.
