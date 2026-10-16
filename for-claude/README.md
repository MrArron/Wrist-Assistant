# Use Wrist Assistant with your own Claude (optional)

These files are for **your own Claude** in claude.ai or the Claude app. They are not part of the watch
or phone apps, and Wrist Assistant works fine without them. They add two things:

1. **A "catch up on my notes" skill.** Notes and handoffs you save from the watch land in a Google Doc
   called "Wrist Assistant Notes". Say "catch up on my notes" and Claude works through the open items.
2. **A context refresh scheduled task.** Twice a day Claude rewrites a short "Wrist Assistant Context"
   doc about what you're working on. The companion reads it and gives it to the watch as background.

## What you need

- A Claude plan that can use custom skills: **Pro, Max, Team or Enterprise**, with **code execution**
  (file creation) turned on. Custom skills need it.
- The **Google Drive** and **Google Docs** connectors connected in Claude.
- The **Wrist Assistant companion** installed on your Android phone and connected to your Google
  account (Notes tab, **Connect**). That is what creates the two docs. Your Claude must not create
  them: the app can only open docs it created itself.

## Install the skill

Custom skills belong to your account only; each person who wants this sets it up for themselves.

1. Get the skill as a ZIP:
   - Download `wrist-assistant-catch-up-notes-skill-<version>.zip` from the
     [GitHub Releases](https://github.com/MrArron/Wrist-Assistant/releases) page, or
   - zip the [`catch-up-notes`](catch-up-notes/) folder yourself. The ZIP must contain the
     **folder** (`catch-up-notes/SKILL.md`), not `SKILL.md` at the top level.
2. In claude.ai, open **Settings -> Capabilities -> Skills** and choose **Upload skill** (the menu
   names may differ a little between app versions). Select the ZIP.
3. Make sure the skill is switched on.
4. Test it: start a chat and say **"catch up on my notes"**.

More on how skills are uploaded: [Using skills in Claude](https://claude.com/docs/skills/how-to) and the
[Agent Skills overview](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/overview).

## Set up the context task

Follow [`context-refresh-task.md`](context-refresh-task.md). It explains how to create the scheduled
task and gives the prompt to paste in.

## Optional reference

[`HANDOFF-for-main-Claude.md`](HANDOFF-for-main-Claude.md) explains the two docs and the entry format
in full. You can paste it into a chat or add it to a Claude project if you want Claude to understand
the setup in more detail. The skill does not need it.
