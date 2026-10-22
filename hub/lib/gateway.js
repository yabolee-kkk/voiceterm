'use strict';
// 语音能力：TTS / ASR 统一走一个 OpenAI 兼容的语音网关（本机或局域网）。
//
// 为什么放服务端而不是手机里：
//   ① 手机本来就离不开局域网（没有 hub 就没有 Agent），"离线 ASR" 这个好处是空的
//   ② 手机上跑 SenseVoice 要带 ~250MB 模型 + JNI，构建与发热都吃亏
//   ③ Key 只留在 .18 这台机器（~/.config/voice-gateway/api-key），手机永远拿不到
//
// 实测接口形态（2026-10-22 走网关验证通过，合成→转写回原文一字不差）：
//   TTS  POST /v1/chat/completions   model=mimo-v2.5-tts
//        messages: [{role:user, content:风格指令}, {role:assistant, content:要念的文本}]
//        audio: {format:'wav'|'pcm16', voice:'冰糖'}  → choices[0].message.audio.data = base64
//   ASR  POST /v1/chat/completions   model=mimo-v2.5-asr
//        messages: [{role:user, content:[{type:'input_audio',input_audio:{data:base64,format:'wav'}}]}]
//        → choices[0].message.content = 转写文本
//   注意：/v1/audio/transcriptions 在网关上不存在（404），别照 OpenAI 的路径猜

const fs = require('fs');
const os = require('os');
const path = require('path');
const log = require('./log');

const GW_URL = process.env.VG_URL || 'http://127.0.0.1:8080';
const KEY_PATH = process.env.VG_KEY_PATH || path.join(os.homedir(), '.config/voice-gateway/api-key');

let cachedKey = null;
function key() {
  if (cachedKey) return cachedKey;
  try {
    cachedKey = fs.readFileSync(KEY_PATH, 'utf8').trim();
  } catch (e) {
    throw new Error(`读不到网关 Key（${KEY_PATH}）：${e.message}`);
  }
  if (!cachedKey) throw new Error('网关 Key 为空');
  return cachedKey;
}

async function callGateway(payload, timeoutMs) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeoutMs);
  try {
    const r = await fetch(`${GW_URL}/v1/chat/completions`, {
      method: 'POST',
      headers: { authorization: `Bearer ${key()}`, 'content-type': 'application/json' },
      body: JSON.stringify(payload),
      signal: ctl.signal,
    });
    const text = await r.text();
    if (!r.ok) throw new Error(`网关 ${r.status}: ${text.slice(0, 200)}`);
    return JSON.parse(text);
  } finally {
    clearTimeout(timer);
  }
}

// TTS 结果按 (文本,音色) 缓存，避免同一条反复请求（限流 RPM 100，别浪费）
const cache = new Map();
const CACHE_MAX = 40;

/**
 * 文本 → wav Buffer
 * @param {string} text  要念的文本（调用方负责先过 speakable 清洗）
 * @param {{voice?:string, style?:string}} opt
 */
async function tts(text, opt = {}) {
  const voice = opt.voice || '冰糖';
  const style = opt.style || '语气自然，语速稍快，像在汇报';
  const clean = String(text || '').trim();
  if (!clean) throw new Error('text 为空');

  const ck = `${voice}\u0000${clean}`;
  if (cache.has(ck)) return cache.get(ck);

  const started = Date.now();
  const d = await callGateway({
    model: 'mimo-v2.5-tts',
    messages: [
      { role: 'user', content: style },
      { role: 'assistant', content: clean },
    ],
    audio: { format: 'wav', voice },
    stream: false,
  }, 30000);

  const b64 = d?.choices?.[0]?.message?.audio?.data;
  if (!b64) throw new Error('网关没给 audio.data');
  const buf = Buffer.from(b64, 'base64');
  log.info(`TTS ok ${clean.length}字 → ${buf.length}B wav（${Date.now() - started}ms，音色 ${voice}）`);

  if (cache.size >= CACHE_MAX) cache.delete(cache.keys().next().value);
  cache.set(ck, buf);
  return buf;
}

/**
 * wav Buffer → 文本
 * 主用 MiMo（中文更准），失败自动回落本机 faster-whisper（离线可用）。
 * 实测 MiMo ASR 会出 429/503（upstream_unavailable），没有回落就等于没有 ASR。
 * @param {Buffer} wav
 */
