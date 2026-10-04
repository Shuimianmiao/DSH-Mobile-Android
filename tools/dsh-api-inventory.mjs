// DSH 前端「跑不跑得动」取证器（不需要任何解析器依赖）。
//
// 用于定位：某台手机系统 WebView 是 Chrome 88，容器正常、页面加载完成，
// 但界面永远空白。要分清是**语法**问题（垫片救不了）还是**运行时 API** 问题
//（补进 webview-shim.js 即可），就看 served 的 bundle 里到底用了什么。
//
// 用法: node tools/dsh-api-inventory.mjs <抓下来的 bundle 目录>
//
// 输出：每个「比 Chrome 88 新的 API」的命中次数 + 若干处上下文；
//       外加一组「Chrome 88 之后的语法」的正则体检（无法 100% 代替解析器，
//       但每一条都打印上下文，能人工确认）。
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const dir = process.argv[2];
if (!dir || !existsSync(dir)) {
  console.error('用法: node tools/dsh-api-inventory.mjs <bundle 目录>');
  process.exit(1);
}

/** 比 Chrome 88 新的运行时 API。shimmed=true 表示 webview-shim.js 已经补了。 */
const APIS = [
  // —— 没垫的（嫌疑对象）——
  { re: /crypto\s*\.\s*randomUUID|\.\s*randomUUID\s*\(/g,        name: 'crypto.randomUUID',             chrome: 92,  shimmed: false },
  { re: /URL\s*\.\s*canParse|\.\s*canParse\s*\(/g,               name: 'URL.canParse',                  chrome: 120, shimmed: false },
  { re: /\.\s*bytes\s*\(\s*\)/g,                                 name: 'Response/Blob.bytes()',         chrome: 118, shimmed: false },
  { re: /URLPattern/g,                                           name: 'URLPattern',                    chrome: 95,  shimmed: false },
  { re: /selectRange\s*\(/g,                                     name: 'Intl.PluralRules.selectRange',  chrome: 106, shimmed: false },
  { re: /Symbol\s*\.\s*dispose|Symbol\s*\.\s*asyncDispose/g,     name: 'Symbol.dispose',                chrome: 134, shimmed: false },
  { re: /ReadableStream\s*\.\s*from/g,                           name: 'ReadableStream.from',           chrome: 124, shimmed: false },
  { re: /navigator\s*\.\s*userAgentData/g,                       name: 'navigator.userAgentData',       chrome: 90,  shimmed: false },
  { re: /\.\s*checkVisibility\s*\(/g,                            name: 'Element.checkVisibility',       chrome: 105, shimmed: false },
  { re: /Promise\s*\.\s*try\s*\(/g,                              name: 'Promise.try',                   chrome: 128, shimmed: false },
  { re: /Array\s*\.\s*prototype\s*\.\s*group|\.\s*group\s*\(\s*\(/g, name: 'Array.prototype.group',      chrome: 117, shimmed: false },
  { re: /Uint8Array\s*\.\s*fromBase64|\.\s*toBase64\s*\(/g,      name: 'Uint8Array base64 方法',         chrome: 140, shimmed: false },
  { re: /RegExp\s*\.\s*escape/g,                                 name: 'RegExp.escape',                 chrome: 136, shimmed: false },
  { re: /Map\s*\.\s*prototype\s*\.\s*getOrInsert|\.\s*getOrInsert\s*\(/g, name: 'Map.getOrInsert',       chrome: 0,   shimmed: false },
  { re: /\.\s*withResolvers\s*\(/g,                              name: '.withResolvers（任意对象）',       chrome: 119, shimmed: true  },
  { re: /\.\s*flatMap\s*\(/g,                                    name: 'Array.flatMap',                 chrome: 69,  shimmed: false },
  // —— 垫片已覆盖的（只为确认垫片够不够）——
  { re: /Object\s*\.\s*hasOwn/g,                                 name: 'Object.hasOwn',                 chrome: 93,  shimmed: true  },
  { re: /structuredClone/g,                                      name: 'structuredClone',               chrome: 98,  shimmed: true  },
  { re: /Promise\s*\.\s*withResolvers/g,                         name: 'Promise.withResolvers',         chrome: 119, shimmed: true  },
  { re: /\.\s*toSorted\s*\(|\.\s*toReversed\s*\(|\.\s*toSpliced\s*\(/g, name: 'Array 非破坏性方法',      chrome: 110, shimmed: true  },
  { re: /\.\s*findLast(Index)?\s*\(/g,                           name: 'findLast / findLastIndex',      chrome: 97,  shimmed: true  },
  { re: /Array\s*\.\s*fromAsync/g,                               name: 'Array.fromAsync',               chrome: 121, shimmed: true  },
  { re: /AbortSignal\s*\.\s*any/g,                               name: 'AbortSignal.any',               chrome: 116, shimmed: true  },
  { re: /AbortSignal\s*\.\s*timeout/g,                           name: 'AbortSignal.timeout',           chrome: 103, shimmed: true  },
  { re: /URL\s*\.\s*parse\s*\(/g,                                name: 'URL.parse',                     chrome: 126, shimmed: true  },
  { re: /Object\s*\.\s*groupBy|Map\s*\.\s*groupBy/g,             name: 'groupBy',                       chrome: 117, shimmed: true  },
  { re: /\.\s*union\s*\(|\.\s*isSubsetOf\s*\(|\.\s*isSupersetOf\s*\(/g, name: 'Set 集合运算',            chrome: 122, shimmed: true  },
  { re: /Iterator\s*\.\s*(from|prototype)/g,                     name: 'Iterator / Iterator helpers',   chrome: 122, shimmed: true  },
  { re: /\.\s*isWellFormed\s*\(|\.\s*toWellFormed\s*\(/g,       name: 'String well-formed',            chrome: 111, shimmed: true  },
  // —— Chrome 88 自带（确认基线）——
  { re: /Intl\s*\.\s*Segmenter/g,                                name: 'Intl.Segmenter',                chrome: 87,  shimmed: false },
  { re: /ResizeObserver/g,                                       name: 'ResizeObserver',                chrome: 64,  shimmed: false },
  { re: /AggregateError/g,                                       name: 'AggregateError',                chrome: 85,  shimmed: false },
  { re: /WeakRef|FinalizationRegistry/g,                         name: 'WeakRef / FinalizationRegistry',chrome: 84,  shimmed: false },
  { re: /queueMicrotask/g,                                       name: 'queueMicrotask',                chrome: 71,  shimmed: false },
  { re: /\.\s*replaceAll\s*\(/g,                                 name: 'String.replaceAll',             chrome: 85,  shimmed: false },
];

/** Chrome 88 之后的**语法**（正则体检，逐条打印上下文人工确认）。 */
const SYNTAX = [
  { re: /static\s*\{/g,                        name: 'class static block（Chrome 94）' },
  { re: /#[\w$]+\s+in\s+[\w$#]/g,              name: '#x in obj（Chrome 91）' },
  { re: /\/[^\n/]{1,120}\/[dgimsuvy]*d[dgimsuvy]*(?=[^\w$])/g, name: '正则 d flag（Chrome 90）' },
  { re: /\/[^\n/]{1,120}\/v[v]*\b/g,           name: '正则 v flag（Chrome 112）' },
  { re: /\busing\s+[\w$]+\s*=/g,               name: 'using 声明（Chrome 134）' },
  { re: /import\s*\([^)]*,\s*\{\s*(with|assert)\s*:/g, name: 'import attributes（Chrome 123）' },
  { re: /\?\.\s*\[|\?\?=/g,                    name: '可选链 / 逻辑赋值（Chrome 80~85，88 支持）' },
];

function contexts(code, re, n = 3, span = 90) {
  const out = [];
  re.lastIndex = 0;
  let m;
  while ((m = re.exec(code)) !== null && out.length < n) {
    const s = Math.max(0, m.index - span);
    const e = Math.min(code.length, m.index + m[0].length + span);
    out.push(code.slice(s, e).replace(/\s+/g, ' '));
    if (m[0].length === 0) re.lastIndex++;
  }
  return out;
}

const files = readdirSync(dir).filter((f) => f.endsWith('.js'));
for (const f of files) {
  const code = readFileSync(join(dir, f), 'utf8');
  console.log(`\n══════════ ${f}（${(code.length / 1024).toFixed(0)} KB）══════════`);

  console.log('── 语法体检（Chrome 88 之后）──');
  let syntaxHit = false;
  for (const s of SYNTAX) {
    const n = (code.match(s.re) || []).length;
    if (!n) continue;
    syntaxHit = true;
    console.log(`  ⚠️ ${s.name} × ${n}`);
    for (const c of contexts(code, s.re, 2)) console.log(`       …${c}…`);
  }
  if (!syntaxHit) console.log('  ✅ 没有发现 Chrome 88 之后的语法特征');

  console.log('── API 盘点（只看未垫 + 命中）──');
  const rows = [];
  for (const a of APIS) {
    const n = (code.match(a.re) || []).length;
    if (!n) continue;
    rows.push({ ...a, n });
  }
  rows.sort((x, y) => (x.shimmed === y.shimmed ? y.chrome - x.chrome : (x.shimmed ? 1 : -1)));
  for (const r of rows) {
    const tag = r.shimmed ? '已垫  ' : (r.chrome > 88 ? '⚠️未垫' : '88自带');
    console.log(`  ${tag} ${r.name.padEnd(30)} × ${String(r.n).padStart(4)}  (Chrome ${r.chrome}+)`);
    if (!r.shimmed && r.chrome > 88) {
      for (const c of contexts(code, r.re, 2)) console.log(`       …${c}…`);
    }
  }
}
