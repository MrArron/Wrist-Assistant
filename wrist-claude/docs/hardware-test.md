# Wrist Assistant: hardware test checklist

Phone: an Android phone with the companion. Watch: a Pebble. Tick each box as you go and note anything odd
(time, what you said, what the watch showed). Don't paste API keys or private messages into notes.

## What was already verified (no hardware)

- Watch app builds for all platforms with no warnings and stays under the size budget; phone-side
  JS tests pass.
- Companion: unit tests pass, lint clean, release build shrunk with R8 (PebbleKit and Google
  sign-in classes kept) and signed with the release key.
- Emulator (no companion, fake Claude server): menus, empty states, ask → working
  → long scrolled answer, set_reminder → reminder listed and alert fired, history, no "hand off"
  hint without the companion, JS route answers "Hand off needs the phone companion.", logs contain
  no key or question text.

## Files

- `wrist-claude.pbw` — watch app.
- `wrist-claude-companion-<version>.apk` — phone companion (release-signed).

## Setup, in order

1. **Google Cloud (once)** — see README "Google Cloud setup (once)". The consent screen is published to
   Production with only the `drive.file` scope, so there are no test users to add.
2. **Uninstall an older companion** if one signed with a different key is on the phone (it won't update).
3. **Install the APK**: copy it to the phone, open it from Files, allow "Install unknown apps" for
   Files when asked.
4. **Install the watch app**: open `wrist-claude.pbw` on the phone and choose the Pebble app (needs
   Pebble app 1.0.7.7 or newer). Wait for it to appear on the watch.
5. **Companion → Setup tab**:
   - Paste your Anthropic API key → **Save key**.
   - **Allow Wrist Assistant to talk to the Pebble app** → approve. Status should read Connected.
   - Permissions: grant **Post notifications**, **Calendar**, **Notification access**,
     **Display over other apps**, **Battery: unrestricted**.
6. **Companion → Notes tab** → **Connect Google account** → pick the Google account to use.

## Tests

Each test: **Goal**, **Do**, **Expect**. Run them roughly in this order; later ones build on earlier.

### A. Basics (companion route)

- [ ] **A1 Companion takes over.** Open Wrist Assistant on the watch, ask "what's 17 times 23".
  Expect: answer within ~10 s. (Answer comes via the companion; if you uninstall/disable the
  companion later, the same question still works via the Pebble app.)
- [ ] **A2 Dictation.** Ask a longer question in a normal voice. Expect: the question shown at the
  top matches what you said; no "Didn't catch that" unless it really didn't.
- [ ] **A3 Long answer + scroll.** "Explain how a heat pump works." Expect: Up/Down scroll a screen
  at a time, the end shows "Select: follow up / Hold Select: new question", no garbled characters.
- [ ] **A4 Follow-up.** Press Select, ask "and in winter?". Expect: the answer uses the previous
  context.
- [ ] **A5 Web search.** "What's the weather in <your city> today?" Expect: current info, not a
  "I can't browse" answer.
- [ ] **A6 Quick prompts.** Run one quick prompt from the menu. Expect: an answer without dictation.
- [ ] **A7 Phone Test tab.** Companion → Test → type a question → **Ask**. Expect: the same
  watch-formatted answer appears on the phone.

### B. Watch reminders

- [ ] **B1 Timing.** Note the watch time, then "remind me in 2 minutes to stretch". Expect: the
  Reminders list shows a time about 2 minutes ahead and the alert fires on time with vibration.
  *Emulator fired ~10 min late, likely emulator clock drift; this confirms real hardware is right.*
- [ ] **B2 Snooze.** On the alert press Down. Expect: it comes back 10 minutes later.
- [ ] **B3 App closed.** Set a 3-minute reminder, leave the app to the watchface. Expect: it still fires.

### C. Phone actions

- [ ] **C1 Alarm.** "Set an alarm for 6:30 tomorrow called gym." Expect: alarm appears in the Clock
  app (if "Display over other apps" is off you get a "Tap to set" notification instead).
- [ ] **C2 Timer.** "Set a 5 minute timer." Expect: Clock timer starts.
- [ ] **C3 Calendar read.** "What's on my calendar tomorrow?" Expect: matches your calendar.
- [ ] **C4 Calendar add.** "Add lunch with Sam Friday at noon." Expect: event on your default
  calendar at the right time.
- [ ] **C5 Draft.** "Draft a text to <a phone number> saying I'm running late." Expect: "Draft ready"
  notification with **Open in Messages** / **Copy**; Open fills Messages but does not send.
- [ ] **C6 Notifications read.** Send yourself a message from another app, then "what are my recent
  notifications?". Expect: it's listed.
- [ ] **C7 Reply needs approval.** "Reply to that saying thanks." Expect: the watch shows the exact
  reply text; **Back** declines (nothing sent); repeat and **Select** sends it. Also leave one
  unanswered for 60 s → nothing sent.
- [ ] **C8 Read aloud.** On an answer hold Up. Expect: a short vibration and the phone speaks it; hold Up
  again (or leave the answer screen) stops it.

### D. Notes, handoffs, context

- [ ] **D1 Hand off hint.** On any answer, check the hint line. Expect: the hint includes "Hold Up: read aloud" and
  "Hold Down: hand off to Claude".
- [ ] **D2 Save a note.** "Note: buy more coffee filters." Expect: short "Saved…" reply; a new
  `=== OPEN | <date time> | NOTE | … ===` entry at the end of the "Wrist Assistant Notes" doc.
- [ ] **D3 Follow-up note.** "Follow up on the garden plan later." Expect: a FOLLOWUP entry.
- [ ] **D4 "Remind me to look into…".** "Remind me to look into a cheaper coffee supplier." Note which happens:
  a watch reminder or a note. (The spec and prompt should agree; if they don't, fix whichever is wrong.)
- [ ] **D5 Hand off.** Have a 2–3 question conversation, then hold Down on the answer. Expect: a
  one-line "Handed off…" on the watch; a HANDOFF entry with Goal / Where it stands / Next step and
  the conversation; a phone notification "Handed off: <title>" with **Open in Claude** and
  **Open notes**. The handoff line is not added to watch History; a follow-up still continues
  the real conversation.
- [ ] **D6 Open in Claude.** Tap **Open in Claude**. Expect: the Claude app opens with "catch up on
  my notes" ready to send; sending it works through the open items.
- [ ] **D7 Offline queue.** Turn on airplane mode (keep Bluetooth on), save a note. Expect: Notes tab
  shows it waiting; turn data back on → **Retry now** (or wait) → it appears in the doc once.
- [ ] **D8 Context.** Notes tab → **Refresh now**. Expect: context text shown with a recent time.
  Then ask about one of your current projects by name; a saved note should use that project name.
- [ ] **D9 Voice hand off.** Say "hand this off". Expect: same as D5.

### G. New in 2.2.0

- [ ] **G1 Quick Launch.** Set Wrist Assistant as a Quick Launch app, hold that button on the watch face.
  Expect: the microphone opens straight away; Back while listening shows the menu.
- [ ] **G2 Normal launch.** Open it from the app list. Expect: the menu, as before (no microphone).
- [ ] **G3 Send to phone.** Ask for a recipe, then say "send that to my phone". Expect: a "Sent to your phone"
  reply and a phone notification with the full text; **Copy** copies it, **Share** opens the share sheet.
- [ ] **G4 Location.** Setup → Location → Allow, then Allow all the time. Ask "what's the weather near me" with
  the phone locked. Expect: the answer names where you actually are, not the City setting.
- [ ] **G5 Location off.** Settings → turn off "Use the phone's location", Save, ask again. Expect: uses City.
- [ ] **G6 Usage.** Settings → Usage after a few questions. Expect: question count and a small cost estimate.
- [ ] **G7 Limit.** Set Monthly limit to 0.01, Save, ask twice. Expect: "Monthly spending limit reached..." on
  the watch. Clear the limit after.

### E. Robustness

- [ ] **E1 Phone locked / screen off** for a few minutes, then ask from the watch. Expect: works.
- [ ] **E2 Bluetooth drop.** Walk away until disconnected, ask. Expect: a clear error, no freeze.
- [ ] **E3 Bad key.** Setup → Remove key, ask. Expect: "Add your API key in the Wrist Assistant phone app." Re-add after.
- [ ] **E4 Companion force-stopped.** Force-stop the companion, open the watch app, ask. Expect: the
  Pebble-app route answers; hold-Down hand off is hidden.

### F. Notes tidy

Before starting, make a copy of the
notes doc in Drive (File > Make a copy) so nothing can be lost while testing.

- [ ] **F1 Setting.** Notes tab. Expect: "Remove done notes from the doc" is on, plus a **Tidy now**
  button.
- [ ] **F2 Basic tidy.** Save two notes from the watch. In the doc, change the first one's header
  from OPEN to DONE and add `Result: 2026-10-16 test` directly under its last line (no blank line).
  Tap **Tidy now**. Expect: that entry disappears; the other note, the title and the intro stay;
  only one blank line between entries; the tab shows "Last tidy: <time>, removed 1 done notes".
- [ ] **F3 OPEN/WAITING kept.** Mark one note WAITING, tap **Tidy now**. Expect: nothing removed.
- [ ] **F4 Unclear entry skipped.** Mark a note DONE and add a blank line plus some text under it.
  **Tidy now**. Expect: that entry is left in place (removed 0).
- [ ] **F5 Edit during tidy.** Optional: type in the doc while tapping **Tidy now**. Expect: either
  it tidies or it says the doc changed; never a partial delete.
- [ ] **F6 Automatic.** Leave one DONE entry, close and reopen the watch app the next day. Expect:
  removed without tapping anything.
- [ ] **F7 Main Claude round trip.** Save a note, say "catch up on my notes" in Claude (with the
  updated skill), let it mark it DONE, then **Tidy now**. Expect: removed cleanly.
- [ ] **F8 Saving still works offline.** Airplane mode (Bluetooth on), save a note. Expect: the
  watch says it's queued; back online + **Retry now** adds it exactly once.

## Reporting back

For each failure: test id, what you did, what you saw, rough time. Screenshots from the phone help;
watch screenshots can be taken from the Pebble app if available.
