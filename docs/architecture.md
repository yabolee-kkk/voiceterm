# Architecture

Three processes, one wire format, no cloud.

```
Phone app ──HTTP/SSE──► hub ──WebSocket(bridge)──► cc-connect ──stdio/CLI──► agent CLI
                        └──direct spawn────────────────────────────────────► agent CLI (optional)
```

## The app (`app/`)

Kotlin + Jetpack Compose, single-module, no DI framework (a hand-rolled `App` container).

| Layer | Files | Notes |
|---|---|---|
| UI | `ui/list`, `ui/detail`, `ui/card`, `ui/call`, `ui/settings` | list of agents → sessions → chat |
| Networking | `net/HubClient.kt`, `net/Endpoints.kt` | OkHttp + SSE; endpoint probing |
| State | `data/HubEngine.kt`, `data/Daos.kt` (Room) | messages persisted locally |
| Voice | `voice/Recorder.kt`, `voice/Player.kt`, `ui/detail/ChatScreen.kt` | recording, VAD, TTS playback |
| Service | `service/HubService.kt` | foreground service, notifications, ring policy |

Choices worth knowing:

* **Route selection.** `Endpoints.pick()` probes `<lan>/ping` on Wi-Fi with a short timeout, then the
  public URL with a longer one, and validates the body's identity marker
  (`{"hub":"voiceterm"}`) — a wrong port answering `200` is a real failure mode. The chosen base URL
  is persisted; a `NetworkCallback` re-probes on every network change.
* **One composer.** Dictation lands in the composer; the recording overlay is a preview, not a second
  editor, and it disappears the moment the message is sent.
* **Local history.** Room keeps the conversation on the device; the hub keeps none.

## The hub (`hub/`)

Plain Node (≥ 22, for the global `WebSocket`), no `node_modules`.

| File | Responsibility |
|---|---|
| `hub/server.js` | HTTP API, SSE fan-out, TTS/ASR façade, optional local CLI spawn |
| `hub/adapter/hub.js` | one `BridgeLink` per upstream, agent→upstream routing, status |
| `hub/adapter/cc-bridge.js` | the WebSocket client: `register`, heartbeats, frame send |
| `hub/adapter/protocol.js` | message/attachment frame builders |
| `hub/lib/config.js` | every knob (env vars, tokens, agent list) |
| `hub/lib/gateway.js` | OpenAI-compatible `/v1/chat/completions` calls for TTS/ASR |
| `hub/lib/asr-fix.js` | post-ASR term correction (`{ text, hits }` — take `.text`) |
| `hub/lib/sanitize.js` | strips footers/reasoning before speaking |

### Phone API

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/ping` | identity marker, used for route probing |
| `GET` | `/v1/sessions` | agent list + bridge status |
| `POST` | `/v1/sessions/:agent/message` | `{text, name, deviceId}` |
| `POST` | `/v1/sessions/:agent/voice` | raw `audio/wav` body, `?name=&durationMs=` |
| `POST` | `/v1/sessions/:agent/attach` | multi-file frames + text |
| `POST` | `/v1/sessions/:agent/card_action` | button callbacks |
| `GET` | `/v1/sessions/:agent/events` | SSE: `typing` / `stream` / `done` / `card` / `push` |
| `POST` | `/v1/asr`, `/v1/tts`, `/v1/tts/stream` | speech in/out |

All of them take `Authorization: Bearer <api-token>`; `/ping` is the only exception (it exposes no
data beyond the marker).

### Attachment frames

`/attach` uploads N files in one body, each frame:

```
[4B nameLen][name][4B mimeLen][mime][8B dataLen][data]   // all big-endian
```

Limit: 10 files, 40 MB total, enforced on the phone before sending.

## Session keys

`phone:<agent>:<deviceId>` is what cc-connect sees. Two phones with the same `deviceId` therefore land
in the same conversation — which is the point, and also why the app filters notifications by
"do I have the user message this reply refers to?".
