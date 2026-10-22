#!/usr/bin/env node
'use strict';
// 自检：对 voice-hub 发一条消息，把 SSE 事件流打出来。
// 用法: node tools/selftest.js [agent] [text]
const http = require('http');
const fs = require('fs'), path = require('path'), os = require('os');

const cfg = JSON.parse(JSON.stringify({
  port: Number(process.env.HUB_PORT || 8099),
  host: process.env.HUB_HOST || '127.0.0.1',
}));
let TOKEN;
try {
  TOKEN = fs.readFileSync(path.join(os.homedir(), '.config/voice-hub/api-token'), 'utf8').trim();
} catch (e) {
  console.error('缺少 ~/.config/voice-hub/api-token');
  process.exit(2);
}

const agent = process.argv[2] || 'codex';
const text = process.argv[3] || '用一句话自我介绍';
const t0 = Date.now();
const el = () => ((Date.now() - t0) / 1000).toFixed(2) + 's';

function post(p, body) {
  return new Promise((resolve, reject) => {
    const data = JSON.stringify(body);
    const req = http.request({
      host: cfg.host, port: cfg.port, path: p, method: 'POST',
      headers: { 'content-type': 'application/json', authorization: 'Bearer ' + TOKEN,
                 'content-length': Buffer.byteLength(data) },
    }, (res) => {
      let b = '';
      res.on('data', (c) => (b += c));
      res.on('end', () => resolve({ code: res.statusCode, body: b }));
    });
    req.on('error', reject);
    req.write(data);
    req.end();
  });
}

function subscribe(agent, onEvent) {
  const req = http.request({
    host: cfg.host, port: cfg.port, path: `/v1/sessions/${agent}/events?token=${TOKEN}`,
    headers: { accept: 'text/event-stream' },
  }, (res) => {
    if (res.statusCode !== 200) { console.error('SSE 连接失败', res.statusCode); process.exit(3); }
    let buf = '';
    res.on('data', (chunk) => {
      buf += chunk.toString('utf8');
      let idx;
      while ((idx = buf.indexOf('\n\n')) >= 0) {
        const block = buf.slice(0, idx);
        buf = buf.slice(idx + 2);
        let ev = 'message', data = '';
        for (const line of block.split('\n')) {
          if (line.startsWith('event:')) ev = line.slice(6).trim();
          else if (line.startsWith('data:')) data += line.slice(5).trim();
        }
        if (data) onEvent(ev, data);
      }
    });
  });
  req.end();
  return req;
}

(async () => {
  console.log(`[${el()}] 连 SSE（agent=${agent}）`);
  let done = false;
  const sse = subscribe(agent, (ev, data) => {
    let d; try { d = JSON.parse(data); } catch { d = { raw: data }; }
    const brief = d.text ? d.text.slice(0, 60).replace(/\n/g, '⏎') : (d.state || d.kind || '');
    console.log(`[${el()}] ← ${ev}  ${brief}`);
    if (ev === 'done') {
      done = true;
      console.log(`\n=== 最终回复（${d.text.length} 字）===`);
      console.log(d.text);
      console.log(`\n=== 可朗读版本 ===`);
      console.log(d.speech.slice(0, 300));
      sse.destroy();
      process.exit(0);
    }
  });

  await new Promise((r) => setTimeout(r, 400));
  const r = await post(`/v1/sessions/${agent}/message`, { text });
  console.log(`[${el()}] POST message -> ${r.code} ${r.body}`);
  if (r.code >= 300) process.exit(0);

  setTimeout(() => { if (!done) { console.log('\n(60s 超时，未收到 done)'); process.exit(4); } }, 60000).unref();
})();
