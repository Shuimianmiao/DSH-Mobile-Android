// class static block（Chrome 94+）→ 静态字段初始化（Chrome 72+）的等价改写。
//
// 背景
// ----
// 用户反馈：HONOR KOZ-AL00 / Android 10 / 系统 WebView **Chrome 88**，
// 容器一切正常、页面加载完成，界面却永远空白。抓下 served 的 bundle 分析后确认：
// 唯一的语法障碍是 `index.js` 里的 **class static block**（`static{…}`，Chrome 94+）——
// Chrome 88 在**解析阶段**就抛 SyntaxError，整个主 bundle 直接作废，
// 所以一个字符都渲染不出来。这种问题垫片（webview-shim.js）救不了：
// 它补的是运行时缺失的全局对象，而这是语法。
//
// 改写规则
// --------
//   class A { static { BODY } }
//     ↓ 等价于
//   class A { static __dshSb0 = (() => { BODY })() }
//
// 为什么等价：
//   · 静态字段初始化器（Chrome 72 起支持）与 static block 一样，在**类定义时**求值一次；
//   · 初始化器里的 `this` 就是类本身，箭头函数按词法捕获它 → static block 里
//     `this` 的语义保持不变；
//   · static block 里不允许 `return` / `await` / `yield`，箭头函数体里同样不能用
//     （这几个关键字在两种形态下都是语法错误），所以不存在"改写后行为变了"的情况；
//   · 直接引用类名（minified 名如 `_4`）或闭包变量（如 `io`）都照旧有效。
//
// 安全性
// ------
// `static` 后面直接跟 `{` 在合法 JS 里**只可能**是 static block（`static{` 出现在别处都是语法错误），
// 所以定位起点很稳。剩下的风险只在"数花括号"时被字符串/注释/模板串/正则里的花括号骗到 ——
// 所以扫描器把这几种上下文都跳过；万一仍有歧义，宁可**整份不改**（返回 null），
// 让上层退回"原样加载"，绝不出手破坏 bundle。
//
// 用法:
//   node tools/rewrite-static-block.mjs <输入.js> [输出.js]     # 命令行：改写并校验
//   或 import { rewriteStaticBlocks } from './rewrite-static-block.mjs'
import { readFileSync, writeFileSync } from 'node:fs';
import { Script } from 'node:vm';

