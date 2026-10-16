# CLAUDE.md

Wrist Assistant: a Pebble Time 2 (also Round 2, Pebble 2 Duo and older color/BW Pebbles) watch app (C + PebbleKit JS). General-purpose Claude on the wrist: voice questions, follow-ups, quick prompts, reminders, history.

This project uses the **pebble-dev** plugin. Settings live in `pebble-dev.json`;
workflows: `/pebble-build`, `/pebble-emu`, `/pebble-ship`. Platform rules and
SDK traps load automatically from the plugin's know-how skill.

Read before making changes:
- `docs/` — protocol.md (features, message keys, settings, storage layout)

## Repository layout

- `package.json` + `wscript` at the root, `src/c/` watch code, `src/pkjs/`
  phone companion (everything there is bundled; tests go in `test/pkjs/`).
- `tools/watch_size.py` — size report (run after every build).

## Working rules

- **Name.** User-facing name is **Wrist Assistant**; say "Pebble Time 2 (also Round 2, Pebble 2 Duo and older color/BW Pebbles)", not SDK
  codenames, in user-facing text.
- **Scope.** Features and phases are in `docs/`. Don't add features from a
  "Later" list or change decisions without asking.
- **Secrets.** Never commit or print passwords, tokens or anything listed in
  `privateData`. Use made-up values in tests.
- **Size.** Static size must stay under the budget in `pebble-dev.json`
  (hard SDK wall 65,535 B). Put the size script's first lines in PR descriptions.

## Bugs

Known bugs live in GitHub issues (`gh issue list --label bug`). File new ones
with `/pebble-ship bug "..."`.

## Pull requests

Small, focused PRs: what changed, how it was tested (emulator / watch / live
data), what's unverified. Version scheme: see `version` in `pebble-dev.json`.

## Notes

- The phone side calls the Anthropic Messages API directly from PebbleKit JS (ES5 only:
  no arrow functions, template literals, fetch or async). The API key lives in phone
  localStorage (`wc_settings`) and must never be logged.
- Emulator dictation tends to work once per emulator boot; restart the emulator between voice tests.
- For end-to-end tests without a key, point `API_URL` in `src/pkjs/claude.js` at a local mock
  server in a throwaway build and revert it after.
