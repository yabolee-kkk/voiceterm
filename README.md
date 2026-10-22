# VoiceTerm

**A voice-first mobile terminal for the AI agents running on your own machine.**
Hold the input bar, talk, let go — the agent answers, out loud if you want.

<p align="center">
  <img src="docs/images/chat.png" width="300" alt="VoiceTerm chat screen">
</p>

[English](README.md) | **[简体中文](README.zh-CN.md)**

VoiceTerm is an Android app plus a small Node gateway (the **hub**, zero npm dependencies).
The hub speaks the [cc-connect](https://github.com/chenhg5/cc-connect) **bridge protocol**, so the
agents you already run behind cc-connect — Claude Code, Codex, OpenCode, Gemini CLI, anything with a
CLI — show up in the app as a list. One tap to talk to any of them.

No cloud in the middle: your phone talks straight to your box on the LAN, and falls back to your own
HTTPS endpoint (Cloudflare Tunnel, reverse proxy, VPN) when you leave the house.

---

## Why not just use an IM bot?

cc-connect already puts your agents into Telegram / Feishu / Slack / Discord. Those apps are
**text-first**: you type, you read. VoiceTerm is built for the cases where your hands and eyes are
busy — driving, walking, cooking, half-asleep at 1am — and you would rather **talk and listen**:

| | IM bots (cc-connect native) | VoiceTerm |
|---|---|---|
| Input | type / hold-to-record then play back | hold-to-talk with live transcription |
| Output | read text, tap to hear | tap once, every reply is spoken, barge-in supported |
| Latency | O(seconds) through a third-party server | LAN-first, ~30 ms to your own box |
| Control | someone else's app, someone else's rules | your device, your tokens, no telemetry |
| When it needs you | one more unread message you scroll past | it **rings like a phone call** — ringtone, vibration, spoken out loud, until you answer |

They compose well: install cc-connect, use it for typing, install VoiceTerm for talking.

## It rings when the agent needs you

This is the part an IM bot structurally cannot do: an IM notification is one more silent line in a
list you scroll past. VoiceTerm escalates — and only for the two moments that actually block you:

| Trigger | What happens |
|---|---|
| **The agent finished the task** | full-screen "incoming call" (also from the lock screen), ringtone, looping vibration, and a spoken line: *"Sir, the task is done, please review."* |
| **The agent is waiting for your approval** | same call-style alert, title `«agent» needs your confirmation`, spoken line: *"Sir, there is a task waiting for your approval."* |

```text
Every 5.5 s  speak the line again (TTS); if TTS is unreachable it falls back to the ringtone
Up to 20x    ~2 minutes, then silence; the notification text stays until you deal with it
Answer       "接听" opens the call screen and the agent starts talking; "挂断" stops it
Interrupts   answering, hanging up, opening that session, or the service stopping — all stop it instantly
Stays quiet  ordinary replies get one normal notification chime;
             the phone that did NOT ask stays silent (reply ownership);
             if you are already looking at that session, nothing rings at all
```

Put the phone in your pocket and go do something else: it *calls* you when your agent is done or
stuck, instead of hoping you notice a badge.

The spoken lines are Chinese by default (*"主人，我的任务完成了，请您验收。"* / *"主人，有任务需要你审批了。"*);
change the wording or the language in `HubService.kt` (`val script = when { … }`, one line per case).
The alert volume follows your phone's ring volume; the whole behaviour can be switched off by
disabling the "任务完成 / 待审批" notification channel in Android settings.

## Features

- **Hold to talk** — press the input bar, speak, release. LAN: live partial transcription while you
  speak. Public link: one-shot transcription on release (fewer round trips).
- **Speak answers** — every reply has a 🔊 button; server-side TTS, streamed playback, tap to stop.
- **Call mode** — full-duplex: continuous listening, streaming speech, interrupt by talking.
- **Calls you when it matters** — task done / approval needed rings like an incoming call and
  speaks the reason; ordinary replies stay quiet.
- **All agents, one list** — each cc-connect `[[projects]]` entry appears automatically.
- **Sessions** — one main line per agent plus named threads; the first sentence becomes the name.
- **Rich replies** — markdown, cards with buttons, images (pinch-zoom full screen), files handed to
  whatever app can open them, slash commands forwarded to the agent.
- **Two networks, one phone** — probes LAN and public endpoint at start-up and on every network
  change, then reconnects to the faster one.
- **Two phones, one brain** — same `deviceId` shares the session context; only the phone that asked
  gets the notification ring, the other just records the message.
- **Small surface** — hub uses only Node built-ins (Node ≥ 22), app uses stock AndroidX + Compose.
- **No default passwords, no analytics, no accounts.** Tokens are files on your machine.

## Architecture

```
   Phone (VoiceTerm, Kotlin/Compose)          Your box
   ┌──────────────────────────┐   HTTP/SSE   ┌────────────────────────────────────┐
   │ List of agents           │ ───────────► │ hub  :8097                         │
   │ Hold-to-talk recorder    │              │   POST /v1/sessions/:agent/message │
   │ TTS player / call mode   │ ◄─────────── │   POST /v1/sessions/:agent/voice   │
   │ Room DB (local history)  │              │   GET  /v1/sessions/:agent/events  │
   └──────────────────────────┘              │   POST /v1/asr, /v1/tts            │
                ▲                            │            │ WebSocket (bridge)    │
                │                            │            ▼                       │
   optional: https://hub.example.com ──────► │      cc-connect :9810              │
   (Cloudflare Tunnel / Caddy / nginx)       │            │ stdio / CLI            │
                                             │      claude · codex · opencode · … │
                                             └────────────────────────────────────┘
```

* **hub** — HTTP + SSE for the phone; a WebSocket client of cc-connect's bridge protocol per upstream;
  a tiny OpenAI-compatible TTS/ASR facade; optional direct spawn of a local CLI agent (e.g. Hermes).
* **app** — Compose UI, hold-to-talk recording, SSE stream rendering, Room storage, foreground
  service for notifications, network-change re-routing.

## Quick start

### 1. cc-connect (the agent side)

Install and configure cc-connect with at least one project and **bridge enabled**:

```bash
# ~/.cc-connect/config.toml
[bridge]
enabled = true
port    = 9810
path    = "/bridge/ws"
token   = "CHANGE_ME"            # any long random string
```

See the [cc-connect docs](https://github.com/chenhg5/cc-connect) for binding a project to a platform
(Feishu, Telegram, …) and for how to point it at your agent CLI.

### 2. The hub (the phone side)

```bash
git clone https://github.com/<you>/voiceterm && cd voiceterm
./deploy/install.sh          # creates tokens, writes a systemd --user unit, starts the hub
curl -s localhost:8097/ping  # {"ok":true,"hub":"voiceterm","port":8097}
```

`deploy/install.sh` never prints secrets; it writes
`~/.config/voice-hub/{api-token,bridge-token,env}` with mode `600`. Prefer Docker?
`docker compose -f deploy/docker-compose.yml up -d`.

Speech is optional but nice — point the hub at any OpenAI-compatible speech endpoint:

```bash
# ~/.config/voice-hub/env
GATEWAY_URL=http://127.0.0.1:8080      # TTS+ASR base URL
TTS_MODEL=your-tts-model
ASR_MODEL=your-asr-model
# optional local fallback
WHISPER_URL=http://127.0.0.1:19980
```

Without a speech endpoint the app still works: text in, text out.

### 3. The app

```bash
cd voiceterm && ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open **Settings** and fill in:

| Field | Example | Notes |
|---|---|---|
| Hub address (LAN) | `http://192.0.2.10:8097` | your box's LAN IP |
| Public address | `https://hub.example.com` | optional; used when LAN is unreachable |
| Access token | contents of `~/.config/voice-hub/api-token` | never committed anywhere |
| Device id | `dev01` | two phones with the same id share context |

Plain `http` to a LAN host must be whitelisted in
`app/src/main/res/xml/network_security_config.xml` (one line, see the comment there).
Public endpoints must be `https`.

## Configuration reference (hub)

All configuration is environment variables; everything has a working default except the tokens.

| Variable | Default | Meaning |
|---|---|---|
| `HUB_PORT` | `8097` | HTTP port for the phone |
| `HUB_BIND` | `0.0.0.0` | bind address |
| `HUB_DEVICE` | `dev01` | default device id used in session keys |
| `HUB_AGENTS` | *(from cc-connect config)* | comma-separated agent list override |
| `BRIDGE_URL` | `ws://127.0.0.1:9810/bridge/ws` | cc-connect bridge endpoint |
| `BRIDGE_TOKEN_FILE` | `~/.config/voice-hub/bridge-token` | bridge token file |
| `HUB_BRIDGES` | *(unset)* | JSON array to add more cc-connect upstreams, with optional `agents` filter |
| `GATEWAY_URL` | `http://127.0.0.1:8080` | OpenAI-compatible TTS/ASR base |
| `TTS_MODEL` / `ASR_MODEL` / `TTS_VOICE` | vendor defaults | speech model names |
| `WHISPER_URL` | *(empty)* | local whisper fallback endpoint |
| `HERMES_BIN` / `HERMES_CWD` / `HERMES_DISABLE` | `hermes` / `$HOME` / unset | optional direct CLI agent |

## Security model

* The bearer token for the phone lives in a file (`600`) and is typed into the app **once**; it is
  never compiled into the APK, never in git, never sent anywhere except your own hub.
* Cleartext HTTP is only allowed for hosts you explicitly whitelist (loopback by default). Use HTTPS
  for anything that leaves your LAN.
* The hub is a single-user service. Put it behind a tunnel/proxy with its own auth layer
  (Cloudflare Access, mTLS, WireGuard) if you expose it to the internet.
* Nothing phones home. There is no telemetry, no update check, no account.

See [SECURITY.md](SECURITY.md) for how to report a problem.

## Relationship to cc-connect

VoiceTerm is **not** a fork of cc-connect and does not bundle it. It is an *external platform
adapter*: the hub connects to cc-connect over the officially documented
[bridge protocol](docs/bridge-notes.md) (WebSocket, `register` / `message` / `card_action`), which is
exactly what that protocol was designed for. Install cc-connect separately, keep it updated, and
VoiceTerm keeps working.

If cc-connect ever grows built-in voice, this project stays useful as the reference client for
"talk to your agents on your own network".

## Status

`1.0.1` — used daily on two phones (Android 14 / Android 9) against five agents. See
[CHANGELOG.md](CHANGELOG.md). Known limitations:

* The public-link path pays 1–3 s per request on a Cloudflare free tunnel; LAN is the good path.
* Locked-screen recording is not supported yet (Android background-mic rules).
* Spoken slash commands are not normalised (`"slash status"` will not become `/status`).
* Only one phone per `deviceId` is *answered*; sharing is deliberate, not a bug.

## Development

```bash
npm run check                 # syntax-check the hub
node tools/selftest.js        # end-to-end self test against a running hub
adb reverse tcp:8097 tcp:8097 # use the hub from the device over USB
```

Debug broadcasts (debug builds only) let you drive the UI from the command line:

```bash
adb shell am broadcast -a ai.local.voiceagent.DEBUG_SEND_TEXT   --es text "hello"
adb shell am broadcast -a ai.local.voiceagent.DEBUG_ADD_ATTACH  --es path /sdcard/x.pdf --es kind file
adb shell am broadcast -a ai.local.voiceagent.DEBUG_PICK_FILES  --es paths "a.pdf,b.png"
```

See [docs/development.md](docs/development.md).

## License

Apache-2.0. cc-connect is a separate project by [@chenhg5](https://github.com/chenhg5) and is not
included here; its own license applies to it.
