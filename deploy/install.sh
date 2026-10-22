#!/usr/bin/env bash
# VoiceTerm hub installer (Linux, systemd --user).
# Creates token files (mode 600), writes a user unit, starts the hub.
# It never prints secret values.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CFG_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/voice-hub"
UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
NODE_BIN="${NODE_BIN:-$(command -v node || true)}"

say() { printf '\033[1m%s\033[0m\n' "$*"; }
warn() { printf '\033[33m%s\033[0m\n' "$*"; }

# ---------- checks ----------
if [[ -z "$NODE_BIN" ]]; then
  warn "找不到 node。装 Node.js >= 22 再跑一次（hub 用内置的全局 WebSocket，不需要 npm 包）。"
  exit 1
fi
NODE_MAJOR="$("$NODE_BIN" -p 'process.versions.node.split(".")[0]')"
if (( NODE_MAJOR < 22 )); then
  warn "Node $("$NODE_BIN" -v) 太旧，需要 >= 22。"
  exit 1
fi

mkdir -p "$CFG_DIR" && chmod 700 "$CFG_DIR"

# ---------- tokens ----------
gen() { "$NODE_BIN" -e 'console.log(require("crypto").randomBytes(32).toString("hex"))'; }
new_token() { # $1 = file, $2 = label
  if [[ -s "$1" ]]; then
    say "沿用已有的 $2：$1"
  else
    ( umask 077; gen > "$1" )
    say "生成 $2：$1（600）"
  fi
  chmod 600 "$1"
}
new_token "$CFG_DIR/api-token"     "手机访问令牌"
new_token "$CFG_DIR/bridge-token"  "cc-connect bridge 令牌"

# ---------- optional env ----------
if [[ ! -f "$CFG_DIR/env" ]]; then
  cp "$REPO_DIR/deploy/env.example" "$CFG_DIR/env"
  chmod 600 "$CFG_DIR/env"
  say "写好配置模板：$CFG_DIR/env（语音网关地址/模型名都在这）"
fi

# ---------- unit ----------
REL_REPO="${REPO_DIR/#$HOME/\%h}"
mkdir -p "$UNIT_DIR"
cat > "$UNIT_DIR/voice-hub.service" <<UNIT
[Unit]
Description=VoiceTerm hub - phone voice gateway for AI agents
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=$REL_REPO
EnvironmentFile=-%h/.config/voice-hub/env
ExecStart=$NODE_BIN $REL_REPO/hub/server.js
Restart=always
RestartSec=3
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=default.target
UNIT

systemctl --user daemon-reload
systemctl --user enable --now voice-hub.service
sleep 2

# ---------- verify ----------
PORT="$(grep -E '^HUB_PORT=' "$CFG_DIR/env" 2>/dev/null | cut -d= -f2 || true)"
PORT="${PORT:-8097}"
if curl -fsS --max-time 3 "http://127.0.0.1:$PORT/ping" >/dev/null 2>&1; then
  say "hub 起来了：$(curl -fsS "http://127.0.0.1:$PORT/ping")"
else
  warn "hub 没响应，看日志：journalctl --user -u voice-hub -n 40 --no-pager"
fi

cat <<EOF

接下来（都在本机做，别把令牌贴到聊天里）：
  1. 把 $CFG_DIR/bridge-token 的内容填进 cc-connect 的 [bridge] token，
     并确认 bridge 的 port / path 与 $CFG_DIR/env 里的 BRIDGE_URL 一致。
  2. 手机 App 设置里填三项：
       服务地址  http://<这台机器的局域网IP>:$PORT
       访问令牌  $CFG_DIR/api-token 的内容
       设备标识  dev01
  3. 想在外面也能用？在前面加一层 HTTPS（Cloudflare Tunnel / Caddy / WireGuard），
     把公网地址填进 App 的「公网地址」。别把明文端口暴露到公网。

日志：journalctl --user -u voice-hub -f
EOF
