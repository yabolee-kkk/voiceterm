# VoiceTerm · 给你的 AI Agent 配一个语音终端

在自己机器上跑的 AI Agent，用手机**按着说话**就能对话，回答还能念出来。
局域网优先，出门自动切自己的 HTTPS 隧道，不经过任何第三方 IM。

<p align="center">
  <img src="docs/images/chat.png" width="300" alt="VoiceTerm 聊天界面">
</p>

VoiceTerm = 一个 Android 应用 + 一个零依赖的 Node 网关（hub）。hub 用
[cc-connect](https://github.com/chenhg5/cc-connect) 官方文档里的 **bridge 协议**接入，
所以你已经在 cc-connect 里配好的 Agent（Claude Code / Codex / OpenCode / Gemini CLI …）
会直接出现在手机列表里，点一下就能聊。

## 为什么不是直接用飞书/Telegram 机器人

cc-connect 已经把 Agent 接进了 IM，但 IM 是**文字优先**的：你打字，你读字。
VoiceTerm 面向"手和眼睛都忙着"的场景 —— 开车、走路、做饭、半夜躺床上 —— 需要的是**说和听**：

| | IM 机器人（cc-connect 原生） | VoiceTerm |
|---|---|---|
| 输入 | 打字 / 录一段再试听 | 按住说话，局域网里边说边出字 |
| 输出 | 读文字，点一下才朗读 | 每条回答都能念，可打断 |
| 延迟 | 经过第三方服务器，秒级 | 局域网优先，到自家机器约 30 ms |
| 控制权 | 别人的 App、别人的规则 | 自己的设备、自己的令牌、无遥测 |

两个可以一起用：打字用 cc-connect，说话用 VoiceTerm。

## 能力

- **按住说话**：局域网实时分段转写；公网线路松手后整段转一次（少几次往返）
- **回答可念**：每条回复带 🔊，服务端 TTS 流式播放，点了就停
- **通话模式**：全双工连续听 + 流式播报，说话即打断
- **多 Agent 一个列表**：cc-connect 里每加一个 `[[projects]]`，手机里自动出现
- **会话**：每个 Agent 一条主线 + 可多开；第一句话就是会话名
- **富消息**：Markdown、带按钮的卡片、图片（全屏双指缩放）、文件交系统应用打开、斜杠命令转发
- **双网络自动选线**：启动和每次网络切换都探测局域网/公网，谁快连谁
- **两台手机一套后端**：`deviceId` 相同即共用上下文；只让"发问的那台"响铃
- **小白也能起**：hub 只用 Node 内置模块（Node ≥ 22），App 只用标准 AndroidX + Compose
- **没有默认密码、没有账号、没有统计**

## 快速开始

```bash
# ① cc-connect 侧：开启 bridge（端口 9810，token 自己编一个长随机串）
#   更多见 https://github.com/chenhg5/cc-connect

# ② 装 hub（自动生成令牌、写 systemd 用户服务、启动）
git clone https://github.com/<you>/voiceterm && cd voiceterm
./deploy/install.sh
curl -s localhost:8097/ping     # {"ok":true,"hub":"voiceterm","port":8097}

# ③ 装 App
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

手机上打开「设置」填四项：局域网地址（如 `http://192.0.2.10:8097`）、公网地址（可选）、
访问令牌（`~/.config/voice-hub/api-token` 的内容）、设备标识（默认 `dev01`）。

局域网明文 `http` 需要你在 `app/src/main/res/xml/network_security_config.xml` 里加一行你的 IP
（文件里有注释示例）；公网一律用 `https`。

语音是可选增强：在 `~/.config/voice-hub/env` 里指向任意 OpenAI 兼容的 TTS/ASR 端点即可，
不配也能用（纯文字）。

## 文档

- [架构](docs/architecture.md) · [部署](docs/deployment.md) · [排错](docs/troubleshooting.md) · [开发](docs/development.md)
- [与 cc-connect 的关系](docs/bridge-notes.md)（不打包、不分发，纯外部适配器）
- 英文说明：[README.md](README.md)

## 许可证

Apache-2.0。cc-connect 是 [@chenhg5](https://github.com/chenhg5) 的独立项目，本项目**不包含**它的
源码或二进制，它自己的许可证对它生效。
