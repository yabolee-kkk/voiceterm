'use strict';
const fs = require('fs'), path = require('path'), os = require('os');
const HOME = os.homedir();

function readSecret(file, label) {
  try {
    const v = fs.readFileSync(file, 'utf8').trim();
    if (!v) throw new Error('empty');
    return v;
  } catch (e) {
    throw new Error(`缺少 ${label}：${file}（${e.message}）`);
  }
}

/** 从 cc-connect 的 config.toml 里读出所有 [[projects]] 的 name */
function ccProjects() {
  const file = process.env.CC_CONFIG || path.join(HOME, '.cc-connect/config.toml');
  try {
    const text = fs.readFileSync(file, 'utf8');
    const chunks = text.split(/^\s*\[\[projects\]\]/m).slice(1);
    const names = [];
    for (const c of chunks) {
      const m = /^\s*name\s*=\s*"([^"]+)"/m.exec(c);
      if (m) names.push(m[1].trim());
    }
    return names;
  } catch (e) {
    return [];
  }
}

/**
 * 不经过 cc-connect 的"本地 CLI Agent"。
 * cc-connect 要求每个 project 至少绑定一个平台（飞书/Telegram…）且要凭证，
 * 有些 CLI Agent 没有自己的 bot，所以由 hub 直接 spawn 一次性命令，
 * stdout 边读边推给手机（SSE 的 stream/done 事件）。
 *   argv  ：参数数组，不走 shell；%S = 会话名，%P = 提示词，%R = 续会话参数
 * 环境变量：HERMES_BIN（可执行文件路径）、HERMES_CWD（工作目录）、HERMES_AGENT_ID
 * 不想要这一项就把 HERMES_DISABLE=1 设上。
 */
const LOCAL_AGENTS = process.env.HERMES_DISABLE === '1' ? [] : [
  {
    id: process.env.HERMES_AGENT_ID || 'hermes',
    workDir: process.env.HERMES_CWD || HOME,
    // hermes chat -q … --oneshot --format stream-json 是它的一次性+流式入口：
    // 每行一个 JSON 事件（text 增量 / result 带 session_id + token 统计），
    // 记住 session_id 下回用 --resume 续上下文。%R 展开成 ['--resume', <会话 id>]
    jsonl: true,
    sessionStore: path.join(HOME, '.config/voice-hub/hermes-sessions.json'),
    argv: [process.env.HERMES_BIN || 'hermes', 'chat', '-q', '%P', '--oneshot',
           '--format', 'stream-json', '%R'],
    timeoutMs: 600000,
  },
];

/**
 * cc-connect bridge 上游，可以有多条。
 * 默认只有一条 —— 本机的 cc-connect。要接远端机器上的 cc-connect
 * （比如某个 Agent 装在同一局域网的另一台电脑上），用环境变量覆盖：
 *   export HUB_BRIDGES='[{"name":"box2","url":"ws://10.0.0.31:9811/bridge/ws",
 *                          "tokenFile":"/home/me/.config/voice-hub/bridge-token-2",
 *                          "agents":["pi"]}]'
 * agents 字段声明这条上游只负责哪些 Agent（不写 = 不限）。
 */
function loadBridges() {
  const raw = process.env.HUB_BRIDGES;
  if (raw) {
    let arr;
    try {
      arr = JSON.parse(raw);
    } catch (e) {
      throw new Error(`HUB_BRIDGES 不是合法 JSON：${e.message}`);
    }
    if (!Array.isArray(arr) || !arr.length) throw new Error('HUB_BRIDGES 应为非空数组');
    for (const b of arr) if (!b.name || !b.url || !b.tokenFile) throw new Error('HUB_BRIDGES 每项都要有 name/url/tokenFile');
    return arr;
  }
  return [{
    name: 'local',
    url: process.env.BRIDGE_URL || 'ws://127.0.0.1:9810/bridge/ws',
    tokenFile: process.env.BRIDGE_TOKEN_FILE || path.join(HOME, '.config/voice-hub/bridge-token'),
  }];
}

const BRIDGES = loadBridges();

module.exports = {
  readSecret,
  // 手机侧入口
  port: Number(process.env.HUB_PORT || 8097),
  bind: process.env.HUB_BIND || '0.0.0.0',
  apiTokenFile: path.join(HOME, '.config/voice-hub/api-token'),
  get apiToken() { return readSecret(this.apiTokenFile, '手机侧 API token'); },

  // cc-connect bridge（可多条上游，见上面 loadBridges）
  bridges: BRIDGES,
  get bridgeUrl() { return this.bridges[0].url; },
  get bridgeToken() { return readSecret(this.bridges[0].tokenFile, 'bridge token'); },
  platform: 'phone',
  localAgents: LOCAL_AGENTS,

  // 要接入的 Agent（= cc-connect 的 projects 名）
  // 优先 HUB_AGENTS 环境变量；没设就自动读 cc-connect 的 config.toml，
  // 这样以后加 Agent 只要在 cc-connect 里加一个 [[projects]]，hub 不用改代码。
  agents: (() => {
    const envList = (process.env.HUB_AGENTS || '').split(',').map(s => s.trim()).filter(Boolean);
    if (envList.length) return envList;
    const fromCc = ccProjects();
    const base = fromCc.length ? fromCc : ['fast-cc', 'codex', 'opencode'];
    const extra = [];
    // 本机/远端 CLI 型 Agent
    for (const a of LOCAL_AGENTS) if (!base.includes(a.id)) extra.push(a.id);
    // 其它 cc-connect 上游声明的 Agent
    for (const b of BRIDGES) {
      for (const id of (b.agents || [])) if (!base.includes(id) && !extra.includes(id)) extra.push(id);
    }
    return base.concat(extra);
  })(),

  // 语音：TTS / ASR 走 OpenAI 兼容的语音网关（默认本机 8080）
  gatewayUrl: process.env.GATEWAY_URL || 'http://127.0.0.1:8080/v1',
  gatewayKeyFile: path.join(HOME, '.config/voice-gateway/api-key'),
  get gatewayKey() { return readSecret(this.gatewayKeyFile, 'AI 网关下游 Key'); },
  ttsModel: process.env.TTS_MODEL || 'mimo-v2.5-tts',
  asrModel: process.env.ASR_MODEL || 'mimo-v2.5-asr',
  ttsVoice: process.env.TTS_VOICE || '冰糖',
  // 本地 whisper 兜底（网关不可达时用）
  whisperUrl: process.env.WHISPER_URL || '',

  // 设备（单用户单手机，留扩展位）
  deviceId: process.env.HUB_DEVICE || 'dev01',
};
