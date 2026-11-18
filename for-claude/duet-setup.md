# Use Wrist Assistant with Claude Duet (optional)

Claude Duet is Anthropic's phone app (research preview, US Pro/Max/Ultra waitlist) that works in your
connected apps on its own and messages you first. It has **no public API, webhook, intent or watch
support**, so Wrist Assistant cannot call it directly. Everything below works through things Duet and
the phone already do: a shared Google Doc and Android notifications. No watch or companion code changes,
and the watch size budget is untouched.

Not affiliated with or endorsed by Anthropic.

## 1. Let Duet work your Notes doc (the inbox)

The companion appends notes and handoffs to the "Wrist Assistant Notes" Google Doc. Duet can read that
doc through its Google Drive / Docs connectors and carry the open items forward in the background.

1. In Duet, connect **Google Drive** and **Google Docs** (the same account the companion uses).
2. Send Duet this message once. It remembers it:

   > Every morning, and whenever I ask "catch up on my notes", open my Google Doc titled exactly
   > "Wrist Assistant Notes" (ignore any with "(old)" in the title). Entries start with a header like
   > `=== OPEN | 2026-10-15 18:02 | HANDOFF | project ===`. Work only on OPEN and WAITING entries.
   > NOTE: file it with the matching project and tell me what it changes. FOLLOWUP: do the research or
   > draft. HANDOFF: continue from "Next step" without repeating what was already answered. Send me
   > one short summary of what you did. After handling an entry, change only its status to DONE
   > and add one line under it saying what you did. Ask before sending, buying or deleting anything.
   > Never create a new doc; the phone app owns it.

3. Test: hold Down on an answer on the watch to hand off, then ask Duet to "catch up on my notes".

Notes:
- Duet checks connected accounts every few hours, so this suits "deal with it later", not live answers.
  Ask it to catch up for anything urgent.
- Duet usage counts toward your Claude plan limits, the same as chats.
- If your account lets you upload skills into Duet, you can instead use
  [`catch-up-notes`](catch-up-notes/) as described in [README](README.md). The prompt above works without it.

## 2. Get Duet's nudges on the watch (and ask about them)

Duet reaches you as ordinary Android notifications, so the watch already gets them without any new code:

- **On the watch:** in the Pebble phone app, open notification settings and enable the **Claude Duet**
  app. Pebble mirrors them like any other app.
- **From the watch:** with the companion's notification access turned on (Setup), ask
  "What did Duet just tell me?". Claude reads Duet's latest notifications with the existing
  `get_recent_notifications` tool (filter app = "Duet"). If Duet's notification has an inline reply
  action, "reply to Duet: yes, do it" works too, and is confirmed on the watch first like every reply.

A dedicated Duet-forwarding feature in the companion was considered and not built: Pebble's own
notification mirroring already covers it, and pushing alerts to a watch app that is not open is not
possible over AppMessage.

## Revisit if

Anthropic ships a Duet API, connector/MCP endpoint or Android share target. Then a direct "send to Duet"
action (instead of the Docs inbox) becomes worth building.
