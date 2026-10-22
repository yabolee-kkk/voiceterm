# Images

| File | What it is |
|---|---|
| `call.png` | Real screenshot (1080×2400): the incoming-call screen a task completion triggers — `来电` / `codex 干完了` / `主人，我的任务完成了，请您验收` / `语音正在播报...` with 挂断 / 接听. Status bar and gesture bar cropped out. |
| `chat.png` | Real screenshot (1080×2238): a message sent from the phone and the agent's reply, the 🔊 read-aloud control and the composer. |

Both are captured on a real device over adb (`adb exec-out screencap -p`, then cropped).
When replacing them, keep the width around 1080 px and make sure no personal data
(IPs, tokens, session names, other apps' notifications) is visible.
