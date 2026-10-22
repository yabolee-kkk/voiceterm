#!/usr/bin/env node
'use strict';
// 语音消息探针：订阅 SSE → POST /v1/sessions/{agent}/voice → 打印所有事件
// 用法: node tools/voice-probe.js [agent] [wav路径] [监听秒数]
const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');

const TOKEN = fs.readFileSync(path.join(os.homedir(), '.config/voice-hub/api-token'), 'utf8').trim();
const agent = process.argv[2] || 'codex';
const wavPath = process.argv[3] || '/tmp/m4-hub-tts.wav';
const secs = Number(process.argv[4] || 40);
const t0 = Date.now();
const el = () => ((Date.now() - t0) / 1000).toFixed(2) + 's';

// 1) 订阅
const sse = http.request({
  host: '127.0.0.1', port: 8099, path: `/v1/sessions/${agent}/events?token=${TOKEN}`,
  headers: { accept: 'text/event-stream' },
}, (res) => {
  let buf = '';
  res.on('data', (c) => {
    buf += c.toString('utf8');
    let i;
    while ((i = buf.indexOf('\n\n')) >= 0) {
      const block = buf.slice(0, i); buf = buf.slice(i + 2);
      let ev = 'message', data = '';
      for (const line of block.split('\n')) {
        if (line.startsWith('event:')) ev = line.slice(6).trim();
        else if (line.startsWith('data:')) data += line.slice(5).trim();
      }
      if (!data || ev === 'message') continue;
      let d = {}; try { d = JSON.parse(data); } catch {}
      let brief = '';
      if (d.text) brief = JSON.stringify(String(d.text).slice(0, 60));
      if (d.kind) brief += ` kind=${d.kind}`;
      if (d.state) brief += ` state=${d.state}`;
      if (d.type === 'audio' || d.kind === 'audio' || ev === 'attachment') {
        brief += ` payload.keys=${Object.keys(d.payload || {}).join(',')}`;
        if (d.payload && d.payload.data) brief += ` dataLen=${String(d.payload.data).length}`;
        if (d.payload && d.payload.format) brief += ` fmt=${d.payload.format}`;
      }
      console.log(`${el()} [${ev}] ${brief}`);
    }
  });
});
sse.end();

// 2) 1 秒后发语音
setTimeout(() => {
  const wav = fs.readFileSync(wavPath);
  const req = http.request({
    host: '127.0.0.1', port: 8099, method: 'POST',
    path: `/v1/sessions/${agent}/voice?durationMs=6600`,
    headers: {
      authorization: 'Bearer ' + TOKEN, 'content-type': 'audio/wav',
      'content-length': wav.length,
    },
  }, (res) => {
    let b = '';
    res.on('data', (c) => (b += c));
    res.on('end', () => console.log(`${el()} POST /voice → ${res.statusCode} ${b}`));
  });
  req.on('error', (e) => console.log('POST 失败', e.message));
  req.write(wav); req.end();
}, 1000);

setTimeout(() => { console.log('--- 结束 ---'); process.exit(0); }, secs * 1000);
