'use strict';
// voice-hub：手机侧入口（自有协议）⇄ cc-connect bridge protocol
const http = require('http');
const { spawn } = require('child_process');
const crypto = require('crypto');
const cfg = require('./lib/config');
const log = require('./lib/log');
const { AgentHub } = require('./adapter/hub');
const P = require('./adapter/protocol');
const { stripFooter, speakable } = require('./lib/sanitize');
const gw = require('./lib/gateway');
const { fixTerms } = require('./lib/asr-fix');

const hub = new AgentHub();
const subscribers = new Map();   // agentId -> Set<res>
const pending = new Map();       // replyCtx -> {agentId, msgId, at}

// 断线期间不能丢事件：done/push/card/buttons/attachment 落一个「待投递」环形缓冲。
// 手机 SSE 重连时带 ?since=<seq>，把漏掉的按顺序补投一遍。
// 只补投"有游标"的连接：新装的手机/刚删过会话的手机不带 since，避免把旧消息重新灌进去。
const outbox = new Map();        // agentId -> [{seq, event, data, at}]
let outboxSeq = 0;
const OUTBOX_MAX = 200;          // 每个 Agent 最多留 200 条
const OUTBOX_TTL = 6 * 3600e3;   // 6 小时
const DURABLE_EVENTS = new Set(['done', 'push', 'card', 'buttons', 'attachment', 'clear_preview']);

// 长任务（跑十几分钟很正常）不能因为 5 分钟没回话就把 pending 清掉：
// 清早了，真正的回答会被当成"非请求应答"降级成 push，手机端体验就是"答了但我没收到"。
const PENDING_TTL = 30 * 60 * 1000;

/** 记一条可补投事件，返回 seq（不补投的事件返回 0） */
function remember(agentId, event, data) {
  if (!DURABLE_EVENTS.has(event)) return 0;
  const seq = ++outboxSeq;
  data.seq = seq;
  const buf = outbox.get(agentId) || [];
  buf.push({ seq, event, data, at: Date.now() });
  while (buf.length > OUTBOX_MAX) buf.shift();
  outbox.set(agentId, buf);
  return seq;
}
const recent = [];               // 最近帧（调试用，最多 50 条）

function json(res, code, obj) {
  res.writeHead(code, { 'content-type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(obj));
}

function tokenOf(req) {
  const h = req.headers.authorization || '';
  if (h.startsWith('Bearer ')) return h.slice(7).trim();
  try { return new URL(req.url, 'http://x').searchParams.get('token') || ''; } catch { return ''; }
}

function auth(req, res) {
  let expected;
  try { expected = cfg.apiToken; } catch (e) { json(res, 500, { error: e.message }); return false; }
  const got = tokenOf(req);
  const ok = got && got.length === expected.length &&
             crypto.timingSafeEqual(Buffer.from(got), Buffer.from(expected));
  if (!ok) { json(res, 401, { error: 'unauthorized' }); return false; }
  return true;
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const CH = []; let n = 0;
    req.on('data', (c) => { n += c.length; if (n > 2e6) return reject(new Error('body too large')); CH.push(c); });
    req.on('end', () => {
      const raw = Buffer.concat(CH).toString('utf8');
      if (!raw) return resolve({});
      try { resolve(JSON.parse(raw)); } catch { reject(new Error('invalid json')); }
    });
    req.on('error', reject);
  });
}

/** 二进制请求体（手机上传的 wav 直接当 body） */
const MAX_ATTACH_BYTES = 40 * 1024 * 1024;   // 附件上限 40MB（多选最多 10 个，base64 后约 54MB，PC 端扛得住）

