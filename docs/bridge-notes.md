# Working with the cc-connect bridge protocol

VoiceTerm does not embed cc-connect. The hub is a **bridge client**: it opens one WebSocket per
upstream to cc-connect's `BridgePlatform` and behaves like any other platform adapter. This page
records what the protocol actually does, and the traps found while implementing it — useful if you
are writing your own adapter (in any language).

## Handshake

```
ws://<host>:9810/bridge/ws        # header: api-key: <token>   (NOT Authorization: Bearer)
→ {"type":"register","platform":"phone","capabilities":[…],"metadata":{…}}
← register_ack
→ message / card_action / ping …
← reply / card / buttons / typing / …
```

* The token header is `api-key`. A `Bearer` header is silently rejected — you get `401` and, if you
  are debugging at 2 a.m., a very confusing evening.
* `platform` becomes part of every session key: cc-connect sees `phone:<project>:<user>`.
* `reply_ctx` is opaque to cc-connect: it echoes it back on every reply. Use it to correlate a reply
  with the message that caused it, which is how VoiceTerm decides *which phone* should ring.

## Capabilities actually matter

Declaring `card`, `buttons`, `update_message` changes what cc-connect sends you. Declare less than you
can render and you get plain text; declare more and you must handle the events.

## Attachments

Images and files travel as base64 in the `message` frame. Voice notes travel as an `audio` object
(`{mime_type, data, format, duration}`) and — important — you can send an audio message with an empty
`content` string and let cc-connect transcribe it. If you transcribe on your side instead (VoiceTerm
does, so it can reuse its own ASR and term fixer), send the transcript as `content` and the audio as
an attachment if you also want the agent to see the file.

## Multi-upstream

One cc-connect instance can only reach the agents installed on its own machine. To talk to agents on
two machines, run two cc-connect instances and give the hub two bridge links
(`HUB_BRIDGES`, see `hub/lib/config.js`). Both sockets register the same platform name; the hub claims
each agent for exactly one upstream using the `agents` filter, and rejects ambiguous claims at start-up
rather than at 3 a.m.

## Fragility to expect

The protocol is published as `1.0-draft`. Upstream evolves quickly (recent work adds intermediate
events such as tool activity and progress). Two habits keep this project alive:

1. Every frame the hub builds goes through `hub/adapter/protocol.js` — one file to patch.
2. Version-sensitive behaviour is negotiated by capability, not by guessing a version string; unknown
   events are logged and dropped instead of crashing the link.

## Not to be confused with

* `cc-connect`'s **management API** (port 9820, REST) — for consoles/TUIs, not for chat traffic.
* Its **native platforms** (Feishu, Telegram, …) — those are in-process Go adapters, not something an
  external program can join.
