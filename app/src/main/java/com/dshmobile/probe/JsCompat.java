package com.dshmobile.probe;

/**
 * JS 语法降级：把 <b>class static block</b>（{@code static{…}}，Chrome 94+）
 * 改写成等价的静态字段初始化（Chrome 72+）。
 *
 * <h3>为什么需要它（真实用户事故）</h3>
 * HONOR KOZ-AL00 / Android 10 / 系统 WebView <b>Chrome 88</b>：容器一切正常、
 * 页面也 onPageFinished 了，界面却**一个字都不渲染**。把 served 的 bundle 抓下来分析
 * （工具：{@code tools/dsh-api-inventory.mjs}）后确认：整个前端里唯一的语法障碍是
 * DSH 主 bundle（{@code /assets/index-*.js}，ES module）里 **2 处 class static block**。
 * Chrome 88 在**解析阶段**就抛 SyntaxError，整个 bundle 作废 —— 所以是永远的白屏。
 *
 * <p>这种问题 {@code webview-shim.js} 那类垫片救不了：垫片补的是**运行时**缺失的
 * 全局对象（{@code Iterator} / {@code AbortSignal.any} …），而这是**语法**。
 *
 * <h3>改写规则与为什么等价</h3>
 * <pre>
 *   class A { static { BODY } }
 *     ↓
 *   class A { static __dshSb0 = (() =&gt; { BODY })(); }
 * </pre>
 * <ul>
 *   <li>静态字段初始化器与 static block 一样，在**类定义时**求值一次；</li>
 *   <li>初始化器里的 {@code this} 就是类本身，箭头函数按词法捕获它 →
 *       static block 里 {@code this} 的语义不变；</li>
 *   <li>static block 里 <b>不允许</b> {@code return} / {@code await} / {@code yield}
 *       （是语法错误），箭头函数体里同样不允许 —— 所以不存在"改写后行为变了"；</li>
 *   <li>直接引用类名（压缩后形如 {@code _4}）或闭包变量（形如 {@code io}）都照旧有效。</li>
 * </ul>
 *
 * <p>结尾的分号**不能省**：bundle 是压缩过的，类成员之间没有换行，
 * {@code …()constructor(){…}} 会因为 ASI 不补分号而解析失败（实测踩到过）。
 *
 * <h3>安全性</h3>
 * {@code static} 后面直接跟 <code>{</code> 在合法 JS 里**只可能**是 static block
 * （出现在别处都是语法错误），所以起点很好找。剩下的风险只在"数花括号"时被
 * 字符串 / 注释 / 模板串 / 正则里的花括号骗到 —— 扫描器把这几种上下文都跳过，
 * 一旦发现**歧义就整份放弃**（返回 {@code ambiguous}），让调用方原样加载：
 * 宁可维持现状，也绝不把 bundle 改坏。
 *
 * <p>改写逻辑与 {@code tools/rewrite-static-block.mjs}（先用 Node 在真实 bundle 上
 * 验证过：改写后 V8 能按 ES module 解析通过）保持一致。
 */
public final class JsCompat {

    private JsCompat() { }

    /** 一次改写的结果。 */
    public static final class Result {
        /** 改写后的文本；{@code count == 0} 时与输入相同。 */
        public final String code;
        /** 改了几处 static block。 */
        public final int count;
        /** true = 扫描时遇到无法判定的上下文，**不要**使用 {@link #code}。 */
        public final boolean ambiguous;

        Result(String code, int count, boolean ambiguous) {
            this.code = code;
            this.count = count;
            this.ambiguous = ambiguous;
        }
    }

