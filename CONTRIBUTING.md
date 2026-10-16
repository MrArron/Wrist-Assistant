# Contributing to Wrist Assistant

Thanks for helping. Small, focused pull requests are easiest to review.

## Build and test

Watch app (Pebble SDK 4.33.1, `uv tool install pebble-tool && pebble sdk install 4.33.1`):

```
cd wrist-claude
pebble build
python3 tools/watch_size.py --platform emery      # exits 1 if over the size budget
for f in test/pkjs/*.test.js; do node $f; done    # phone-side tests
```

Android companion (Android SDK platform `android-37.2`, build-tools `37.0.0`, JDK 17):

```
cd wrist-claude-android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew testDebugUnitTest lintDebug assembleDebug
```

## Rules

- **Size budget.** The watch app must stay under `sizeBudgetBytes` in `wrist-claude/pebble-dev.json`
  (the SDK's hard wall is 65,535 B). Paste the first lines of `watch_size.py` into your PR.
- **ES5 only in `wrist-claude/src/pkjs/`.** PebbleKit JS has no arrow functions, template literals,
  `let`/`const` in all engines, `fetch` or `async`. Tests live in `wrist-claude/test/pkjs/`.
- **Confirm on the watch.** Anything that sends something to another person (a text, an email, a
  reply) must be approved on the watch first. Do not weaken or bypass this.
- **No secrets, no personal data.** Never commit or log API keys, tokens, keystores,
  `keystore.properties`, or real questions and messages. Use made-up values in tests and screenshots.
- **Keep identifiers stable.** Do not change the Pebble app UUID, message keys, the Android
  `applicationId` or stored-preference keys; users' installs depend on them.
- **Naming.** The product is "Wrist Assistant". "Claude" is mentioned only descriptively, with
  "Not affiliated with or endorsed by Anthropic." No Anthropic logos.
- **Docs.** Update `wrist-claude/docs/` when you change the protocol or companion behaviour.

## Pull requests

Describe what changed, how you tested it (emulator, real watch, unit tests), and what you could not
verify. CI must pass. See the PR template.

## Reporting bugs and ideas

Use the issue templates. Do not paste API keys or personal messages. For security problems, see
[SECURITY.md](SECURITY.md) instead.