/** 解析"多附件帧拼接"的请求体（见 attach 路由注释） */
function parseAttachFrames(buf) {
  const out = [];
  let o = 0;
  const need = (k, what) => {
    if (o + k > buf.length) throw new Error(`附件数据不完整（缺 ${what}）`);
  };
  while (o < buf.length) {
    need(4, '文件名长度');
    const nameLen = buf.readUInt32BE(o); o += 4;
    need(nameLen, '文件名');
    const name = buf.slice(o, o + nameLen).toString('utf8'); o += nameLen;
    need(4, '类型长度');
    const mimeLen = buf.readUInt32BE(o); o += 4;
    need(mimeLen, '类型');
    const mime = buf.slice(o, o + mimeLen).toString('utf8') || 'application/octet-stream'; o += mimeLen;
    need(8, '数据长度');
    const dataLen = Number(buf.readBigUInt64BE(o)); o += 8;
    need(dataLen, '数据');
    out.push({ name, mime, data: buf.slice(o, o + dataLen) });
    o += dataLen;
  }
  return out;
}

function readRaw(req, limit = 8e6) {
  return new Promise((resolve, reject) => {
    const CH = []; let n = 0;
    req.on('data', (c) => { n += c.length; if (n > limit) return reject(new Error('音频太大')); CH.push(c); });
    req.on('end', () => resolve(Buffer.concat(CH)));
    req.on('error', reject);
  });
}

