'use strict';
// Agent 池 = 一条 bridge 连接 + 若干 project 路由。
const { EventEmitter } = require('events');
const { BridgeLink } = require('./cc-bridge');
const cfg = require('../lib/config');
const log = require('../lib/log');

class AgentHub extends EventEmitter {
  constructor() {
    super();
    this.link = null;
    this.upstreams = [];
    this.agentUpstream = new Map();
    this.agents = new Map();   // agentId -> {sent, received, lastError, lastAt}
  }

  start() {
    this.localIds = new Set((cfg.localAgents || []).map((a) => a.id));
    // 每条上游（cc-connect 实例）对应一个 project 白名单；空 = 除已认领的以外都归它
    this.upstreams = (cfg.bridges || []).map((b) => ({
      name: b.name, url: b.url, tokenFile: b.tokenFile, agents: (b.agents || []).slice(), link: null,
    }));
    const claimed = new Set();
    for (const u of this.upstreams) for (const id of u.agents) claimed.add(id);

    for (const id of cfg.agents) {
      const u = this.upstreams.find((x) => x.agents.includes(id))
        || this.upstreams.find((x) => !x.agents.length);      // 没认领别人 project 的那条
      this.agents.set(id, {
        sent: 0, received: 0, lastError: null, lastAt: null,
        local: this.localIds.has(id), upstream: u ? u.name : null,
      });
      this.agentUpstream = this.agentUpstream || new Map();
      this.agentUpstream.set(id, u || null);
    }

    for (const u of this.upstreams) {
      let token;
      try { token = cfg.readSecret(u.tokenFile); } catch (e) { log.warn(`上游 ${u.name} 跳过：${e.message}`); continue; }
      u.link = new BridgeLink({ url: u.url, token });
      u.link.on('ready', () => {
        const mine = [...this.agents.keys()].filter((id) => (this.agentUpstream.get(id) || {}).name === u.name);
        log.info(`bridge[${u.name}] 就绪，可路由：${mine.join(', ') || '（无）'}`);
        this.emit('ready', u.name);
      });
      u.link.on('frame', (f) => this.emit('frame', f));
      u.link.on('upstream_error', (f) => this.emit('upstream_error', f));
      u.link.connect();
    }
    return this.upstreams[0] && this.upstreams[0].link;
  }

  onFrame(fn) { this.on('frame', fn); }
  onUpstreamError(fn) { this.on('upstream_error', fn); }
  onReady(fn) { this.on('ready', fn); }

  has(agentId) { return this.agents.has(agentId); }

  require(agentId) {
    if (!this.agents.has(agentId)) {
      const e = new Error(`未知 Agent: ${agentId}（已配置：${[...this.agents.keys()].join(', ')}）`);
      e.code = 'UNKNOWN_AGENT';
      throw e;
    }
    return this.agents.get(agentId);
  }

  isLocal(agentId) { return this.localIds.has(agentId); }

  send(agentId, frame) {
    this.require(agentId);
    if (this.isLocal(agentId)) {
      throw Object.assign(new Error(`${agentId} 是本地 CLI Agent，不走 bridge`), { code: 'LOCAL_AGENT' });
    }
    const u = this.agentUpstream.get(agentId);
    if (!u || !u.link) throw Object.assign(new Error(`${agentId} 的上游 bridge 未连接`), { code: 'NOT_READY' });
    u.link.send(frame);
    const st = this.agents.get(agentId);
    st.sent++; st.lastAt = Date.now();
  }

  markReceived(agentId) {
    const st = this.agents.get(agentId);
    if (st) { st.received++; st.lastAt = Date.now(); }
  }

  status() {
    return [...this.agents.entries()].map(([id, st]) => {
      const u = this.agentUpstream.get(id);
      return { agentId: id, ready: !!(u && u.link && u.link.ready), ...st };
    });
  }

  bridgeStatus() {
    return this.upstreams.map((u) => ({
      name: u.name, url: u.url,
      ready: !!(u.link && u.link.ready), stats: u.link ? u.link.stats : null,
    }));
  }

  stop() { for (const u of this.upstreams || []) u.link && u.link.stop(); }
}

module.exports = { AgentHub };