    private static boolean isIdentChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_' || c == '$';
    }

    /**
     * 扫一遍并改写。**不做 IO**，纯函数，方便单测/复跑。
     *
     * @return 永不为 null；{@link Result#ambiguous} 为 true 时调用方必须放弃改写
     */
    public static Result rewrite(String js) {
        if (js == null || js.indexOf("static") < 0) {
            return new Result(js == null ? "" : js, 0, false);
        }
        final int n = js.length();
        StringBuilder out = new StringBuilder(n + 64);
        int i = 0;
        int copied = 0;          // 已经原样复制到的位置
        int count = 0;
        char prev = '\0';        // 上一个"有意义"的字符（用来区分正则 / 除号）

        while (i < n) {
            char ch = js.charAt(i);

            // ── 行注释 ──
            if (ch == '/' && i + 1 < n && js.charAt(i + 1) == '/') {
                int e = js.indexOf('\n', i);
                i = (e == -1) ? n : e;
                continue;
            }
            // ── 块注释 ──
            if (ch == '/' && i + 1 < n && js.charAt(i + 1) == '*') {
                int e = js.indexOf("*/", i + 2);
                i = (e == -1) ? n : e + 2;
                continue;
            }
            // ── 正则字面量（只在"这里允许出现正则"时才当正则）──
            if (ch == '/' && regexAllowed(prev)) {
                int j = i + 1;
                boolean inClass = false;
                boolean closed = false;
                while (j < n) {
                    char c = js.charAt(j);
                    if (c == '\\') { j += 2; continue; }
                    if (c == '\n') break;                       // 正则不跨行 → 判断错了
                    if (inClass) { if (c == ']') inClass = false; }
                    else if (c == '[') inClass = true;
                    else if (c == '/') { closed = true; break; }
                    j++;
                }
                if (closed) {
                    j++;
                    while (j < n && Character.isLetter(js.charAt(j))) j++;   // flags
                    i = j;
                    prev = '/';
                    continue;
                }
                prev = ch;                                       // 当除号
                i++;
                continue;
            }
            // ── 字符串 / 模板串 ──
            if (ch == '"' || ch == '\'' || ch == '`') {
                char quote = ch;
                int j = i + 1;
                while (j < n) {
                    char c = js.charAt(j);
                    if (c == '\\') { j += 2; continue; }
                    if (c == quote) { j++; break; }
                    if (quote == '`' && c == '$' && j + 1 < n && js.charAt(j + 1) == '{') {
                        j = skipTemplateExpr(js, j + 2);              // 跳过 ${…}
                        continue;
                    }
                    j++;
                }
                i = j;
                prev = quote;
                continue;
            }

            // ── static block ──
            if (ch == 's' && js.startsWith("static", i)) {
                boolean wordStart = (i == 0) || !isIdentChar(js.charAt(i - 1));
                boolean wordEnd = (i + 6 >= n) || !isIdentChar(js.charAt(i + 6));
                if (wordStart && wordEnd) {
                    int k = skipWsAndComments(js, i + 6);
                    if (k < n && js.charAt(k) == '{') {
                        int end = matchBrace(js, k);                  // 指向配对的 '}'
                        if (end < 0) {
                            return new Result(js, 0, true);           // 有歧义 → 放弃
                        }
                        String body = js.substring(k + 1, end);
                        out.append(js, copied, i);
                        out.append("static __dshSb").append(count)
                           .append("=(()=>{").append(body).append("})();");
                        count++;
                        copied = end + 1;
                        i = end + 1;
                        prev = '}';
                        continue;
                    }
                }
            }

            if (!Character.isWhitespace(ch)) prev = ch;
            i++;
        }

        out.append(js, copied, n);
        return new Result(out.toString(), count, false);
    }

    /** 这里允许出现正则字面量吗（否则就是除号）。 */
    private static boolean regexAllowed(char prev) {
        if (prev == '\0') return true;
        if (isIdentChar(prev)) return false;
        return !(prev == ')' || prev == ']' || prev == '}');
    }

    /** 跳过 {@code ${…}} 里的一段（内部可能有任意 JS，含嵌套花括号与字符串）。 */
    private static int skipTemplateExpr(String js, int from) {
        int n = js.length();
        int depth = 1;
        int j = from;
        while (j < n && depth > 0) {
            char d = js.charAt(j);
            if (d == '\\') { j += 2; continue; }
            if (d == '{') depth++;
            else if (d == '}') depth--;
            else if (d == '"' || d == '\'' || d == '`') {
                char q = d;
                j++;
                while (j < n) {
                    if (js.charAt(j) == '\\') { j += 2; continue; }
                    if (js.charAt(j) == q) break;
                    j++;
                }
            }
            j++;
        }
        return j;
    }

    /** 跳过空白与注释，返回下一个有效字符的下标。 */
    private static int skipWsAndComments(String js, int from) {
        int n = js.length();
        int k = from;
        while (k < n) {
            char c = js.charAt(k);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') { k++; continue; }
            if (c == '/' && k + 1 < n && js.charAt(k + 1) == '/') {
                int e = js.indexOf('\n', k);
                k = (e == -1) ? n : e;
                continue;
            }
            if (c == '/' && k + 1 < n && js.charAt(k + 1) == '*') {
                int e = js.indexOf("*/", k + 2);
                k = (e == -1) ? n : e + 2;
                continue;
            }
            break;
        }
        return k;
    }

    /**
     * 从 {@code open}（指向 '}' 的配对起点 '{'）出发找配对的 '}'。
     *
     * @return 配对 '}' 的下标；-1 = 遇到无法判定的上下文（调用方放弃改写）
     */
    private static int matchBrace(String js, int open) {
        int n = js.length();
        int depth = 1;
        int j = open + 1;
        char innerPrev = '{';
        while (j < n && depth > 0) {
            char c = js.charAt(j);
            if (c == '/' && j + 1 < n && js.charAt(j + 1) == '/') {
                int e = js.indexOf('\n', j);
                j = (e == -1) ? n : e;
                continue;
            }
            if (c == '/' && j + 1 < n && js.charAt(j + 1) == '*') {
                int e = js.indexOf("*/", j + 2);
                j = (e == -1) ? n : e + 2;
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') {
                char q = c;
                j++;
                while (j < n) {
                    if (js.charAt(j) == '\\') { j += 2; continue; }
                    if (js.charAt(j) == q) break;
                    j++;
                }
                j++;
                innerPrev = q;
                continue;
            }
            if (c == '/' && (innerPrev == '{' || innerPrev == ';' || innerPrev == '('
                    || innerPrev == ',' || innerPrev == '=' || innerPrev == ':'
                    || innerPrev == '[' || innerPrev == '!')) {
                int p = j + 1;
                boolean cls = false;
                boolean ok = false;
                while (p < n) {
                    char d = js.charAt(p);
                    if (d == '\\') { p += 2; continue; }
                    if (d == '\n') break;
                    if (cls) { if (d == ']') cls = false; }
                    else if (d == '[') cls = true;
                    else if (d == '/') { ok = true; break; }
                    p++;
                }
                if (ok) {
                    j = p + 1;
                    while (j < n && Character.isLetter(js.charAt(j))) j++;
                    innerPrev = '/';
                    continue;
                }
                return -1;                       // 疑似正则却收不了尾 → 不冒这个险
            }
            if (c == '{') depth++;
            else if (c == '}') depth--;
            if (depth > 0) innerPrev = c;
            j++;
        }
        return (depth == 0) ? (j - 1) : -1;
    }
}
