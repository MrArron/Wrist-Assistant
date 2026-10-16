# Releasing

Releases are built by `.github/workflows/release.yml` when you push a tag `v*`. It builds the watch app
(`.pbw`) and a release-signed companion APK and attaches both, plus the notes skill ZIP and `SHA256SUMS.txt`, to a GitHub Release.

## One-time setup: signing secrets

The companion APK must always be signed with the same key. Android only installs updates signed with the
same key, and the Google sign-in client is tied to that key's SHA-1.

1. Base64-encode your keystore (one line, no wrapping):
   - Linux: `base64 -w0 release.jks > release.jks.b64`
   - macOS: `base64 -i release.jks | tr -d '\n' > release.jks.b64`
2. In the GitHub repo: Settings, Secrets and variables, Actions, New repository secret. Add:

   | Secret | Value |
   |---|---|
   | `ANDROID_KEYSTORE_BASE64` | contents of `release.jks.b64` |
   | `ANDROID_KEYSTORE_PASSWORD` | the keystore (store) password |
   | `ANDROID_KEY_ALIAS` | the key alias |
   | `ANDROID_KEY_PASSWORD` | the key password |

   Use ASCII passwords with no leading spaces and no backslashes: the workflow writes them to a Java
   properties file, which trims leading whitespace, reads the file as ISO-8859-1 and treats a backslash
   as an escape.

   With the GitHub CLI: `gh secret set ANDROID_KEYSTORE_BASE64 < release.jks.b64`.
3. Delete `release.jks.b64`. Keep the original `.jks` and its passwords backed up somewhere safe. If you
   lose them you cannot ship updates that install over existing installs.

The workflow writes `keystore.properties` and the `.jks` into the runner's workspace at build time, never
prints them, and deletes them at the end. 

## Expected certificate: `ANDROID_CERT_SHA256`

The workflow fails if the signed APK's certificate is not the one you expect (for example, if the wrong
keystore was stored in the secret). It compares the APK's SHA-256 certificate digest with the repository
**variable** `ANDROID_CERT_SHA256` (case and colons are ignored). If the variable is unset, the release
fails with a message saying so.

1. Get your value, either:
   - `keytool -list -v -keystore release.jks` and copy the `SHA256:` line, or
   - `apksigner verify --print-certs your-known-good.apk` and copy "certificate SHA-256 digest".
2. In the GitHub repo: Settings, Secrets and variables, Actions, the **Variables** tab, New repository
   variable, name `ANDROID_CERT_SHA256`. With the GitHub CLI: `gh variable set ANDROID_CERT_SHA256 --body <value>`.

A certificate fingerprint is public information, so a variable (not a secret) is fine.

## Keep the same signing key (and SHA-1)

The registered Google OAuth Android client (Google Cloud Console, Credentials) is matched by package name
plus the SHA-1 of the signing key. If the release key changes, Google sign-in for notes stops working until
a new client is registered, and users must uninstall and reinstall. The release workflow prints the
certificate digests (`apksigner verify --print-certs`); compare the SHA-1 with the registered client.
To read it locally: `keytool -list -v -keystore release.jks`.

## Cutting a release

1. Bump the version: `wrist-claude/package.json` (`version`), `wrist-claude/src/pkjs/version.js`
   (`APP_VERSION`), and in `wrist-claude-android/app/build.gradle.kts` (`versionName`, and raise `versionCode`).
2. Run the checks locally (see [CONTRIBUTING.md](../CONTRIBUTING.md)) and merge to `main`.
3. Tag and push:
   ```
   git tag v2.1.0
   git push origin v2.1.0
   ```
   The workflow fails early if the tag (without the `v`) does not match `version` in
   `wrist-claude/package.json`, `APP_VERSION` in `wrist-claude/src/pkjs/version.js` or `versionName` in
   `wrist-claude-android/app/build.gradle.kts`, if `versionCode` is not an integer, or if a secret or
   the `ANDROID_CERT_SHA256` variable is missing.
4. Open the GitHub Release, check the files, and edit the notes if you want. Besides the `.pbw` and
   `.apk` it holds `wrist-assistant-catch-up-notes-skill-<version>.zip`, the optional skill for the
   user's own Claude (zipped from [`for-claude/catch-up-notes`](../for-claude/catch-up-notes/); see
   [`for-claude/README.md`](../for-claude/README.md)).
5. Upload the `.pbw` to the Pebble app store dashboard (see `wrist-claude/store/listing.md`). The Pebble
   UUID must stay the same for every release.

## If the workflow fails

- Pebble SDK step: check that `pebble sdk install 4.33.1` still works on `ubuntu-latest`.
- Android SDK step: the build needs platform `android-37.2` and build-tools `37.0.0`.
- Signing step: re-check that the base64 secret is a single line and the alias and passwords are right.

## Publishing the watch app to the Pebble app store

The watch app goes to the Pebble app store (Core Devices / repebble) with `pebble publish`, run
from the maintainer's own machine:

1. Once: install pebble-tool and SDK 4.33.1, then run `pebble login` and sign in in the browser.
   The login is stored locally in `~/.pebble-sdk/`; never commit or share that file.
2. Each release: in a Claude Code session in this repo, say "publish to the Pebble store". The
   project skill `.claude/skills/publish-pebble-store` checks the login, builds, runs the tests and
   size check, shows a summary, and on your "yes" uploads the version with the store assets from
   `wrist-claude/store/`. Uploads are **hidden** until you make them visible in the store's
   developer dashboard (unless you explicitly ask for an immediate public release).

Without Claude Code, follow the same steps by hand from that skill file.