/** 快速判断有没有必要改写（省掉整份扫描）。 */
export function looksLikeItNeedsRewrite(js) {
  return typeof js === 'string' && /(^|[^\w$])static\s*\{/.test(js);
}

/**
 * 把 class static block 改写成等价的静态字段初始化。
 *
 * @returns {{code: string, count: number} | null} null = 有歧义，宁可不动
 */
export function rewriteStaticBlocks(js) {
  if (typeof js !== 'string' || !looksLikeItNeedsRewrite(js)) {
    return { code: js, count: 0 };
  }

  const n = js.length;
  const out = [];
  let i = 0;
  let copied = 0;
  let count = 0;
  let prevSignificant = '';       // 用于区分正则字面量 / 除号

  const isIdentChar = (c) => /[A-Za-z0-9_$]/.test(c);
  const KEYWORDS_BEFORE_REGEX = new Set([
    'return', 'typeof', 'instanceof', 'in', 'of', 'new', 'delete', 'void', 'case',
    'do', 'else', 'yield', 'await', 'throw',
  ]);

  /** 从下标 p（指向 '/'）开始是不是正则字面量。 */
  const regexAllowed = (p) => {
    const c = prevSignificant;
    if (c === '') return true;
    if (isIdentChar(c)) return KEYWORDS_BEFORE_REGEX.has(c);
    return !(c === ')' || c === ']' || c === '}');
  };

  while (i < n) {
    const ch = js[i];

    // 行注释
    if (ch === '/' && js[i + 1] === '/') {
      const e = js.indexOf('\n', i);
      i = e === -1 ? n : e;
      continue;
    }
    // 块注释
    if (ch === '/' && js[i + 1] === '*') {
      const e = js.indexOf('*/', i + 2);
      i = e === -1 ? n : e + 2;
      continue;
    }
    // 正则字面量
    if (ch === '/' && regexAllowed(i)) {
      let j = i + 1;
      let inClass = false;
      let closed = false;
      while (j < n) {
        const c = js[j];
        if (c === '\\') { j += 2; continue; }
        if (c === '\n') break;                       // 正则不能跨行 → 说明不是正则
        if (inClass) { if (c === ']') inClass = false; }
        else if (c === '[') inClass = true;
        else if (c === '/') { closed = true; break; }
        j++;
      }
      if (closed) {
        j++;
        while (j < n && /[a-z]/i.test(js[j])) j++;   // flags
        i = j;
        prevSignificant = '/';
        continue;
      }
      // 不是正则 → 当除号处理
      prevSignificant = ch;
      i++;
      continue;
    }
    // 字符串 / 模板串
    if (ch === '"' || ch === "'" || ch === '`') {
      const quote = ch;
      let j = i + 1;
      while (j < n) {
        const c = js[j];
        if (c === '\\') { j += 2; continue; }
        if (c === quote) { j++; break; }
        if (quote === '`' && c === '$' && js[j + 1] === '{') {
          // 模板串里的 ${…}：按花括号配对跳过（内部可能有任意 JS）
          let depth = 1;
          j += 2;
          while (j < n && depth > 0) {
            const d = js[j];
            if (d === '\\') { j += 2; continue; }
            if (d === '{') depth++;
            else if (d === '}') depth--;
            else if (d === '"' || d === "'" || d === '`') {
              const q2 = d;
              j++;
              while (j < n) {
                if (js[j] === '\\') { j += 2; continue; }
                if (js[j] === q2) break;
                j++;
              }
            }
            j++;
          }
          continue;
        }
        j++;
      }
      i = j;
      prevSignificant = quote;
      continue;
    }

    // static block
    if (ch === 's' && js.startsWith('static', i)) {
      const before = i === 0 ? '' : js[i - 1];
      const after = js[i + 6];
      const wordStart = !isIdentChar(before);
      const wordEnd = !after || !isIdentChar(after);
      if (wordStart && wordEnd) {
        // 跳过 static 与 '{' 之间的空白 / 注释
        let k = i + 6;
        for (;;) {
          const c = js[k];
          if (c === ' ' || c === '\t' || c === '\r' || c === '\n') { k++; continue; }
          if (c === '/' && js[k + 1] === '/') { const e = js.indexOf('\n', k); k = e === -1 ? n : e; continue; }
          if (c === '/' && js[k + 1] === '*') { const e = js.indexOf('*/', k + 2); k = e === -1 ? n : e + 2; continue; }
          break;
        }
        if (js[k] === '{') {
          const bodyStart = k + 1;
          // 数花括号找配对的 '}'（同样跳过字符串 / 注释 / 模板串 / 正则）
          let depth = 1;
          let j = bodyStart;
          let ambiguous = false;
          let innerPrev = '{';
          while (j < n && depth > 0) {
            const c = js[j];
            if (c === '/' && js[j + 1] === '/') { const e = js.indexOf('\n', j); j = e === -1 ? n : e; continue; }
            if (c === '/' && js[j + 1] === '*') { const e = js.indexOf('*/', j + 2); j = e === -1 ? n : e + 2; continue; }
            if (c === '"' || c === "'" || c === '`') {
              const q3 = c;
              j++;
              while (j < n) {
                if (js[j] === '\\') { j += 2; continue; }
                if (js[j] === q3) break;
                j++;
              }
              j++;
              innerPrev = q3;
              continue;
            }
            if (c === '/' && (innerPrev === '{' || innerPrev === ';' || innerPrev === '(' || innerPrev === ',' || innerPrev === '=' || innerPrev === ':' || innerPrev === '[' || innerPrev === '!')) {
              // 可能是正则：按正则跳过；扫不到收尾就当普通字符，交给括号计数（有歧义就整体放弃）
              let p = j + 1;
              let cls = false;
              let ok = false;
              while (p < n) {
                const d = js[p];
                if (d === '\\') { p += 2; continue; }
                if (d === '\n') break;
                if (cls) { if (d === ']') cls = false; }
                else if (d === '[') cls = true;
                else if (d === '/') { ok = true; break; }
                p++;
              }
              if (ok) {
                j = p + 1;
                while (j < n && /[a-z]/i.test(js[j])) j++;
                innerPrev = '/';
                continue;
              }
              ambiguous = true;
              break;
            }
            if (c === '{') depth++;
            else if (c === '}') depth--;
            if (depth > 0) innerPrev = c;
            j++;
          }
          if (ambiguous || depth !== 0) return null;      // 有歧义 → 整份不改
          const body = js.slice(bodyStart, j - 1);        // 不含最外层 '}'
          out.push(js.slice(copied, i));
          /*
           * 结尾那个 `;` 不能省：bundle 是**压缩过**的，类成员之间没有换行，
           * `static x=…()constructor(){…}` 会直接解析失败（ASI 只在换行/`}` 处补分号）。
           * 实测漏掉它时 `node --check` 报语法错误 —— 这个坑值得留一行注释。
           */
          out.push('static __dshSb' + count + '=(()=>{' + body + '})();');
          count++;
          copied = j;
          i = j;
          prevSignificant = '}';
          continue;
        }
      }
    }

    if (!/\s/.test(ch)) prevSignificant = ch;
    i++;
  }

  out.push(js.slice(copied));
  return { code: out.join(''), count };
}

// ── 命令行：改写 + 自检 ────────────────────────────────────────
if (process.argv[1] && process.argv[1].endsWith('rewrite-static-block.mjs')) {
  const [, , input, output] = process.argv;
  if (!input) {
    console.error('用法: node tools/rewrite-static-block.mjs <输入.js> [输出.js]');
    process.exit(1);
  }
  const src = readFileSync(input, 'utf8');
  const res = rewriteStaticBlocks(src);
  if (!res) { console.error('❌ 有歧义，未改写（上层会退回原样加载）'); process.exit(2); }
  console.log(`改写 ${res.count} 处 static block`);
  console.log(`剩余 static{ ： ${(res.code.match(/(^|[^\w$])static\s*\{/g) || []).length}`);

  /*
   * 真实解析校验：DSH 的主 bundle 是 **ES module**（HTML 里是
   * `<script type="module" crossorigin src=...>`），所以要用 .mjs 让 Node 按模块解析 ——
   * 用 `new vm.Script()` 会误报 "Cannot use import statement outside a module"。
   */
  const tmp = (output || input) + '.check.mjs';
  writeFileSync(tmp, res.code);
  const { spawnSync } = await import('node:child_process');
  const chk = spawnSync(process.execPath, ['--check', tmp], { stdio: 'ignore' });
  const { unlinkSync } = await import('node:fs');
  try { unlinkSync(tmp); } catch { /* 忽略 */ }
  if (chk.status === 0) console.log('✅ 改写后能被 V8 按 ES module 解析通过');
  else { console.error('❌ 改写后解析失败（退出码 ' + chk.status + '）'); process.exit(3); }

  if (output) {
    writeFileSync(output, res.code);
    console.log(`已写出: ${output}`);
  }
  // 顺带报告改写点的上下文，便于人工确认
  const re = /static __dshSb\d+=\(\(\)=>\{/g;
  let m, k = 0;
  while ((m = re.exec(res.code)) !== null && k < 5) {
    console.log(`  · …${res.code.slice(Math.max(0, m.index - 60), m.index + 130).replace(/\s+/g, ' ')}…`);
    k++;
  }
}
