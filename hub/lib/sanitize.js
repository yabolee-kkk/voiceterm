'use strict';
// 把 Agent 回复清洗成"能念"和"能显示"两种形态。
// 教训来源（旧项目）：不过清洗直接念，会把 ** 和换行念出来。

// cc-connect 会在回复末尾附一行/两行运行时信息，例如：
//   *deepseek-flash · effort:high · out 37 · in 27.4k cw 0 cr 10.1k · ctx 3%
//   ~/fast-cc*
// 逐行判断比正则整段匹配稳（尾部可能多出一行 cwd）。
function stripFooter(text) {
  const s = String(text == null ? '' : text);
  const lines = s.split('\n');
  let last = lines.length - 1;
  while (last >= 0 && lines[last].trim() === '') last--;
  const from = Math.max(0, last - 2);
  for (let i = from; i <= last; i++) {
    if (/^\s*\*[^*]*·/.test(lines[i])) {
      return lines.slice(0, i).join('\n').trimEnd();
    }
  }
  return s.trimEnd();
}

function speakable(text) {
  let s = stripFooter(text);
  s = s.replace(/```[\s\S]*?```/g, '（代码块）');       // 围栏代码
  s = s.replace(/`([^`]*)`/g, '$1');                    // 行内代码
  s = s.replace(/!\[[^\]]*\]\([^)]*\)/g, '');         // 图片
  s = s.replace(/\[([^\]]*)\]\([^)]*\)/g, '$1');      // 链接
  s = s.replace(/^\s{0,3}#{1,6}\s*/gm, '');             // 标题井号
  s = s.replace(/^\s{0,3}[-*+]\s+/gm, '');              // 列表符号
  s = s.replace(/^\s{0,3}>\s?/gm, '');                  // 引用
  s = s.replace(/\*\*([^*]+)\*\*/g, '$1');            // 加粗
  s = s.replace(/(^|[^*])\*([^*\n]+)\*/g, '$1$2');      // 斜体
  s = s.replace(/_{1,2}([^_]+)_{1,2}/g, '$1');
  s = s.replace(/^\s*\|.*\|\s*$/gm, '（表格）');        // 表格
  s = s.replace(/^\s*[-—=]{3,}\s*$/gm, '');             // 分隔线
  s = s.replace(/\[([^\]]+)\]/g, '$1');                 // 残留方括号
  s = s.replace(/（表格）(\s*（表格）)+/g, '（表格）');   // 连续表格行只念一次
  s = s.replace(/\n{2,}/g, '\n');
  s = s.replace(/[ \t]+/g, ' ');
  return s.trim();
}

module.exports = { stripFooter, speakable };
