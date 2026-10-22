# Development

## Requirements

* JDK 17, Android SDK (compileSdk as in `app/build.gradle.kts`), Gradle wrapper included
* Node ≥ 22 (the hub uses the global `WebSocket`)
* Optional: `adb` + a device, `cc-connect` for end-to-end tests

## Build

```bash
echo "sdk.dir=$HOME/android-sdk" > local.properties     # once, git-ignored
./gradlew :app:assembleDebug                            # app/build/outputs/apk/debug/app-debug.apk
npm run check                                           # hub syntax check
node hub/server.js                                      # run the hub in the foreground
```

Low-memory machines: `gradle.properties` already caps the daemon (`-Xmx1536m`), disables parallel
builds and the configuration cache. Raise the heap if you have room.

## Run the app against a hub on the same machine

```bash
adb reverse tcp:8097 tcp:8097
adb shell am start -n ai.local.voiceagent/.MainActivity \
  -e baseUrl http://127.0.0.1:8097 \
  -e publicUrl https://hub.example.com \
  -e token "$(cat ~/.config/voice-hub/api-token)"
```

`local.properties` keeps the SDK path out of git; `adapter reverse` keeps the hub off the LAN when you
are testing on a USB-tethered device.

## Driving the UI from the shell (debug builds)

```bash
# send a text message through the real code path
adb shell am broadcast -a ai.local.voiceagent.DEBUG_SEND_TEXT --es text "hello"

# pretend the agent sent an attachment
adb shell am broadcast -a ai.local.voiceagent.DEBUG_ADD_ATTACH \
  --es path /sdcard/test.pdf --es kind file --es mime application/pdf

# pick several attachments at once (real multi-pick path)
adb shell am broadcast -a ai.local.voiceagent.DEBUG_PICK_FILES --es paths "a.pdf,b.png"

# inspect the UI as text
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml /tmp/ui.xml
```

`DEBUG_SEND_FILE` and `DEBUG_SEGMENT` exist for the single-file and audio-segment paths.

## Testing

`tools/selftest.js` walks the wire protocol against a running hub: `/ping`, session list, a text
round-trip over SSE, TTS bytes, ASR of a WAV, and the attachment frame. Point it at a live stack:

```bash
HUB=http://127.0.0.1:8097 TOKEN=$(cat ~/.config/voice-hub/api-token) node tools/selftest.js
```

There is no unit-test suite yet — the honest reason is that this code is mostly glue over two
external protocols, and the contracts that break are exercised by the self test. Pull requests that
add tests for the parsers (`protocol.js`, `asr-fix.js`, `sanitize.js`) are very welcome.

## Release

1. Bump `versionName` / `versionCode` in `app/build.gradle.kts` and `package.json`.
2. Add a `CHANGELOG.md` entry.
3. `./gradlew :app:assembleDebug`, install on a device, run the smoke list in
   [troubleshooting.md](troubleshooting.md).
4. Tag `vX.Y.Z`, attach the APK to the GitHub release.
