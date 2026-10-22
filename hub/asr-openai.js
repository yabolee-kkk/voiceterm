'use strict';
// OpenAI 兼容的 /v1/audio/transcriptions 网关：给 cc-connect 的 [speech.openai] 用。
//
// 为什么要这一层：
//   cc-connect 的语音消息转写走它自己的 STT provider（openai 协议）。
//   它原本指向本机 faster-whisper（base 模型），实测中文错得厉害：
//     "这是语音链路自测，冰糖在说话" → "這是語音鏈錄字測,冰糖在說話"（还变繁体）
//   这一层把请求转给网关上的 ASR 模型（质量好），失败再回落本机 faster-whisper。
//
// 两种用法都支持：
//   · multipart/form-data（cc-connect 就用这种，字段名 file）
//   · 裸 body + content-type: audio/wav（自己调试方便）
//
// 起法：systemd --user 单元 voice-asr.service，监听 127.0.0.1:19981

const http = require('http');
const log = require('./lib/log');

const PORT = Number(process.env.ASR_PORT || 19981);
const HOST = process.env.ASR_HOST || '127.0.0.1';
const MAX_BODY = 16 * 1024 * 1024;

// 复用 hub 的网关客户端（MiMo 主 + 本地 whisper 回落）
const gw = require('./lib/gateway');
const { fixTerms } = require('./lib/asr-fix');

function readAll(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let n = 0;
    req.on('data', (c) => {
      n += c.length;
      if (n > MAX_BODY) return reject(new Error('body too large'));
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

/** 从 multipart 里抠出第一个文件部分（字段名不限，cc-connect 用 file） */
function extractFile(buf, contentType) {
  const m = /boundary=(?:"([^"]+)"|([^;]+))/i.exec(contentType || '');
  if (!m) return null;
  const boundary = Buffer.from('--' + (m[1] || m[2]).trim());
  let pos = buf.indexOf(boundary);
  while (pos >= 0) {
    const headStart = pos + boundary.length + 2;          // 跳过 \r\n
    const headEnd = buf.indexOf('\r\n\r\n', headStart);
    if (headEnd < 0) break;
    const head = buf.slice(headStart, headEnd).toString('utf8');
    const next = buf.indexOf(boundary, headEnd);
    if (next < 0) break;
    if (/filename=/i.test(head)) {
      const body = buf.slice(headEnd + 4, next - 2);       // 去掉尾部 \r\n
      const name = /filename="([^"]*)"/i.exec(head);
      return { data: body, name: name ? name[1] : 'a.wav', head };
    }
    pos = next;
  }
  return null;
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');

  if (req.method === 'GET' && (url.pathname === '/' || url.pathname === '/health')) {
    res.writeHead(200, { 'content-type': 'application/json' });
    return res.end(JSON.stringify({ ok: true, port: PORT, tts: true, asr: true }));
  }

  // ---- TTS：cc-connect 的 mimo provider 协议 ----
  // 它发 api-key 头（不是 Authorization: Bearer），而 语音网关只认 Bearer，
  // 所以这一步就是把 api-key 翻译成 Bearer 再转发（顺带复用 hub 的 TTS 缓存）。
  if (req.method === 'POST' && url.pathname.endsWith('/chat/completions')) {
    const t0 = Date.now();
    try {
      const raw = await readAll(req);
      const body = JSON.parse(raw.toString('utf8') || '{}');
      const model = String(body.model || '');
      if (!model.includes('tts')) throw new Error(`这个代理只管 tts，收到 model=${model}`);

      // MiMo 语义：role=assistant 才是要念的文本
      const msgs = Array.isArray(body.messages) ? body.messages : [];
      const speakText = msgs.filter((m) => m && m.role === 'assistant').map((m) => m.content).join(' ').trim();
      if (!speakText) throw new Error('messages 里没有 assistant 文本');

      const voice = (body.audio && body.audio.voice) || undefined;
      const wav = await gw.tts(speakText, { voice });
      const payload = {
        id: 'tts-' + Date.now().toString(36),
        object: 'chat.completion',
        created: Math.floor(Date.now() / 1000),
        model,
        choices: [{
          index: 0,
          finish_reason: 'stop',
          message: {
            role: 'assistant',
            content: '',
            audio: { data: wav.toString('base64'), format: 'wav', voice: voice || '冰糖' },
          },
        }],
        usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 },
      };
      log.info(`TTS 代理 ok ${speakText.length}字 → ${wav.length}B wav（${Date.now() - t0}ms）`);
      res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
      return res.end(JSON.stringify(payload));
    } catch (e) {
      log.warn(`TTS 代理失败：${e.message}`);
      res.writeHead(400, { 'content-type': 'application/json; charset=utf-8' });
      return res.end(JSON.stringify({ error: { message: e.message, type: 'invalid_request_error' } }));
    }
  }

  if (req.method !== 'POST' || !url.pathname.endsWith('/audio/transcriptions')) {
    res.writeHead(404, { 'content-type': 'application/json' });
    return res.end(JSON.stringify({ error: 'not found', path: url.pathname }));
  }

  const t0 = Date.now();
  try {
    const raw = await readAll(req);
    const ct = req.headers['content-type'] || '';
    let audio;
    if (ct.includes('multipart/form-data')) {
      const f = extractFile(raw, ct);
      if (!f || !f.data || !f.data.length) throw new Error('multipart 里没找到音频');
      audio = f.data;
      log.info(`收到 ${f.name} ${audio.length}B`);
    } else {
      audio = raw;
    }
    if (!audio.length) throw new Error('音频为空');

    const r = await gw.asr(audio);
    const fixed = fixTerms((r.text || '').trim());
    if (fixed.hits.length) log.info(`术语纠正：${fixed.hits.map((h) => `${h.from}→${h.to}`).join(', ')}`);
    const text = fixed.text;
    log.info(`转写 ok engine=${r.engine} ${audio.length}B → ${text.length}字（${Date.now() - t0}ms）`);
    res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
    return res.end(JSON.stringify({ text }));
  } catch (e) {
    log.warn(`转写失败：${e.message}`);
    res.writeHead(500, { 'content-type': 'application/json; charset=utf-8' });
    return res.end(JSON.stringify({ error: e.message }));
  }
});

server.listen(PORT, HOST, () => log.info(`voice-asr（OpenAI 兼容）监听 ${HOST}:${PORT} → MiMo ASR / 本地 whisper 回落`));

for (const sig of ['SIGTERM', 'SIGINT']) {
  process.on(sig, () => server.close(() => process.exit(0)));
}
