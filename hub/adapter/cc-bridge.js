'use strict';
// 单条 Bridge 连接（platform = "phone"）。
//
// 实测得出的三条硬约束（2026-10-21，读 core/bridge.go 并实跑验证）：
//   ① adapters 以 platform 名为键，同名连接会被【踢掉】→ 只能有一条连接，
//      多 Agent 靠 message.project 路由（resolveEngine 按 project 匹配）
//   ② register 必须带 project 或 message 每帧带 project，否则静默丢弃（无任何回包）
//   ③ preview_start 不回 preview_ack 就只剩最终 reply，拿不到增量
//
// 帧形态因 Agent 而异：codex 只给一次性快照；claudecode 会给 update_message 增量 + buttons

const { EventEmitter } = require('events');
const log = require('../lib/log');
const P = require('./protocol');

const RECONNECT_MIN = 1000;
const RECONNECT_MAX = 30000;
const PING_EVERY = 30000;

class BridgeLink extends EventEmitter {
  constructor({ url, token }) {
    super();
    this.url = url;
    this.token = token;
    this.ws = null;
    this.ready = false;
    this.backoff = RECONNECT_MIN;
    this.pingTimer = null;
    this.retryTimer = null;
    this.stopped = false;
    this.seq = 0;
    this.stats = { sent: 0, received: 0, reconnects: 0, lastError: null, connectedAt: null };
  }

  connect() {
    if (this.stopped) return;
    const url = `${this.url}?token=${encodeURIComponent(this.token)}`;
    log.info(`连接 bridge ${this.url}`);
    let ws;
    try { ws = new WebSocket(url); }
    catch (e) { return this._scheduleReconnect(e.message); }
    this.ws = ws;

    ws.addEventListener('open', () => {
      log.info('WS 已连，发送 register(platform=phone)');
      this._send(P.buildRegister());
    });

    ws.addEventListener('message', (ev) => {
      let f;
      try { f = JSON.parse(ev.data); } catch { return log.warn('非 JSON 帧'); }
      this._handle(f);
    });

    ws.addEventListener('error', (e) => { this.stats.lastError = e.message || 'ws error'; });

    ws.addEventListener('close', (ev) => {
      this.ready = false;
      this._clearPing();
      if (this.stopped) return;
      log.warn(`WS 关闭 code=${ev.code}，准备重连`);
      this._scheduleReconnect(`close ${ev.code}`);
    });
  }

  _handle(f) {
    this.stats.received++;
    switch (f.type) {
      case P.OUT.REGISTER_ACK:
        if (f.ok === false) {
          log.error('register 被拒:', f.error);
          this.stats.lastError = f.error || 'register rejected';
          return;
        }
        this.ready = true;
        this.backoff = RECONNECT_MIN;
        this.stats.connectedAt = Date.now();
        log.info('register_ack ok，连接就绪');
        this._startPing();
        this.emit('ready');
        break;

      case P.OUT.PREVIEW_START:
        this._send(P.buildPreviewAck(f.ref_id));   // 不回就没有增量
        this.emit('frame', this._wrap(f));
        break;

      case P.OUT.PONG:
        break;

      case P.OUT.ERROR:
        log.warn('上游 error:', f);
        this.emit('upstream_error', f);
        break;

      case P.OUT.TYPING_START:
      case P.OUT.TYPING_STOP:
      case P.OUT.REPLY:
      case P.OUT.REPLY_STREAM:
      case P.OUT.UPDATE_MESSAGE:
      case P.OUT.DELETE_MESSAGE:
      case P.OUT.CARD:
      case P.OUT.BUTTONS:
      case P.OUT.AUDIO:
      case P.OUT.IMAGE:
      case P.OUT.FILE:
        this.emit('frame', this._wrap(f));
        break;

      default:
        log.dbg(`未知帧 ${f.type}`);
    }
  }

  _wrap(f) {
    const sk = f.session_key || null;
    const parsed = sk ? P.parseSessionKey(sk) : {};
    return {
      type: f.type,
      sessionKey: sk,
      agentId: parsed.agentId || null,
      sessionName: parsed.name || null,
      replyCtx: f.reply_ctx || null,
      content: f.content || f.delta || f.full_text || null,
      raw: f,
      at: Date.now(),
    };
  }

  send(obj) {
    if (!this.ready) { const e = new Error('bridge 未就绪'); e.code = 'NOT_READY'; throw e; }
    if (!this._send(obj)) { const e = new Error('bridge 发送失败'); e.code = 'SEND_FAIL'; throw e; }
    this.stats.sent++;
    return true;
  }

  _send(obj) {
    if (!this.ws || this.ws.readyState !== 1) return false;
    try { this.ws.send(JSON.stringify(obj)); return true; }
    catch (e) { log.warn('发送异常', e.message); return false; }
  }

  _startPing() {
    this._clearPing();
    this.pingTimer = setInterval(() => { this._send({ type: P.IN.PING }); }, PING_EVERY);
    if (this.pingTimer.unref) this.pingTimer.unref();
  }

  _clearPing() {
    if (this.pingTimer) { clearInterval(this.pingTimer); this.pingTimer = null; }
  }

  _scheduleReconnect(reason) {
    if (this.stopped || this.retryTimer) return;
    const wait = this.backoff;
    this.backoff = Math.min(this.backoff * 2, RECONNECT_MAX);
    this.stats.reconnects++;
    log.warn(`${wait}ms 后重连（${reason}）`);
    this.retryTimer = setTimeout(() => { this.retryTimer = null; this.connect(); }, wait);
  }

  stop() {
    this.stopped = true;
    this._clearPing();
    if (this.retryTimer) { clearTimeout(this.retryTimer); this.retryTimer = null; }
    try { this.ws && this.ws.close(); } catch {}
  }
}

module.exports = { BridgeLink };
