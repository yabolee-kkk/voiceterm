# Security

## Reporting

Open a GitHub security advisory (preferred) or email the maintainer. Please do not file a public
issue for anything that lets someone else reach your hub or your agents.

## Threat model in one paragraph

VoiceTerm assumes **you own both ends**: the phone and the machine running the hub. The hub is a
single-user service with one bearer token and no user database. It is meant to be reached over your
LAN, or over a tunnel you control. Anything that lets an unauthenticated party speak to your agents,
read your conversation history, or make the hub execute commands is a security bug.

## What is stored where

| Secret | Location | Notes |
|---|---|---|
| Phone access token | `~/.config/voice-hub/api-token` (`600`) + the app's private SharedPreferences | typed once into the app; never in the APK, never in git |
| cc-connect bridge token | `~/.config/voice-hub/bridge-token` (`600`) | shared secret between hub and cc-connect |
| Speech gateway key | `~/.config/voice-gateway/api-key` (`600`) | read by the hub, never sent to the phone |
| Conversation history | the phone (Room DB) | not uploaded anywhere by VoiceTerm |

## Hardening checklist

1. Keep `api-token` long and random (`install.sh` generates 32 bytes from `crypto.randomBytes`).
2. Never expose the hub's plain-HTTP port to the internet. If you need remote access, put a TLS
   terminator in front (Cloudflare Tunnel + Access, Caddy, nginx, WireGuard) and keep HTTPS on.
3. The app's `network_security_config.xml` deliberately blocks cleartext except for hosts you list;
   do not add `0.0.0.0/0`-style exceptions — Android would refuse them anyway, and please do not
   flip `cleartextTrafficPermitted="true"` in `base-config`.
4. Rotate tokens by deleting the files and restarting the hub; the old token stops working
   immediately.
5. cc-connect itself can execute commands on your machine. Bind it to loopback, and treat its
   bridge port as you would SSH.
