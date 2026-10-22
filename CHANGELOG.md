# Changelog

All dates are local; versions follow `MAJOR.MINOR.PATCH`.

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
