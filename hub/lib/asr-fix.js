/**
 * ASR 术语纠正：语音转写会把专有名词听成同音词，直接交给 Agent 会误解任务目标。
 *
 * 实例（2026-10-22 真机）：用户说"ChainMem 这个项目"，MiMo 转成"依恋这个项目"，
 * codex 收到的是"依恋"，只能靠上下文猜——这种错最贵：猜错了就是白干一轮。
 *
 * 规则：只做白名单替换，且带排除条件，宁可不改也不乱改。
 */

// [错听写法, 正确写法, 排除的上下文（出现则不替换）]
const RULES = [
  ['依恋', 'ChainMem', ['理论', '关系', '类型', '依恋型', '情感']],
  ['依链', 'ChainMem', []],
  ['念链', 'ChainMem', []],
  ['纤链', 'ChainMem', []],
  ['扣的克斯', 'codex', []],
  ['科得克斯', 'codex', []],
  ['赫米斯', 'Hermes', []],
  ['海尔梅斯', 'Hermes', []],
  ['欧朋扣', 'opencode', []],
];

/** @param {string} text @returns {{text:string, hits:Array<{from:string,to:string}>}} */
function fixTerms(text) {
  if (!text || typeof text !== 'string') return { text: text || '', hits: [] };
  let out = text;
  const hits = [];
  for (const [wrong, right, excludes] of RULES) {
    if (!out.includes(wrong)) continue;
    let idx = 0;
    let changed = false;
    let result = '';
    while (idx < out.length) {
      const at = out.indexOf(wrong, idx);
      if (at < 0) {
        result += out.slice(idx);
        break;
      }
      const after = out.slice(at + wrong.length, at + wrong.length + 3);
      const blocked = excludes.some((ex) => after.startsWith(ex));
      result += out.slice(idx, at) + (blocked ? wrong : right);
      changed = changed || !blocked;
      idx = at + wrong.length;
    }
    if (changed) {
      out = result;
      hits.push({ from: wrong, to: right });
    }
  }
  return { text: out, hits };
}

module.exports = { fixTerms, RULES };
