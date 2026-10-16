# Development handoff - Wrist Assistant 2.1.0 (notes, handoffs, context)

Status as of 2026-10-15: a clean point: everything compiles and all tests pass. Not yet built into
release files or tried on a real phone/watch.

## What 2.1.0 adds

Spec: `wrist-claude/docs/companion.md` → "Notes and handoffs" and "Context from the main Claude".

- **Notes / handoffs → Google Doc.** Two new Claude tools on the phone: `save_note` and
  `create_handoff`. Entries are appended to the "Wrist Assistant Notes" Google Doc in a fixed format
  (`=== OPEN | date | KIND | project ===`). The user's main Claude works through them with a
  "catch up on my notes" skill. The skill and the context task prompt are in [`for-claude/`](../../for-claude/README.md);
  the user uploads the skill to their own claude.ai account (it is also attached to each GitHub Release as a ZIP).
- **Hand off button on the watch.** Hold Down on an answer (companion only) → `ASK_KIND=3`.
- **Context doc.** A scheduled task in the user's Claude account rewrites "Wrist Assistant Context"
  twice a day. The companion reads it (cached, refreshed when the watch app opens if > 3 h old) and
  adds it to the system prompt as background.
- **Offline queue.** Notes that can't be written are kept encrypted (Keystore key) and retried.
- **Notes tab** in the phone app: connect Google (the app creates its own docs), queue status, context preview.

Docs: the app creates (or adopts) "Wrist Assistant Notes" and "Wrist Assistant Context" itself under the
drive.file scope; no ids are hard-coded. **Upgrading from 2.1.0 dev builds:**
1. Open the Notes tab, tap Reconnect and tick the new Google box; the app makes the two new docs
   (Drive root). The old ids are ignored.
2. Move OPEN/WAITING entries from the old notes doc into the new one.
3. Rename the old docs "Wrist Claude Notes (old)" / "Wrist Claude Context (old)".
4. Re-point the "catch up on my notes" skill (finds the doc by exact title, ignoring "(old)") and
   any context refresh scheduled task at the new docs ("Open context doc" in the Notes tab opens it).

## Files changed

Watch (`wrist-claude/`): `src/c/common.h` (ASK_HANDOFF), `src/c/answer_win.c` (hold Down, hint,
no history entry for handoffs, removed an unused variable), `src/pkjs/index.js` (kind 3 → error on
the JS route), version 2.1.0, `docs/companion.md`.

Companion (`wrist-claude-android/app/src/main/java/com/mrarron/wristclaude/`):
- new `core/Notes.kt` (format, Docs JSON, context parsing), `phone/GoogleAuth.kt`,
  `phone/NotesRepo.kt`, `ui/NotesScreen.kt`
- changed `core/Prompt.kt` (tools + prompt lines + background), `core/ClaudeClient.kt`
  (`ToolExecutor.background()`, `ToolContext.history`), `core/Settings.kt` (notesDoc, contextDoc,
  useContext, handoffNotification), `watch/AskEngine.kt` (ASK_HANDOFF, `onWatchOpen`),
  `phone/PhoneTools.kt`, `phone/Notifications.kt` (handoff notification, Open in Claude),
  `data/SecureKeyStore.kt` (encrypt/decrypt helpers), `App.kt`, `ui/MainActivity.kt` (Notes tab),
  `ui/SettingsScreen.kt` (keeps notes fields on save), `ui/SetupScreen.kt` (Google row),
  `AndroidManifest.xml` (query for com.anthropic.claude)
- build: play-services-auth 21.4.0, R8 minify + resource shrinking for release,
  `proguard-rules.pro`, release signing from `keystore.properties`, version 2.1.0 (210)
- tests: new `core/NotesTest.kt`, `watch/HandoffTest.kt` — 53 unit tests pass

## Still to do

Done since the 2.1.0 dev build: lint is clean, `assembleRelease` builds with R8 and release signing,
the watch app builds within the size budget, and CI plus a tag-triggered release workflow exist
(see [`docs/RELEASING.md`](../RELEASING.md)). Remaining:

1. Try the signed release APK on a real phone (R8 vs. PebbleKit and Google sign-in) and the watch app
   on a real watch. The Hand off hint only shows when the companion has said hello, so the emulator
   can't fully test it.
2. Set the repo secrets and the `ANDROID_CERT_SHA256` variable, then tag `v2.1.0` to run the release
   workflow for the first time. Confirm CI on a GitHub-hosted runner passes (new, not yet run).
3. Upload the `.pbw` to the Pebble app store (`publish-pebble-store` skill).
4. Share the `for-claude/` setup with anyone who wants the notes skill and context task.

## Google Cloud setup

See `wrist-claude/README.md` ("Building your own fork") and `docs/RELEASING.md`.

## Install notes

The 2.0.0 APK was debug-signed, so a release-signed 2.1.0 can't
install over it: uninstall the old companion first, then re-enter the API key and permissions.
Keep the release keystore safe — future updates must be signed with it, and the Google client is tied
to its SHA-1.
