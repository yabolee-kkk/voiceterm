# Deployment

## What runs where

| Component | Where | Why |
|---|---|---|
| cc-connect | same box as the agents | it spawns/attachs the CLI agents |
| hub | same box as cc-connect (usually) | needs loopback access to the bridge |
| VoiceTerm app | your phone | talks to the hub over LAN / tunnel |

## 1. cc-connect

```toml
# ~/.cc-connect/config.toml
[bridge]
enabled = true
port    = 9810
path    = "/bridge/ws"
token   = "<32+ random chars>"
```

Start it (`cc-connect daemon` / your usual service) and confirm the bridge is listening:

```bash
ss -ltn | grep 9810
```

## 2. hub

### systemd (recommended)

```bash
./deploy/install.sh
systemctl --user status voice-hub
journalctl --user -u voice-hub -f
```

`install.sh` creates:

```
~/.config/voice-hub/api-token      32 random bytes, hex  -> the app's token
~/.config/voice-hub/bridge-token   32 random bytes, hex  -> same value as cc-connect's [bridge] token
~/.config/voice-hub/env            optional configuration (see deploy/env.example)
~/.config/systemd/user/voice-hub.service
```

If cc-connect's bridge token already exists, put it in `~/.config/voice-hub/bridge-token` yourself;
`install.sh` only generates one when the file is missing.

### Docker

```bash
cp deploy/env.example .env        # edit
docker compose -f deploy/docker-compose.yml up -d --build
```

The container needs read access to the token files; mount `~/.config/voice-hub` read-only, and use
`network_mode: host` (Linux) if cc-connect runs on the same host as a loopback service.

### From a checkout (quick test)

```bash
node hub/server.js
```

## 3. Public access (optional)

The app only needs a public endpoint if you want to use it away from home. Any TLS terminator works;
the author's setup is a Cloudflare Tunnel:

```yaml
# ~/.cloudflared/config.yml
tunnel: voiceterm
ingress:
  - hostname: hub.example.com
    service: http://localhost:8097
  - service: http_status:404
```

Then set the app's *public address* to `https://hub.example.com`. The app prefers LAN and falls back
automatically. Expect 1–3 s per request through a free tunnel — fine for text, noticeably slow for
speech; that is why the app switches the transcription strategy on that route (one-shot instead of
streaming partials).

**Do not** publish the plain-HTTP port. Put Cloudflare Access, mTLS or a VPN in front.

## 4. The app

```bash
./gradlew :app:assembleDebug        # needs ANDROID_HOME or local.properties
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Settings → four fields → *Save and reconnect*. The list of agents should appear within a second or
two. First hold-to-talk asks for the microphone permission; grant notifications too, otherwise the
"agent finished" ring never arrives.

Keep the app out of battery optimisation (Android 12+: *Battery → Unrestricted*) or the foreground
service will be killed on some vendors.
