# Contributing

Thanks for looking. This project is small on purpose; keep patches small too.

## Ground rules

1. **Two build targets must stay green**: `./gradlew :app:assembleDebug` and `npm run check`.
2. **No new runtime dependencies in the hub.** Node built-ins only (`http`, `crypto`, `ws`-less
   WebSocket via the global `WebSocket`). The app sticks to AndroidX/Compose/Room/OkHttp.
3. **No secrets, ever** — not in code, not in tests, not in commits. Tokens are files under
   `~/.config/voice-hub/`.
4. **No personal hosts** — no `192.168.x.x`, no personal domains, no paths under a user's home in
   committed code. Use env vars or the documented placeholders (`192.0.2.10`, `hub.example.com`).
5. Chinese comments are fine and welcome; UI strings stay in Chinese for now, English extraction is
   a tracked TODO.

## Layout

```
app/     Android client (Kotlin, Compose, Room)
hub/     Node gateway
  server.js        HTTP + SSE for the phone
  adapter/         cc-connect bridge client, protocol frames, agent routing
  lib/config.js    every knob, all env-driven
  lib/gateway.js   OpenAI-compatible TTS/ASR facade
tools/   self test + manual probes
deploy/  install.sh, systemd unit, docker compose
docs/    architecture, deployment, troubleshooting, development
```

## Development loop

```bash
npm run check                                   # hub syntax
node hub/server.js                              # run from a terminal
curl -s localhost:8097/ping
node tools/selftest.js                          # end-to-end (needs a live hub + cc-connect)
./gradlew :app:assembleDebug
```

Debug builds accept `am broadcast` hooks so you can drive the UI without touching the screen —
see [docs/development.md](docs/development.md).

## Commit messages

`<type>(<area>): <what changed>` where type ∈ feat/fix/docs/chore/refactor, area ∈ app/hub/deploy/docs.
Say **what you verified** (device, command, numbers) rather than "it works".

## Pull requests

Describe the problem, the chosen shape, and what you tested. Screenshots or `uiautomator dump` output
are gold for UI changes. If a change touches the wire format between app and hub, bump the version.