function sseWrite(res, event, data) {
  try {
    // 带 seq 的事件用 SSE 的 id: 字段下发，客户端拿它当断线续传的游标
    const id = data && data.seq ? `id: ${data.seq}\n` : '';
    res.write(`${id}event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
  } catch {}
}

function broadcast(agentId, event, data) {
  const seq = remember(agentId, event, data);   // 先记账：没有订阅者也不丢
  const subs = subscribers.get(agentId);
  if (!subs || !subs.size) {
    log.info(`[${agentId}] 无订阅者，${event}${seq ? ` seq=${seq} 已入待投递` : ' 丢弃（瞬时事件）'}`);
    return;
  }
  for (const res of subs) sseWrite(res, event, data);
}

// cc-connect 自己对语音消息的回执/提示（core/i18n.go），不是 Agent 的回答。
// 它们带着同一个 reply_ctx 回来，如果按普通 reply 处理会：
//   ① 把 pending 提前消费掉（真正的回答反而变成 unsolicited push）
//   ② 让用户以为"正在转录"就是答案
const AGENT_NOTICE_PREFIXES = [
  '🎙 正在转录语音消息', '🎙 [语音]', '🎙 语音消息', '🎙 语音转文字失败',
  '🎙 Transcribing voice', '🎙 [Voice]',
  '📬 消息已收到，将在当前任务完成后处理',
];

function isAgentNotice(text) {
  const t = String(text || '').trim();
  return AGENT_NOTICE_PREFIXES.some((p) => t.startsWith(p));
}


// ---------- 本地 CLI Agent（Hermes / .31 的 Pi）----------
const LOCAL = new Map((cfg.localAgents || []).map((a) => [a.id, a]));

/**
 * Hermes 一次性模式会把思考过程打在最前面（💭 **Reasoning:** \`\`\`…\`\`\`），
 * 手机上看是噪音、TTS 念出来更糟，这里整块切掉。
 */
function stripReasoning(t) {
  let s = String(t || '');
  s = s.replace(/^\s*💭[\s\S]*?\`\`\`[\s\S]*?\`\`\`[ \t]*\r?\n?/, '');
  s = s.replace(/💭\s*\*\*Reasoning:\*\*[\s\S]*?\`\`\`[\s\S]*?\`\`\`/g, '');
  return s.trim();
}

/** hermes 的 session_id 按「Agent+App 会话名」存下来，下一条消息用 --resume 续上 */
function readSessionStore(file) {
  try { return JSON.parse(require('fs').readFileSync(file, 'utf8')); } catch { return {}; }
}
function saveSessionId(file, key, id) {
  if (!file || !id) return;
  try {
    const all = readSessionStore(file);
    all[key] = id;
    require('fs').mkdirSync(require('path').dirname(file), { recursive: true });
    require('fs').writeFileSync(file, JSON.stringify(all, null, 2));
  } catch (e) { log.warn(`会话 id 存不下来：${e.message}`); }
}

/** 会话名要能当文件名/参数用：去掉奇怪字符 */
function localSessionId(agentId, name) {
  const raw = `${agentId}-${name || 'default'}`;
  return raw.replace(/[^a-zA-Z0-9_-]+/g, '-').slice(0, 60);
}

/**
 * 跑一次性 CLI，把 stdout 边读边推成 stream 事件，结束时发 done。
 * 手机端不用区分它和 cc-connect 的回答，走同一套渲染。
 */
function runLocalAgent(spec, { agentId, text, sessionName, msgId }) {
  const sid = localSessionId(agentId, sessionName);
  const at = Date.now();
  const base = { agentId, sessionName: sessionName || null, replyCtx: msgId, at };

  // 每条会话一个独立工作区：第一条消息新建会话，之后 --resume latest 接着聊
  let ws = spec.workDir;
  let resumeArgs = [];
  if (spec.sessionStore) {
    const key = `${agentId}|${sessionName || 'default'}`;
    const sid0 = readSessionStore(spec.sessionStore)[key];
    if (sid0) { resumeArgs = ['--resume', sid0]; ws = spec.workDir; }
  } else if (spec.wsDir) {
    ws = require('path').join(spec.wsDir, sid);
    try { require('fs').mkdirSync(ws, { recursive: true }); } catch {}
    if (require('fs').existsSync(require('path').join(ws, '.started'))) resumeArgs = ['--resume', 'latest'];
  }
  const argv = [];
  for (const a of spec.argv) {
    if (a === '%R') { argv.push(...resumeArgs); continue; }
    const v = a.replace(/%S/g, sid).replace(/%W/g, ws).replace(/%P/g, text);
    if (v !== '') argv.push(v);
  }
  let child;
  try {
    child = spawn(argv[0], argv.slice(1), { cwd: ws, env: { ...process.env, ...(spec.env || {}) } });
  } catch (e) {
    broadcast(agentId, 'done', { ...base, text: `启动失败：${e.message}`, speech: '' });
    return;
  }
  broadcast(agentId, 'typing', { ...base, state: 'on' });

  const doStream = spec.stream !== false;      // hermes 这种会打思考过程，缓冲完整答案再发
  let full = '';
  let errBuf = '';
  let finished = false;
  const finish = (note) => {
    if (finished) return;
    finished = true;
    clearTimeout(timer);
    broadcast(agentId, 'typing', { ...base, state: 'off' });
    const t = (stripReasoning(resultText || full) || note || `（${agentId} 没有输出）`).trim();
    broadcast(agentId, 'stream', { ...base, text: t, done: true, kind: 'local' });
    broadcast(agentId, 'done', { ...base, text: t, speech: speakable(t) });
    pending.delete(msgId);
    log.info(`[${agentId}] 本地 Agent 完成 字数=${t.length}${note ? ' ' + note : ''}`);
  };
  const timer = setTimeout(() => {
    try { child.kill('SIGTERM'); } catch {}
    finish('（超时，已中断）');
  }, spec.timeoutMs || 600000);

  const isJsonl = spec.jsonl === true;
  let acc = '';        // jsonl 的半行缓冲
  let resultText = ''; // result 事件里的完整回答（优先用它）
  child.stdout.on('data', (d) => {
    const chunk = d.toString();
    if (!isJsonl) {
      full += chunk;
      if (doStream) broadcast(agentId, 'stream', { ...base, text: stripReasoning(full), done: false, kind: 'local' });
      return;
    }
    acc += chunk;
    let i;
    while ((i = acc.indexOf('\n')) >= 0) {
      const line = acc.slice(0, i).trim();
      acc = acc.slice(i + 1);
      if (!line.startsWith('{')) continue;          // 「Session: xxx」这类非 JSON 行忽略
      let o; try { o = JSON.parse(line); } catch { continue; }
      if (o.type === 'text' && typeof o.text === 'string') {
        full += o.text;
        if (doStream) broadcast(agentId, 'stream', { ...base, text: full, done: false, kind: 'local' });
      } else if (o.type === 'result') {
        if (typeof o.text === 'string' && o.text.trim()) resultText = o.text;
        if (o.session_id) saveSessionId(spec.sessionStore, `${agentId}|${sessionName || 'default'}`, o.session_id);
      }
    }
  });
  child.stderr.on('data', (d) => {
    errBuf += d.toString();
    if (errBuf.length > 4000) errBuf = errBuf.slice(-4000);
    log.dbg(`[${agentId}] stderr ${d.toString().slice(0, 200)}`);
  });
  child.on('error', (e) => finish(`启动失败：${e.message}`));
  child.on('close', (code) => {
    if (code === 0 && spec.wsDir) {
      try { require('fs').writeFileSync(require('path').join(ws, '.started'), String(Date.now())); } catch {}
    }
    if (code === 0) finish(null);
    else finish(`（退出码 ${code}）${errBuf.trim().split('\n').slice(-2).join(' ').slice(0, 200)}`);
  });

  if (spec.stdin) { try { child.stdin.write(spec.stdin.replace(/%S/g, sid).replace(/%P/g, text)); } catch {} }
  try { child.stdin.end(); } catch {}
  return child;
}

// ---------- 上行帧 → 手机事件 ----------
function onFrame(f) {
  const agentId = f.agentId;
  if (!agentId) return log.warn('帧缺 agentId，丢弃', f.type);

  // 闸①：只接自己平台的会话帧。
  // cc-connect 会把该 project 下其它平台（飞书等）的帧也推给唯一适配器，
  // 不拦就会出现"手机上看不到的消息其实是别人的回复"。
  if (f.sessionKey && !String(f.sessionKey).startsWith(P.PLATFORM + ':')) {
    log.dbg(`丢弃非本平台帧 ${f.sessionKey}`);
    return;
  }

  hub.markReceived(agentId);
  recent.push({ at: f.at, agentId, type: f.type });
  if (recent.length > 50) recent.shift();

  const base = { agentId, sessionName: f.sessionName, replyCtx: f.replyCtx, at: f.at };

  switch (f.type) {
    case 'typing_start': broadcast(agentId, 'typing', { ...base, state: 'on' }); break;
    case 'typing_stop':  broadcast(agentId, 'typing', { ...base, state: 'off' }); break;

    case 'preview_start':
    case 'update_message':
    case 'reply_stream':
      broadcast(agentId, 'stream', {
        ...base, text: stripFooter(f.content || ''), done: false, kind: f.type,
      });
      break;

    case 'reply': {
      const text = stripFooter(f.content || '');

      // 语音回执/排队提示：只做提示，不消费 pending，也不当成回答
      if (isAgentNotice(text)) {
        broadcast(agentId, 'notice', { ...base, text });
        log.info(`[${agentId}] 提示帧：${text.slice(0, 24)}`);
        break;
      }

      // 闸②：replyCtx 不在 pending 里 = 不是这次请求的应答。
      // 来源可能是：cron/heartbeat 主动推送、上次断线后的补投、探针残留。
      // 不能当成"当前问题的回答"，单独走 push 事件。
      const owned = f.replyCtx && pending.has(f.replyCtx);
      if (!owned) {
        broadcast(agentId, 'push', {
          ...base, text, speech: speakable(text), unsolicited: true,
          reason: f.replyCtx ? 'unknown-reply-ctx' : 'no-reply-ctx',
        });
        log.warn(`[${agentId}] 非请求应答 replyCtx=${f.replyCtx} 字数=${text.length} → 记为 push`);
        break;
      }
      broadcast(agentId, 'stream', { ...base, text, done: true, kind: 'reply' });
      broadcast(agentId, 'done', { ...base, text, speech: speakable(text) });
      pending.delete(f.replyCtx);
      log.info(`[${agentId}] 完成 replyCtx=${f.replyCtx} 字数=${text.length}`);
      break;
    }

    case 'card':    broadcast(agentId, 'card', { ...base, card: f.raw.card }); break;
    case 'buttons': broadcast(agentId, 'buttons', { ...base, content: f.content || null, buttons: f.raw.buttons }); break;
    case 'delete_message': broadcast(agentId, 'clear_preview', { ...base, handle: f.raw.preview_handle }); break;
    case 'audio': case 'image': case 'file':
      broadcast(agentId, 'attachment', { ...base, kind: f.type, payload: f.raw });
      break;
    default: break;
  }
}

// ---------- HTTP ----------
const server = http.createServer(async (req, res) => {
  const path = new URL(req.url, 'http://x').pathname;
  res.setHeader('access-control-allow-origin', '*');

  // 无鉴权的最小存活探针（systemd / 看门狗用，不泄露信息）
  // 探线用：带上身份，App 才不会把「别的服务回 200」当成 hub 通（端口填错时踩过）
  if (path === '/ping') {
    res.setHeader('access-control-allow-origin', '*');
    return json(res, 200, { ok: true, hub: 'voice-hub-v2', port: cfg.port });
  }

  if (!auth(req, res)) return;

  if (path === '/health') {
    return json(res, 200, {
      ok: true,
      bridge: hub.bridgeStatus(),
      agents: hub.status(),
      subscribers: [...subscribers.entries()].map(([k, v]) => [k, v.size]),
      pending: pending.size,
    outboxSeq,
    outbox: Object.fromEntries([...outbox.entries()].map(([k, v]) => [k, v.length])),
      recent,
    });
  }

  if (path === '/v1/sessions' && req.method === 'GET') {
    return json(res, 200, { sessions: hub.status() });
  }

  // 发消息
  let m = path.match(/^\/v1\/sessions\/([^/]+)\/message$/);
  if (m && req.method === 'POST') {
    const agentId = decodeURIComponent(m[1]);
    let body;
    try { body = await readBody(req); } catch (e) { return json(res, 400, { error: e.message }); }
    const text = String(body.text || '').trim();
    if (!text) return json(res, 400, { error: 'text 不能为空' });
    const name = body.name ? String(body.name) : null;
    const deviceId = body.deviceId || cfg.deviceId;
    const msgId = 'm' + Date.now() + '-' + crypto.randomBytes(3).toString('hex');
    try { hub.require(agentId); } catch (e) { return json(res, 404, { error: e.message }); }

    // 本地 CLI Agent（Hermes / .31 的 Pi）：hub 直接跑，不经 bridge
    if (LOCAL.has(agentId)) {
      pending.set(msgId, { agentId, msgId, at: Date.now(), local: true });
      runLocalAgent(LOCAL.get(agentId), { agentId, text, sessionName: name, msgId });
      log.info(`[${agentId}] 收消息（本地）msgId=${msgId} 字数=${text.length}`);
      return json(res, 202, { ok: true, msgId, agentId, local: true });
    }

    const frame = P.buildMessage({ msgId, agentId, deviceId, name, text });
    try { hub.send(agentId, frame); }
    catch (e) { return json(res, e.code === 'NOT_READY' ? 503 : 502, { error: e.message }); }

    pending.set(msgId, { agentId, msgId, at: Date.now() });
    log.info(`[${agentId}] 收消息 msgId=${msgId} 字数=${text.length}`);
    return json(res, 202, { ok: true, msgId, sessionKey: frame.session_key, agentId });
  }

  // 事件流
  m = path.match(/^\/v1\/sessions\/([^/]+)\/events$/);
  if (m && req.method === 'GET') {
    const agentId = decodeURIComponent(m[1]);
    if (!hub.has(agentId)) return json(res, 404, { error: `未知 Agent: ${agentId}` });
    res.writeHead(200, {
      'content-type': 'text/event-stream; charset=utf-8',
      'cache-control': 'no-cache, no-transform',
      connection: 'keep-alive', 'x-accel-buffering': 'no',
    });
    res.write(': connected\n\n');
    if (!subscribers.has(agentId)) subscribers.set(agentId, new Set());
    subscribers.get(agentId).add(res);
    log.info(`[${agentId}] SSE +1（共 ${subscribers.get(agentId).size}）`);

    // 断线续传：手机带着上次收到的 seq 回来，把这期间完成的事件按顺序补投。
    // 没有 since = 新装的手机 / 刚清过数据，不补投（否则旧消息会重新灌进会话）。
    const sinceRaw = new URL(req.url, 'http://x').searchParams.get('since')
      || req.headers['last-event-id'] || '';
    const since = Number(sinceRaw) || 0;
    if (since > 0) {
      const miss = (outbox.get(agentId) || []).filter((e) => e.seq > since);
      for (const e of miss) sseWrite(res, e.event, e.data);
      if (miss.length) log.info(`[${agentId}] SSE 补投 ${miss.length} 条（since=${since}）`);
    }
    const hb = setInterval(() => { try { res.write(': hb\n\n'); } catch {} }, 15000);
    if (hb.unref) hb.unref();
    let cleaned = false;
    const cleanup = () => {
      if (cleaned) return;          // close 与 error 会各触发一次，只认第一次
      cleaned = true;
      clearInterval(hb);
      const s = subscribers.get(agentId);
      if (s) { s.delete(res); if (!s.size) subscribers.delete(agentId); }
      log.info(`[${agentId}] SSE -1（剩 ${s ? s.size : 0}）`);
    };
    req.on('close', cleanup); req.on('error', cleanup);
    return;
  }

  // 文件/图片：body 直接是文件字节；query 带 name / filename / mime / text(说明)
  // 文件/图片（支持多选）：body 是"帧拼接"——每帧 [4B名长][名][4B类型长][类型][8B数据长][数据]
  // 老的单文件请求（不带 count）仍然按裸字节处理，curl 测试不受影响
  m = path.match(/^\/v1\/sessions\/([^/]+)\/attach$/);
  if (m && req.method === 'POST') {
    const agentId = decodeURIComponent(m[1]);
    const u = new URL(req.url, 'http://x');
    const name = u.searchParams.get('name') || null;
    const caption = (u.searchParams.get('text') || '').trim();
    const multi = u.searchParams.has('count');
    let body;
    try { body = await readRaw(req, MAX_ATTACH_BYTES); }
    catch (e) { return json(res, 413, { error: e.message }); }
    if (!body || !body.length) return json(res, 400, { error: '空文件' });

    let items;
    try {
      items = multi
        ? parseAttachFrames(body)
        : [{
            name: u.searchParams.get('filename') || 'file.bin',
            mime: u.searchParams.get('mime') || req.headers['content-type'] || 'application/octet-stream',
            data: body,
          }];
    } catch (e) { return json(res, 400, { error: e.message }); }
    if (!items.length) return json(res, 400, { error: '没有附件' });
    if (LOCAL.has(agentId)) {
      return json(res, 400, { error: `${agentId} 是本机/远端 CLI Agent，暂不支持附件（可以发文字或语音）` });
    }

    const deviceId = cfg.deviceId;
    const msgId = 'm' + Date.now() + '-' + crypto.randomBytes(3).toString('hex');
    try { hub.require(agentId); } catch (e) { return json(res, 404, { error: e.message }); }

    // 图片走 images[]，其它走 files[]（cc-connect 分别落到 images/ 和 attachments/）
    const images = [];
    const files = [];
    for (const it of items) {
      const entry = { mime_type: it.mime, data: it.data.toString('base64'), file_name: it.name };
      if (it.mime.startsWith('image/')) images.push(entry); else files.push(entry);
    }
    const frame = P.buildMessage({ msgId, agentId, deviceId, name, text: caption || '', images, files });
    try { hub.send(agentId, frame); }
    catch (e) { return json(res, e.code === 'NOT_READY' ? 503 : 502, { error: e.message }); }

    pending.set(msgId, { agentId, msgId, at: Date.now() });
    const total = items.reduce((n, it) => n + it.data.length, 0);
    log.info(`[${agentId}] 收文件 msgId=${msgId} ${items.length} 个 ${total}B ${items.map((x) => x.name).join(', ').slice(0, 120)}`);
    return json(res, 202, {
      ok: true, msgId, sessionKey: frame.session_key, agentId,
      count: items.length, totalBytes: total,
    });
  }

  // 语音消息：body 直接是 wav，query/header 带 name 与时长
  m = path.match(/^\/v1\/sessions\/([^/]+)\/voice$/);
  if (m && req.method === 'POST') {
    const agentId = decodeURIComponent(m[1]);
    const u = new URL(req.url, 'http://x');
    const name = u.searchParams.get('name') || null;
    const durationMs = Number(u.searchParams.get('durationMs') || 0);
    let wav;
    try { wav = await readRaw(req); } catch (e) { return json(res, 400, { error: e.message }); }
    if (!wav.length) return json(res, 400, { error: '音频为空' });
    try { hub.require(agentId); } catch (e) { return json(res, 404, { error: e.message }); }

    if (LOCAL.has(agentId)) {
      const msgId = 'm' + Date.now() + '-' + crypto.randomBytes(3).toString('hex');
      pending.set(msgId, { agentId, msgId, at: Date.now(), local: true });
      (async () => {
        try {
          const { text } = await gw.asr(wav);
          // 注意：fixTerms 返回的是 { text, hits } 对象，不是字符串。
          // 直接把它当正文传给 Agent，%P 会被 String() 成 "[object Object]"
          // —— 之前发给 hermes 的语音条永远得到这句抱怨，就是这里。
          const fixed = fixTerms(String(text || '').trim());
          const clean = String(fixed && typeof fixed === 'object' ? fixed.text : fixed || '').trim();
          if (fixed && fixed.hits && fixed.hits.length) log.info(`ASR 术语纠正：${fixed.hits.map((h) => `${h.from}→${h.to}`).join(', ')}`);
          if (!clean) { broadcast(agentId, 'done', { agentId, sessionName: name, replyCtx: msgId, at: Date.now(), text: '（没听清）', speech: '' }); pending.delete(msgId); return; }
          runLocalAgent(LOCAL.get(agentId), { agentId, text: clean, sessionName: name, msgId });
        } catch (e) {
          broadcast(agentId, 'done', { agentId, sessionName: name, replyCtx: msgId, at: Date.now(), text: `转写失败：${e.message}`, speech: '' });
          pending.delete(msgId);
        }
      })();
      return json(res, 202, { ok: true, msgId, agentId, bytes: wav.length, local: true });
    }

    const msgId = 'm' + Date.now() + '-' + crypto.randomBytes(3).toString('hex');
    // content 留空：让 cc-connect 自己转写（它会把转写结果当消息内容，
    // 并置 FromVoice=true，于是 tts_mode=voice_only 时回复也走语音）
    const frame = P.buildMessage({
      msgId, agentId, deviceId: cfg.deviceId, name, text: '',
      audio: {
        mime_type: 'audio/wav',
        data: wav.toString('base64'),
        format: 'wav',
        duration: durationMs > 0 ? Math.round(durationMs / 1000) : undefined,
      },
    });
    try { hub.send(agentId, frame); }
    catch (e) { return json(res, e.code === 'NOT_READY' ? 503 : 502, { error: e.message }); }
    pending.set(msgId, { agentId, msgId, at: Date.now() });
    log.info(`[${agentId}] 收语音 msgId=${msgId} ${wav.length}B ${durationMs}ms`);
    return json(res, 202, { ok: true, msgId, agentId, bytes: wav.length });
  }

  // 卡片按钮回传
  m = path.match(/^\/v1\/sessions\/([^/]+)\/card_action$/);
  if (m && req.method === 'POST') {
    const agentId = decodeURIComponent(m[1]);
    let body;
    try { body = await readBody(req); } catch (e) { return json(res, 400, { error: e.message }); }
    const frame = P.buildCardAction({
      agentId, deviceId: cfg.deviceId, name: body.name ? String(body.name) : null,
      action: String(body.action || ''), replyCtx: String(body.replyCtx || ''),
    });
    try { hub.send(agentId, frame); }
    catch (e) { return json(res, e.code === 'NOT_READY' ? 503 : 502, { error: e.message }); }
    log.info(`[${agentId}] 卡片回传 action=${frame.action} replyCtx=${frame.reply_ctx || '-'}`);
    return json(res, 202, { ok: true });
  }

  // ---------- 语音：文本 → wav（手机点小喇叭朗读一条回复） ----------
  if (path === '/v1/tts' && req.method === 'POST') {
    let body;
    try { body = await readBody(req); } catch (e) { return json(res, 400, { error: e.message }); }
    const text = speakable(String(body.text || ''));
    if (!text) return json(res, 400, { error: 'text 为空' });
    try {
      const buf = await gw.tts(text, { voice: body.voice ? String(body.voice) : undefined });
      res.writeHead(200, {
        'content-type': 'audio/wav',
        'content-length': buf.length,
        'cache-control': 'no-store',
      });
      return res.end(buf);
    } catch (e) {
      log.warn(`TTS 失败：${e.message}`);
      return json(res, 502, { error: e.message });
    }
  }

  // ---------- 流式 TTS：边合成边吐 PCM（全双工通话用，首包 ~0.5s） ----------
  if (path === '/v1/tts/stream' && req.method === 'POST') {
    let body;
    try { body = await readBody(req); } catch (e) { return json(res, 400, { error: e.message }); }
    const text = speakable(String(body.text || ''));
    if (!text) return json(res, 400, { error: 'text 为空' });

    res.writeHead(200, {
      'content-type': 'application/octet-stream',
      'x-format': 'pcm16',
      'x-sample-rate': '24000',
      'x-channels': '1',
      'cache-control': 'no-store',
      'x-accel-buffering': 'no',
    });
    let closed = false;
    res.on('close', () => { closed = true; });
    try {
      const bytes = await gw.ttsStream(text, { voice: body.voice ? String(body.voice) : undefined }, (pcm) => {
        if (closed) return;
        try { res.write(pcm); } catch { closed = true; }
      });
      if (!closed) res.end();
      log.info(`[stream] ${text.length}字 → ${bytes}B PCM 已推送`);
    } catch (e) {
      log.warn(`流式 TTS 失败：${e.message}`);
      if (!closed) {
        try { res.end(); } catch {}
      }
    }
    return;
  }

  // ---------- 语音：wav → 文本（按住说话） ----------
  if (path === '/v1/asr' && req.method === 'POST') {
    let wav;
    try { wav = await readRaw(req); } catch (e) { return json(res, 400, { error: e.message }); }
    if (!wav.length) return json(res, 400, { error: '音频为空' });
    try {
      const { text, engine } = await gw.asr(wav);
      const fixed = fixTerms(text);
      if (fixed.hits.length) log.info(`ASR 术语纠正：${fixed.hits.map((h) => `${h.from}→${h.to}`).join(', ')}`);
      return json(res, 200, { ok: true, text: fixed.text, engine });
    } catch (e) {
      log.warn(`ASR 失败：${e.message}`);
      return json(res, 502, { error: e.message });
    }
  }

  return json(res, 404, { error: 'not found', path });
});

// ---------- 启动 ----------
// 超时清理：5 分钟没有应答的请求视为丢失（cc-connect 重投时会走 push 分支）
setInterval(() => {
  const now = Date.now();
  for (const [agentId, buf] of outbox) {
    const keep = buf.filter((e) => now - e.at <= OUTBOX_TTL);
    if (keep.length) outbox.set(agentId, keep); else outbox.delete(agentId);
  }
  for (const [ctx, v] of pending) {
    if (now - v.at > PENDING_TTL) { pending.delete(ctx); log.warn(`pending 超时清理 ${ctx}`); }
  }
}, 60000).unref();

hub.start();                       // 先建 link，再挂回调（link 连接是异步的，来得及）
hub.onFrame(onFrame);
hub.onUpstreamError((f) => log.warn('上游错误帧', f));
server.listen(cfg.port, cfg.bind, () => {
  log.info(`voice-hub 监听 :${cfg.port}  Agent: ${cfg.agents.join(', ')}`);
});

function shutdown(sig) {
  log.info(`收到 ${sig}，关闭`);
  hub.stop();
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 3000).unref();
}
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));
