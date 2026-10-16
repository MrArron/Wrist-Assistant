# Context refresh scheduled task (optional)

Wrist Assistant's companion reads a short "Wrist Assistant Context" Google Doc and gives it to the
watch as background, so answers can refer to what you're working on. Your own Claude keeps that doc
up to date with a scheduled task.

## Set it up

1. Install the Wrist Assistant companion and tap **Connect Google account** on its Notes tab. The app
   creates the "Wrist Assistant Context" doc. Your Claude must not create it: the app can only read
   docs it created itself.
2. In Claude (claude.ai or the Claude app), make sure the **Google Drive** and **Google Docs**
   connectors are connected.
3. Create a scheduled task that runs twice a day (for example 06:45 and 16:45 in your time zone)
   with the prompt below. Replace `<your time zone>` with an IANA name such as `America/Chicago`.

## Prompt

```
Refresh the "Wrist Assistant Context" Google Doc. The user's Pebble watch app (Wrist Assistant) and its Android companion read this doc and add it to the watch assistant's system prompt, so the watch has a small bit of context about what the user is working on.

0. Find the docs by exact title with the Google Drive connector:
   - "Wrist Assistant Context" (the doc you will rewrite) and "Wrist Assistant Notes" (read only).
   - If several match a title, use the most recently modified one whose title does not contain "(old)".
   - If "Wrist Assistant Context" doesn't exist yet, stop and say so in one line. Do NOT create it yourself: the phone app can only read docs it created, so it must make this doc when the user connects Google in the app.

1. Gather what the user has been working on lately:
   - Read the user's memory, if available.
   - If past-chat tools are available, look at chats from the last 7 days for active projects and decisions.
   - Read the "Wrist Assistant Notes" doc. Use only OPEN and WAITING entries; ignore DONE entries entirely.

2. Write a very brief, high-level summary, plain text, ASCII only, at most 1,200 characters, in exactly this shape:
   === WRIST CONTEXT | YYYY-MM-DD HH:MM (<your time zone>) ===
   Active projects:
   - <project>: <one line: what it is and where it stands>
   (at most 6 projects, most recently active first; include only projects with activity in the last ~14 days and drop finished ones)
   Recent: <one or two lines on recent decisions or changes>
   Open notes: <count> open, <count> waiting
   === END ===
   Leave out health, money, family/relationship details, passwords, addresses and anything sensitive; this text is sent to the API from the watch. Facts only, no advice.

3. Replace the doc's whole body with the new summary: read the doc with the Google Docs connector to get the body's end index, then in one update call delete the range from index 1 to (end index - 1) and insert the new text at index 1. If nothing changed meaningfully, still update the timestamp.

4. Don't create other files, don't edit the notes doc, don't send messages. If the Google connectors aren't available, stop and say so.
```
