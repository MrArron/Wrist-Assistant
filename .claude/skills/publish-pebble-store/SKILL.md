---
name: publish-pebble-store
description: Publish the Wrist Assistant watch app to the Pebble app store (Core Devices / repebble) from the maintainer's own machine with `pebble publish`. Use when the maintainer says "publish to the Pebble store", "release to repebble", "upload the new version to the appstore" or similar. Uploads hidden by default.
---

# Publish Wrist Assistant to the Pebble app store

Runs on the maintainer's own computer (Linux, macOS or WSL), where `pebble login` has already
been done in a browser. It uploads the watch app (`wrist-claude/`) with `pebble publish`. The
Android companion is not on the store; it ships as a GitHub Release (see `docs/RELEASING.md`).

## Hard rules

- **Never handle the login.** Don't run `pebble login` yourself, and never read, print, copy or
  commit `~/.pebble-sdk/firebase_oauth_storage.json` or any token. If not logged in, ask the
  maintainer to run `pebble login` in their own terminal and stop.
- **Hidden by default.** Never pass `--is-published` unless the maintainer explicitly says to make
  this release visible immediately in this conversation.
- **Never pass `--replace-screenshots`** unless the maintainer explicitly asks; it is irreversible.
- **Confirm before uploading.** Show the summary in step 5 and wait for a clear "yes".
- Never publish from a dirty tree or with failing tests.

## 1. Preflight

Run from the repository root:

```
command -v pebble && pebble --version
pebble login --status          # must say logged in; otherwise stop (see Hard rules)
pebble sdk list                # 4.33.1 should be installed and active
git status --short             # must be empty
git rev-parse --abbrev-ref HEAD   # normally main
```

If the SDK is missing: `pebble sdk install 4.33.1 && pebble sdk activate 4.33.1`.

## 2. Version and release notes

- The store version comes from `wrist-claude/package.json` (`"version"`). Read it and tell the
  maintainer. If they want a new version and it hasn't been bumped, stop and suggest bumping it in
  a normal commit first (also `src/pkjs/version.js`, see `pebble-dev.json` → `version.files`).
- Release notes: use what the maintainer gives you; otherwise draft 1–3 short plain-text lines
  from the commits since the last tag (`git log --oneline $(git describe --tags --abbrev=0)..HEAD`)
  and show them for approval. For the first release use the "Release notes (first release)"
  section of `wrist-claude/store/listing.md`.

## 3. Build and test

```
cd wrist-claude
pebble clean && pebble build
python3 tools/watch_size.py --platform emery | head -3     # must be under the budget in pebble-dev.json
for f in test/pkjs/*.test.js; do node "$f"; done            # all must pass
cd ..
```

Stop on any failure and report it.

## 4. Assemble the upload arguments

All paths relative to `wrist-claude/` (the command runs there).

- Description: the text under `## Description (plain text, paste as is)` in
  `store/listing.md`, up to the next `## ` heading, trimmed. Pass it via a temp file read into a
  shell variable, not by retyping it.
- Screenshots (max 5 per platform; filenames must start with the platform name):
  - emery: `store/screenshots/emery_ask.gif store/screenshots/emery_reminder.gif
    store/screenshots/emery_1_menu.png store/screenshots/emery_3_answer.png
    store/screenshots/emery_5_alert.png`
  - basalt, chalk, diorite, flint, gabbro: `store/screenshots/<platform>_1_menu.png` …
    `<platform>_5_alert.png` (all five).
  Check `store/listing.md` → "Screenshots to upload" if it says otherwise.
- Icons: `store/icons/icon_small_80.png`, `store/icons/icon_large_144.png`.

## 5. Confirm

Show the maintainer, then wait for "yes":

- app name **Wrist Assistant**, version, release notes,
- visibility: **hidden** (or visible, only if they asked),
- number of screenshots per platform, icons,
- that `pebble publish` will rebuild and upload to `https://appstore-api.repebble.com`.

## 6. Publish

```
cd wrist-claude
DESC="$(cat /path/to/temp/description.txt)"
pebble publish --non-interactive --no-gif-all-platforms \
  --name "Wrist Assistant" \
  --description "$DESC" \
  --category tools \
  --source "https://github.com/MrArron/Wrist-Assistant" \
  --icon-small store/icons/icon_small_80.png \
  --icon-large store/icons/icon_large_144.png \
  --release-notes "<approved notes>" \
  --screenshots <files from step 4>
```

- `--name`, `--description`, `--category`, `--source` and the icons are only used the first time
  (when the app doesn't exist on the store yet); they're harmless on later releases.
- `--no-gif-all-platforms` stops it from trying to record new GIFs in the emulator; we upload the
  committed ones instead.
- Add `--is-published` only per the Hard rules.

## 7. Report

Report the command's outcome (success or the exact error), the version uploaded, and that it is
hidden until the maintainer makes it visible in the store's developer dashboard. Suggest tagging
the release on GitHub (`docs/RELEASING.md`) if it isn't tagged yet; don't push a tag without
asking.
