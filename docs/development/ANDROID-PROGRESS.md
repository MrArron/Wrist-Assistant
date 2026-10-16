# Wrist Assistant companion for Android - build history

## Done
- Gradle wrapper (9.8.1), settings/build.gradle.kts, version catalog, local.properties
- core/: TimeUtil, TextUtil, Prompt, ClaudeClient, Settings (reviewed by resuming agent: good, kept)
- SDK: platform android-37.2, build-tools 37.0.0 only

- app/build.gradle.kts written; core compiles with assembleDebug (compileSdk release(37){minorApiLevel=2}, buildToolsVersion 37.0.0
  pinned so AGP doesn't download 36). Maven Central sometimes returns 429: just retry after ~45 s with --max-workers=2.

- Android layer written and assembleDebug passes (milestone 2).
- Unit tests: 31 pass (GoldenTest uses vectors generated from the real text.js/claude.js via node, in app/src/test/resources; ProtocolTest; ClaudeClientTest; TimeAndToolsTest). Next: lint, copy APK, report.

- Lint clean ("No issues found"), 31 unit tests pass, debug APK built.
  JOB COMPLETE (2026-10-14). Remaining = on-device testing only.

## Decisions
- Debug signing (debug keystore in ~/.android of the build machine) was used for 2.0.0. Superseded: release builds are now signed from `keystore.properties` (see docs/RELEASING.md). Moving from a debug-signed install to a release-signed one needs an uninstall first.
- Pebble app picker: enableAutoSelect=false in App.onCreate; user grants via client-ui PebbleAppPermissionDialog on Setup.
- Listener returns Ack immediately; ask work runs in App.scope (SupervisorJob, Dispatchers.Default).
- AskService: FGS type shortService (no extra permission, ~3 min limit) while an ask runs; start failures are ignored
  (the Pebble app's binding keeps the process alive while the watch app is open).
- Alarms/timers: start activity directly if Settings.canDrawOverlays (BAL exemption) or in Test screen; else tap-to-set notification.
- Test screen: confirmations shown as an in-app dialog instead of on the watch.
- WatchConfirmer sends STATUS "Check your watch to approve." before CONFIRM and "Working..." after; 60 s timeout starts after delivery;
  pauseClock excludes confirmation time from the 60 s ask budget.

## Review fixes (versionCode 201) - DONE, 41 tests pass, lint clean, APK re-copied
1. Replies: Recipients.checkReply/ReplyFlow (core/Confirm.kt). Reply must be plain Latin-1, no markdown, <=150 chars,
   header (title, app, package) + text <=300 B, else tool error; exactly the approved text is sent. WatchConfirmer and
   the Test screen never alter confirm text (Recipients.isDisplayable or NO_ANSWER).
2. Taint: ClaudeClient tracks taint (web search blocks/results, get_recent_notifications); ToolContext.tainted;
   TaintGate: add_calendar_event and create_draft-with-valid-to need watch approval once tainted. Follow-ups inherit
   taint (AskResult.Answer.tainted -> initiallyTainted).
3. fail(): after a tool note exists, TOO_SLOW/network/API errors return the note as the answer.
4. WatchLink: withTimeoutOrNull(10 s) per send attempt.
5. AskService updateAndGet; Test confirm cleared in finally; RemoteInput.setResultsSource FREE_FORM; key-save error shown;
   Setup note about losing answers if the watch app closes mid-question.
