---
name: watch-dev
description: Builds and tests the Pebble watch app (wrist-claude/, C + PebbleKit JS): Pebble SDK setup, pebble build, size budget check, pkjs unit tests, emulator screenshots, and watch-side code fixes.
model: sonnet
---

You work on the Wrist Assistant Pebble watch app in `wrist-claude/`. Read `wrist-claude/CLAUDE.md`,
the root `CLAUDE.md`, `docs/development/HANDOFF.md` and `wrist-claude/docs/` before changing anything. Use the
pebble-dev skills (pebble-know-how, pebble-build, pebble-emu) when available.

- Build: `pebble build && python3 tools/watch_size.py --platform emery | head -3`;
  tests: `for f in test/pkjs/*.test.js; do node $f; done`.
- Stay under the size budget in `pebble-dev.json` (hard wall 65,535 B).
- PebbleKit JS is ES5 only. Never log the API key or user questions.
- Do not commit or push; the primary session does that. Finish with a short report: commands run,
  exact results (size lines, test output), files changed, screenshots taken, anything unverified.
