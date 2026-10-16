# Store assets for Wrist Assistant

Assets and listing text for the Pebble appstore (Core Devices / Rebble). Everything here is original
artwork (a watch with a speech bubble); there is no Anthropic logo or branding. Screenshots come from
the emulator using a throwaway local mock server and made-up sample questions, so no personal data
or real API output appears in them.

## Requirements found

Current Core Devices developer docs (they list the exact numbers):

- https://developer.repebble.com/guides/appstore-publishing/preparing-a-submission
- https://developer.repebble.com/guides/appstore-publishing/publishing-an-app

| Item | Requirement |
|---|---|
| Images | PNG, JPEG or GIF, at most 4.4 MB each. GIFs keep their animation. |
| Screenshots | Exactly the platform display size, not framed in a watch outline. Up to 5 per platform. |
| Screenshot sizes | aplite, basalt, diorite, flint 144x168; chalk 180x180; emery 200x228; gabbro 260x260 |
| Banner | 720x320, optional, one per platform, shown at the top of the listing |
| Small icon | 80x80 (watchapps only, optional; generated from the name if missing) |
| Large icon | 144x144 (watchapps only, optional) |
| Description | Required, plain text (the older Rebble guide caps it at 1600 characters; we stay under) |
| Category | One of Daily, Tools & Utilities, Notifications, Remotes, Health & Fitness, Games |
| .pbw | At most 4.4 MB; version numbers and dots only, UUID unique and kept for all releases |
| Companion app | Optional; needs name, Play Store URL and 144x144 icon (we skip it, see `listing.md`) |
| Review | None. "Apps go live as soon as they are submitted." Visibility: Listed, Unlisted or Hidden. |
| Local filenames for `pebble publish` | must start with the platform name and an underscore, e.g. `emery_main.png` |

The older Rebble pages (https://developer.rebble.io/guides/appstore-publishing/preparing-a-submission/ and
https://help.rebble.io/appstore-submission/) say: up to 5 screenshots per platform in PNG, GIF or animated GIF,
at least 1 required, a banner required for watchapps, and a large and small icon. They give no pixel sizes or
GIF byte limits; the Core Devices page above is the source for sizes. Note the small icon is 80x80 there,
not 48x48.

Not found in either source: a GIF frame-count or duration limit (only the 4.4 MB limit per image).

## What is here

| Path | Use |
|---|---|
| `icons/icon_small_80.png` | Small icon, 80x80 |
| `icons/icon_large_144.png` | Large icon, 144x144 |
| `banner/banner_720x320.png` | Marketing banner (framed screenshots allowed in banners only) |
| `screenshots/<platform>_<n>_<name>.png` | Native-size stills for emery, gabbro, basalt, chalk, diorite, flint |
| `screenshots/emery_ask.gif`, `emery_reminder.gif` | Animated previews, 200x228 |
| `listing.md` | Name, category, description, links, release notes |

Stills per platform: `1_menu`, `2_thinking`, `3_answer`, `4_reminders`, `5_alert`.
Basalt, diorite and flint share 144x168; diorite and flint are black and white. Gabbro and chalk are round
(the gabbro PNGs keep transparent corners).

The limit is 5 per platform, so for emery pick 5 of the 7 files. Suggested: `emery_ask.gif`,
`emery_reminder.gif`, `emery_1_menu.png`, `emery_3_answer.png`, `emery_5_alert.png`.
The other platforms take all five stills.

## How they were made (to redo them)

1. In a throwaway build only: set `API_URL` in `src/pkjs/claude.js` to `http://127.0.0.1:8765/v1/messages`,
   seed a made-up key and three quick prompts ("How long to boil an egg?", "Remind me in 1 minute to check the
   oven", a fun fact) in `src/pkjs/settings.js`, and run a small mock server that returns canned replies
   (a `set_reminder` tool call for the reminder prompt). Revert those edits afterwards.
2. `pebble install --emulator <platform>`, drive it with `pebble emu-button click ...`, capture with
   `pebble screenshot --no-open --emulator <platform> file.png`. The reminder alert fires in real time about a
   minute after it is set.
3. GIFs: frames captured one by one from emery (the voice question used `pebble transcribe`), then
   assembled with Pillow using a shared 64-colour palette.
