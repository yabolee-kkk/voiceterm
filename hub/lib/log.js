'use strict';
const T0 = Date.now();
function ts() {
  const d = new Date();
  return d.toTimeString().slice(0, 8) + '.' + String(Date.now() % 1000).padStart(3, '0');
}
const fmt = (args) => args.map(a => typeof a === 'string' ? a : JSON.stringify(a)).join(' ');
module.exports = {
  info: (...a) => console.log(`[${ts()}] INFO `, fmt(a)),
  warn: (...a) => console.warn(`[${ts()}] WARN `, fmt(a)),
  error: (...a) => console.error(`[${ts()}] ERROR`, fmt(a)),
  dbg: (...a) => { if (process.env.HUB_DEBUG) console.log(`[${ts()}] DEBUG`, fmt(a)); },
  elapsed: () => ((Date.now() - T0) / 1000).toFixed(2) + 's',
};
