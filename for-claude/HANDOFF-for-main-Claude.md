# Wrist Assistant: handoff for the user's main Claude

The user gives this to their own Claude (claude.ai or the Claude app) to explain how their watch
notes work and how to handle them. It's the reference; the "catch-up-notes" skill does the day-to-day work.

## What this is

The user runs **Wrist Assistant**, a Claude app on their Pebble watch with an Android companion on
their phone. From the watch they can save notes, follow-ups and whole conversations ("hand off")
for later. They land in a Google Doc. You (the user's main Claude) work through them when the user
says **"catch up on my notes"**. A scheduled task in the user's account can also write a short
summary of what they are working on into a second doc, which the watch reads as background.

## The two docs (created by the phone app in the user's Drive; they may move them to another folder)

| Doc (find by exact title) | Written by | Read by |
|---|---|---|
| **Wrist Assistant Notes** | phone appends entries; you change status and add one line; phone removes DONE entries | you, the context task |
| **Wrist Assistant Context** | the context refresh scheduled task (for example twice a day) | the phone/watch |

## Notes doc: entry format

The title and intro text at the top are not entries. Each entry is plain text:

```
=== OPEN | 2026-10-15 17:49 | NOTE | kitchen ===
Buy more coffee filters.

=== OPEN | 2026-10-15 18:02 | HANDOFF | Trip planning ===
Title: ...
Goal: ...
Where it stands: ...
Next step: ...
Conversation:
Q: ...
A: ...
```

- **Status**: `OPEN` (new), `WAITING` (blocked on the user or something else), `DONE` (finished).
- **Kind**: `NOTE` (remember or file it), `FOLLOWUP` (look into or do it), `HANDOFF` (continue
  this watch conversation from "Next step").
- **Project**: free text, may be empty. It matches a project name from the context doc when the
  watch could tell.

## Lifecycle (who does what)

1. **Watch/phone** appends a new `OPEN` entry at the bottom. It never changes your edits.
2. **You** ("catch up on my notes") handle OPEN and WAITING items, then edit only the header
   status and add one line directly after the entry's last line (no blank line before it;
   the phone skips entries with blank lines inside, so they'd never be cleaned up):
   - finished: `DONE` + `Result: YYYY-MM-DD <one short line>`
   - blocked: `WAITING` + `Waiting on: <what>`
3. **Phone** removes `DONE` entries from the doc, about once a day (setting "Remove done notes from
   the doc", on by default). `OPEN` and `WAITING` are never removed automatically.

So the doc only ever holds open work. **It is not an archive:** if a result is worth keeping,
save it to memory or the relevant Project in the same turn you mark it DONE.

## Rules for you

- Find the notes doc by its exact title (ignore copies with "(old)" in the title); skip DONE entries entirely (don't summarize or count them).
- Never delete entries or change the user's text. Only the phone removes things, and only DONE entries.
- For HANDOFF items, continue from "Next step"; don't re-answer what the watch already answered
  and don't paste the conversation back to the user.
- Anything that sends a message, buys something, deletes something or changes settings: prepare
  it and ask the user first.
- Unclear item: ask, and leave it OPEN.

## Context doc: keeping it small

The scheduled task replaces the doc's whole text each run (it never appends), plain ASCII, at most
1,200 characters, between `=== WRIST CONTEXT | <time> ===` and `=== END ===`. The phone reads only
the text between those markers and cuts it to 1,500 characters, so it can't grow on the watch side.

Two habits keep it useful and short:
- List only projects active in the last ~14 days (at most 6); drop finished ones.
- Count only OPEN and WAITING notes; ignore DONE entries' text.

## Token budget at a glance

| Thing | Size limit | Why it stays small |
|---|---|---|
| Context doc | ≤ 1,200 chars (watch cuts at 1,500) | rewritten on each refresh, never appended |
| One handoff entry | last 6 messages, each answer ≤ 600 chars | the phone trims it when writing |
| Notes doc | only OPEN + WAITING entries | phone removes DONE daily |

## What the user sets up in Claude (once)

1. **Install the "catch-up-notes" skill** from `catch-up-notes/SKILL.md`, which comes
   with this doc. It finds the doc by its title, skips DONE entries, uses a dated `Result:` line, and
   saves keepers to memory because DONE entries get removed.
2. **Optional:** add the two "keeping it small" habits above to the context refresh
   scheduled task's prompt.