async function asr(wav) {
  if (!wav || !wav.length) throw new Error('音频为空');
  try {
    const text = await mimoAsr(wav);
    return { text, engine: 'mimo' };
  } catch (e) {
    log.warn(`MiMo ASR 失败（${e.message}），回落本地 whisper`);
    const text = await localAsr(wav);
    return { text, engine: 'local-whisper' };
  }
}

async function mimoAsr(wav) {
  const started = Date.now();
  const d = await callGateway({
    model: 'mimo-v2.5-asr',
    messages: [{
      role: 'user',
      content: [{ type: 'input_audio', input_audio: { data: wav.toString('base64'), format: 'wav' } }],
    }],
  }, 60000);

  const text = d?.choices?.[0]?.message?.content;
  if (typeof text !== 'string') throw new Error('网关没给转写文本');
  log.info(`ASR(MiMo) ok ${wav.length}B → ${text.length}字（${Date.now() - started}ms）`);
  return text.trim();
}

const LOCAL_ASR_URL = process.env.VG_LOCAL_ASR || 'http://127.0.0.1:19980/v1/audio/transcriptions';

/**
 * 流式 TTS：边收 MiMo 的 PCM chunk 边回调，给"边收边播"用。
 * 实测：首包 0.53s，chunk 形态是 choices[0].delta.audio.data（不是 message.audio）。
 * @param {string} text
 * @param {{voice?:string, style?:string}} opt
 * @param {(pcm:Buffer)=>void|Promise<void>} onChunk
 * @returns {Promise<number>} 总字节数
 */
async function ttsStream(text, opt = {}, onChunk) {
  const voice = opt.voice || '冰糖';
  const style = opt.style || '语气自然，语速稍快，像在汇报';
  const clean = String(text || '').trim();
  if (!clean) throw new Error('text 为空');

  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), 120000);
  let sent = 0;
  const started = Date.now();
  let firstAt = null;
  try {
    const r = await fetch(`${GW_URL}/v1/chat/completions`, {
      method: 'POST',
      headers: { authorization: `Bearer ${key()}`, 'content-type': 'application/json' },
      body: JSON.stringify({
        model: 'mimo-v2.5-tts',
        messages: [
          { role: 'user', content: style },
          { role: 'assistant', content: clean },
        ],
        audio: { format: 'pcm16', voice },
        stream: true,
      }),
      signal: ctl.signal,
    });
    if (!r.ok) {
      const t = await r.text();
      throw new Error(`网关 ${r.status}: ${t.slice(0, 160)}`);
    }

    let buf = '';
    for await (const chunk of r.body) {
      buf += Buffer.from(chunk).toString('utf8');
      let i;
      while ((i = buf.indexOf('\n\n')) >= 0) {
        const block = buf.slice(0, i);
        buf = buf.slice(i + 2);
        for (const line of block.split('\n')) {
          if (!line.startsWith('data:')) continue;
          const body = line.slice(5).trim();
          if (!body || body === '[DONE]') continue;
          let d;
          try { d = JSON.parse(body); } catch { continue; }
          const delta = ((d.choices || [])[0] || {}).delta || {};
          const b64 = delta.audio && delta.audio.data;
          if (!b64) continue;
          const pcm = Buffer.from(b64, 'base64');
          if (!pcm.length) continue;
          if (firstAt === null) firstAt = Date.now() - started;
          sent += pcm.length;
          await onChunk(pcm);
        }
      }
    }
    log.info(`TTS 流式 ok ${clean.length}字 → ${sent}B PCM 首包 ${firstAt ?? -1}ms 总 ${Date.now() - started}ms`);
    return sent;
  } finally {
    clearTimeout(timer);
  }
}

async function localAsr(wav) {
  const started = Date.now();
  const form = new FormData();
  form.append('file', new Blob([wav], { type: 'audio/wav' }), 'a.wav');
  form.append('model', 'whisper-1');

  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), 120000);
  try {
    const r = await fetch(LOCAL_ASR_URL, { method: 'POST', body: form, signal: ctl.signal });
    const text = await r.text();
    if (!r.ok) throw new Error(`本地 ASR ${r.status}: ${text.slice(0, 160)}`);
    const d = JSON.parse(text);
    log.info(`ASR(本地) ok ${wav.length}B → ${(d.text || '').length}字（${Date.now() - started}ms）`);
    return String(d.text || '').trim();
  } finally {
    clearTimeout(timer);
  }
}

module.exports = { tts, ttsStream, asr, GW_URL, LOCAL_ASR_URL };
