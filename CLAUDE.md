# Wrist Assistant (monorepo)

Two apps that work together:

- `wrist-claude/` — the Pebble watch app (C + PebbleKit JS). Has its own `CLAUDE.md`; read it
  before touching watch code. Uses the pebble-dev plugin workflows (`/pebble-build`, `/pebble-emu`).
- `wrist-claude-android/` — the Android companion (Kotlin, Compose, PebbleKit Android 2). It
  becomes the "brain" when installed: holds the API key, calls Claude, runs phone tools.
  `docs/development/ANDROID-PROGRESS.md` has the build history and design decisions.

Design docs (source of truth): `wrist-claude/docs/protocol.md` (v1 watch/phone protocol) and
`wrist-claude/docs/companion.md` (v2 companion, notes/handoffs, context doc).

**Start with `docs/development/HANDOFF.md`** — current status and what's left.

## Building

Watch:
```
uv tool install pebble-tool==5.0.40 && pebble sdk install 4.33.1
cd wrist-claude && pebble build && python3 tools/watch_size.py --platform emery | head -3
for f in test/pkjs/*.test.js; do node $f; done
```

Companion (needs the Android SDK: platform android-37.2, build-tools 37.0.0):
```
cd wrist-claude-android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew testDebugUnitTest lintDebug assembleRelease
```
- Maven Central often returns 429 in cloud sandboxes. If it does, add a build-machine-only Gradle
  init script that swaps Central for `https://maven-central.storage-download.googleapis.com/maven2/`
  (don't commit it), and re-run with `--refresh-dependencies` once (Gradle caches the misses).
- Release signing reads `wrist-claude-android/keystore.properties` + the `.jks` it names. Neither is
  in git. The maintainer keeps them; without them `assembleRelease` produces an unsigned APK. Never commit them.

## Rules

- Never commit or print API keys, tokens, passwords, keystores, or questions users asked.
- Keep the watch app under the size budget in `wrist-claude/pebble-dev.json`.
- Anything that sends something to another person must be confirmed on the watch first.
- User-facing name is "Wrist Assistant". "Claude" appears only descriptively ("works with Claude") with the
  line "Not affiliated with or endorsed by Anthropic."; no Anthropic logos.

## Workflow (primary session + subagents)

The main chat is the coordinator: it plans, talks to the maintainer, reviews results, commits and
pushes. Hands-on work goes to subagents in `.claude/agents/`, each pinned to a model that fits:

| Agent | Model | Use for |
|---|---|---|
| `android-dev` | sonnet | Gradle/SDK setup, companion tests, lint, release build, Kotlin fixes |
| `watch-dev` | sonnet | Pebble SDK, `pebble build`, size check, pkjs tests, emulator |
| `docs-writer` | haiku | README, setup steps, release notes |
| `reviewer` | opus | Read-only review of a diff before it is pushed |
| `Explore` (built-in) | — | Quick read-only lookups across the code |

- Independent jobs (watch vs. companion) run in parallel, in the background.
- Subagents never commit or push; they end with a report (commands, exact results, files changed,
  what's unverified). The coordinator checks it, runs `reviewer` on non-trivial diffs, then commits.
- Give each subagent a self-contained brief: goal, files, done criteria. It starts with no context.
