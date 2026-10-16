# Security policy

## Reporting a vulnerability

Please report security problems privately through GitHub Security Advisories:
<https://github.com/MrArron/Wrist-Assistant/security/advisories/new>

Do not open a public issue for a vulnerability. Include what you found, how to reproduce it, and which
version is affected. Do not include real API keys or other people's data. You should get a first reply
within about a week; this is a hobby project, so please be patient.

## In scope

- **API key handling.** The Anthropic API key must stay on the phone (PebbleKit JS storage, or the
  companion's encrypted store) and be sent only to `api.anthropic.com`. It must never be logged.
- **Notification access.** The companion can read and reply to notifications. Anything leaking
  notification content, or sending something without approval on the watch, is in scope.
- **Google token handling.** The companion uses the `drive.file` scope only. Token storage, scope
  creep, and access to files the app did not create are in scope.
- Bypassing the confirm-on-watch step. Replies to notifications always need approval on the watch.
  Drafting a message or email to a recipient, and adding a calendar event, need approval once the
  conversation has used web results or read notifications (the "taint" gate in `core/Confirm.kt`);
  a calendar add on its own, in a conversation that used neither, does not.
- Release signing: anything that would let someone ship an APK that updates the real app.

## Out of scope

- Problems in the Pebble phone app, the Pebble SDK, Android, or Anthropic's API itself.
- Attacks that need a rooted or already-compromised phone.
- Content quality of Claude's answers.

## Supported versions

Only the latest release gets fixes.
