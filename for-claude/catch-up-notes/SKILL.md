---
name: "catch-up-notes"
description: "Use when the user says \"catch up on my notes\", \"check my watch notes\", \"what did I note\" or similar: work through open items in the Wrist Assistant Notes Google Doc."
---

# Catch up on my notes

The user's Pebble watch app (Wrist Assistant) and its Android companion append notes and conversation
handoffs to a Google Doc titled "Wrist Assistant Notes". This skill works through the open ones.

## 1. Read the doc

- Search Google Drive for a Google Doc titled exactly "Wrist Assistant Notes" (or a title the user
  gives). The phone app creates it, so it may be in the Drive root or wherever the user moved it.
  If several match, use the most recently modified one whose title has no "(old)" in it, and
  mention the duplicates in one line.
- Read it with the Google Docs connector by the id the search returned.
- If the Google Docs or Drive connector is not connected, say so in one line and suggest
  connecting them; stop there.

## 2. Parse the entries

Each entry starts with a header line:

`=== STATUS | YYYY-MM-DD HH:MM | KIND | project ===`

- STATUS: OPEN, WAITING or DONE. Work only on OPEN and WAITING. Skip DONE entries completely:
  don't summarize, quote or count them.
- KIND: NOTE (something to remember or file), FOLLOWUP (something to look into or do), HANDOFF
  (a watch conversation to continue; it has Goal, Where it stands, Next step and a Conversation
  block).
- project: free text, may be empty.
- Everything above the first header (title and intro text) is not an entry; leave it alone.

## 3. Show the list first

Start with a short list of the open items, oldest first: date, kind, project, one line each. If
there are more than 8, show the 8 oldest and say how many more there are.

Then say which items you'll handle now. Default: handle all of them unless one needs a decision
only the user can make.

## 4. Handle each item

- NOTE: if it belongs to an ongoing project the user has (check memory and the current Project),
  connect it to that project and say what it changes. Otherwise just acknowledge it.
- FOLLOWUP: do the work (research, draft, plan or answer) the way you would if the user had asked
  it directly in chat.
- HANDOFF: read the Conversation block and pick up exactly from the Next step. Don't re-answer
  what was already answered on the watch, and don't paste the Conversation block back into chat.
- Anything that would send a message, buy something, delete something or change settings:
  prepare it and ask before doing it.
- If an item is unclear, ask about it rather than guessing, and leave it OPEN.

## 5. Update the doc

After handling an item, edit only its header and add one line directly after the entry's last
line (no blank line before it, and no blank lines anywhere inside an entry), with the Google
Docs connector:

- Finished: change OPEN (or WAITING) to DONE and add `Result: YYYY-MM-DD <one short line>`.
- Needs the user or something outside your control: change it to WAITING and add
  `Waiting on: <what>`. When it is later finished, change WAITING to DONE the same way.

The phone app removes DONE entries from the doc automatically, about once a day, to keep the doc
short. So the doc is not an archive: if a result is worth keeping (a decision, a number, a link,
a project change), save it to memory or the relevant Project as well, in the same turn.

Never delete entries yourself, never change the user's text, and never edit DONE entries; the
phone does the removing.

## 6. Wrap up

End with a short summary: what got done, what is WAITING and why, and any question for the user.
No recap of the steps.
