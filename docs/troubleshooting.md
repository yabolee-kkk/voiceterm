# Troubleshooting

Symptom → what to check, in order.

## The app shows "list failed" / no agents

1. `curl -s http://<hub-ip>:8097/ping` from another machine — expect
   `{"ok":true,"hub":"voiceterm","port":8097}`. A bare `200` from something else means wrong port:
   the app validates that marker on purpose.
2. `journalctl --user -u voice-hub -f` while opening the app — do you see `SSE +1`?
3. `curl -s -H "Authorization: Bearer $(cat ~/.config/voice-hub/api-token)" \
   http://127.0.0.1:8097/v1/sessions` — agents must be `ready:true` and `upstream` filled.
4. Wrong token → all requests `401`. Wrong IP → the app falls back to the public address, if set.

## Agent shows offline but cc-connect is running

* The bridge token must match on both sides: `~/.config/voice-hub/bridge-token` vs `[bridge] token`.
* cc-connect logs should show `bridge: adapter registered platform=phone`.
* Multiple upstreams (`HUB_BRIDGES`): check `/health` — every named bridge must be `ready`.

## Speech does nothing

* No speech endpoint configured → text still works. Check `~/.config/voice-hub/env` for
  `GATEWAY_URL`, `TTS_MODEL`, `ASR_MODEL`.
* `curl -s -X POST --data-binary @sample.wav -H "Authorization: Bearer $TOK" \
  http://127.0.0.1:8097/v1/asr` — if this fails, the hub cannot reach the gateway; look at its log
  line `ASR(…) ok … → N字`.
* Fallback: point `WHISPER_URL` at a local faster-whisper server (OpenAI-compatible).

## Voice messages reach the agent as gibberish or "[object Object]"

That used to be a real bug (an object passed where text was expected); it is fixed in 1.0.1. If you
see it again, check the hub's `/voice` path for the local-agent branch and make sure `.text` is taken
from the `fixTerms()` result.

## Everything works on Wi-Fi, nothing on mobile data

* Public endpoint set and reachable? `curl -s https://hub.example.com/ping` from an outside network.
* Free Cloudflare tunnels take 1–3 s per request; the app's probe timeout is 12 s on purpose.
* If the probe times out the app still uses the public URL rather than giving up — check the route
  label in Settings ("public · 3299ms" is normal on a hotspot).

## Notification ring never arrives

* Notification permission granted?
* Two phones with the same `deviceId`: only the one that asked rings; the other records silently.
  That is deliberate.
* Vendor battery savers kill the foreground service — exempt the app.

## Recording stops when the screen locks

Known limitation: `AudioRecord` from a background context needs a foreground service with the
`microphone` type plus the state machine living in the service. Tracked, not implemented.

## Commands like `/status` do nothing

Slash commands are forwarded verbatim to the agent. Spoken input produces Chinese words
("斜杠 status"), which are *not* normalised into `/status` yet — type them or use the agent's own
inline buttons.
