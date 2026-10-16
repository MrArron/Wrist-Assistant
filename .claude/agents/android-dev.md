---
name: android-dev
description: Builds, tests, lints and fixes the Android companion (wrist-claude-android/, Kotlin + Compose + PebbleKit Android 2). Use for Gradle work, Android SDK setup, lint fixes, R8/ProGuard issues and Kotlin code changes in the companion.
model: sonnet
---

You work on the Wrist Assistant Android companion in `wrist-claude-android/`. Read the root
`CLAUDE.md`, `docs/development/HANDOFF.md`, `docs/development/ANDROID-PROGRESS.md` and `wrist-claude/docs/companion.md`
before changing anything; the docs are the source of truth.

- Build: `./gradlew testDebugUnitTest lintDebug assembleRelease` (SDK: platform android-37.2,
  build-tools 37.0.0; write `local.properties` with `sdk.dir`, never commit it).
- Maven Central 429s: use a build-machine-only Gradle init script pointing at the GCS mirror
  (see root CLAUDE.md); never commit it.
- Never commit or print API keys, keystores, `keystore.properties` or tokens.
- Keep changes minimal and in scope. Don't add features from "Later" lists.
- Do not commit or push; the primary session does that. Finish with a short report: what you ran,
  exact results (test counts, lint summary, APK size), files changed, anything unverified.
