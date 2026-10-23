# Changelog

All dates are local; versions follow `MAJOR.MINOR.PATCH`.

## 1.0.5 — 2026-10-23

Answers that arrive while the phone is disconnected are no longer lost.

- **The hub keeps a durable outbox** of completed events (done / push / cards / buttons /
  attachments) per agent — 200 events, 6 hours. If nobody is subscribed when the agent finishes,
  the event waits instead of disappearing.
- **SSE reconnects with a cursor.** Every durable event carries an `id:` (a monotonic sequence);
  the app stores the highest one per agent and reconnects with `?since=<seq>`, so the hub replays
  exactly what was missed. Fresh installs (no cursor) do not get a replay, so old messages are never
  resurrected — and a session you deleted stays deleted.
- **The app de-duplicates by `replyCtx`** when it stores an answer, so a replayed event can never
  produce a second copy of the same reply.
- **Pending-reply TTL 5 min → 30 min**, and the in-call wait for an answer 2 min → 10 min: long
  tasks now get to finish and answer instead of being reported as failures.
- `/health` reports `outboxSeq` and the per-agent outbox depth.

## 1.0.4 — 2026-10-23

Clean up the sessions you no longer need.

- **Long-press a session to delete it** (the list header now says so). A confirm dialog names the
  session and warns that the local chat history goes with it — the agent's own context on the
  server side is not touched. Deleting removes the messages first, then the session row.

## 1.0.3 — 2026-10-23

Markdown that is actually readable on a phone.

- **Code blocks are syntax-highlighted** (keywords / strings / comments / numbers / function names /
  annotations) with a built-in tokenizer — no extra dependency. Language hint from the fence, with
  sensible comment-token handling per language (`#`, `--`, `//`).
- **Copy button on every code block** (⧉ 复制 in the top-right corner, copies the code only, with a
  toast telling you how many characters went to the clipboard).
- **Tables render as a panel instead of raw pipes.** Wide two-column tables with long descriptions
  switch to a stacked list (name on its own line, description wrapped at full width); narrow tables
  stay side-by-side with aligned columns, a dashed header rule, CJK-aware width and no orphaned
  punctuation at line starts.
- **The in-chat call button is now a filled pill** (phone icon + label, primary when idle, error-red
  while in a call) instead of a 12 sp grey text link in the app bar.

## 1.0.2 — 2026-10-22

The task-completion / approval call actually reaches you now.

- **Fixed: a second alert never came to the front.** The call notification reused one tag, so a
  follow-up alert only *updated* the existing notification — and Android never fires a full-screen
  intent for an update. The old one is now cancelled first, so every alert truly re-alerts.
- **Fixed: no ringtone in Do Not Disturb.** The `call` channel does not bypass DND and its sound
  rides the ring stream, which DND mutes (TTS kept talking because it uses the media stream).
  Ringing now detects DND and switches to `USAGE_ALARM` + the alarm tone, which priority-only DND
  still lets through. Every announcement round also starts with a real 1.2 s ring now.
- **Fixed: no call window while the app is in the foreground.** Android only shows a full-screen
  intent when the device is locked or the app is backgrounded. When the app is visible we start the
  call screen ourselves.
- **New (debug builds):** `am broadcast -a ai.local.voiceagent.DEBUG_FIRE_CALL --es agent codex`
  fires a call alert without running a real agent round. Do not put spaces in the `--es` values —
  `adb shell` treats the rest as a package filter and the broadcast is silently dropped.

## 1.0.1 — 2026-10-22

* Fixed: after dictating, the voice bar (`cancel / send / send-as-voice`) stayed pinned above the
  composer — two text fields and two send buttons on screen. The bar is now informational only
  (`🎤 transcribed — edit below`, `cancel`, `🎤 send as voice`) and disappears as soon as the
  message is sent or discarded. Text has exactly one home: the composer.
* Fixed: voice messages to a *locally spawned* agent arrived as the literal string
  `[object Object]` — `fixTerms()` returns `{ text, hits }`, not a string, and the `/voice` path
  passed the object through to the CLI prompt.
* Added: `DEBUG_SEND_TEXT` broadcast for automation.

## 1.0.0 — 2026-10-22

First public shape of the 1.0 line.

* Out-of-home support: hub port consolidated on 8097 behind a Cloudflare tunnel; the app probes LAN
  and public endpoint and reconnects on every network change.
* Public links transcribe the whole utterance on release instead of streaming partials.
* Attachments: images open in a full-screen pinch-zoom viewer, files are handed to the system via
  FileProvider; incoming payloads are written to disk instead of living in Room.
* Dictated text renders as a text bubble with a 🎤 marker (previously an empty voice bubble).
* Multi-device: only the phone that asked gets the notification; the other records silently.
* Voice bar, session naming from the first sentence, slash-command passthrough, 1500 ms
  end-of-utterance threshold, keyboard auto-hide after send.
