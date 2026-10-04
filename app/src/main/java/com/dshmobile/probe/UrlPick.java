package com.dshmobile.probe;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 dsh 进程的输出里挑出 <b>DSH 自己的</b>那个 URL。
 *
 * <h3>为什么不能"看到 127.0.0.1 就抓"（真实事故）</h3>
 * 用户装了一个第三方插件（billion-context）之后，界面变成了那张插件的网页，
 * 而且退不出去。日志里是这样的：
 *
 * <pre>
 *   WebView 加载: http://127.0.0.1:18787
 *   dsh| dsh web: http://127.0.0.1:3080/?token=joEyPUjDjqc27qatdpO2qDwKpwc8cINskWVrHjeONPE
 *   dsh| native-attach: drop http://127.0.0.1:18787 (pid 28618): owner process gone
 * </pre>
 *
 * 那个插件会在**自己的端口**上起一个面板服务，并且把这行地址打进了 dsh 的输出；
 * 而原来的匹配是"抓第一个 http://127.0.0.1:&lt;端口&gt;"，于是 18787 抢先被抓走，
 * 整个界面被加载成了插件的面板 —— 用户当然退不出去（那不是 DSH 的界面，
 * 注入的分层返回也不在这个页面里）。
 *
 * <h3>怎么认准 DSH</h3>
 * DSH 自己打印的地址**一定带鉴权 token**（形如 {@code http://127.0.0.1:3080/?token=xxx}），
 * 而且行里有 {@code dsh web} 这个前缀。所以：
 * <ol>
 *   <li>优先：带 {@code ?token=} 的回环地址 —— 这是 DSH 的形态；</li>
 *   <li>兜底：整行含 {@code dsh web} 且带回环地址 —— 万一以后 DSH 不打 token 了
 *       （插件不会打这几个字，所以仍然安全）；</li>
 *   <li>其它一切回环地址（插件的面板、本地代理…）**一律不认**。</li>
 * </ol>
 *
 * <p>纯逻辑、不碰 Android API —— 这样可以用 {@code tools/UrlPickSelfTest.java}
 * 拿用户日志里的真实行做回归测试。
 */
public final class UrlPick {

    private UrlPick() { }

    /**
     * 带 token 的回环地址：DSH 的形态。
     *
     * <p>token 的字符集故意放宽成"非空白、非引号、非右括号"的一整段：
     * 收紧成 {@code [A-Za-z0-9_.-]} 的话，万一以后 token 里出现别的字符，
     * 正则会**匹配到一半就截断** —— 那样交出去的地址是残缺的，页面只会 401 白屏，
     * 而且报错信息里看不出是"被截断了"。放宽之后要么整段拿到、要么不匹配。
     */
    private static final Pattern WITH_TOKEN = Pattern.compile(
            "https?://(?:127\\.0\\.0\\.1|localhost|0\\.0\\.0\\.0|\\[::1\\]|\\[::\\]):(\\d+)"
          + "(/[^\\s)\"'<>]*\\?token=[^\\s)\"'<>]+)");

    /**
     * 任意回环地址（只在整行是 dsh 自己的公告时才会用到）。
     *
     * <p>路径那段允许"没有斜杠、只有一个查询串"的形式（{@code :3080?token=x}），
     * 否则兜底路径会把 token 丢掉、交出一个必然 401 的地址。
     */
    private static final Pattern ANY_LOOPBACK = Pattern.compile(
            "https?://(?:127\\.0\\.0\\.1|localhost|0\\.0\\.0\\.0|\\[::1\\]|\\[::\\]):(\\d+)"
          + "((?:/[^\\s)\"'<>]*)?(?:\\?[^\\s)\"'<>]*)?)");

    /** dsh 公告自己地址时那行的特征。 */
    private static final String DSH_ANNOUNCE = "dsh web";

    /**
     * 从一行输出里挑出 DSH 的 URL。
     *
     * @return 归一化成 {@code http://127.0.0.1:<port>/...} 的地址；不是 DSH 的返回 null
     */
    public static String pickDshUrl(String line) {
        if (line == null || line.isEmpty()) return null;
        Matcher m = WITH_TOKEN.matcher(line);
        if (m.find()) return normalize(m);
        if (line.contains(DSH_ANNOUNCE)) {
            Matcher a = ANY_LOOPBACK.matcher(line);
            if (a.find()) return normalize(a);
        }
        return null;
    }

    /** 这一行里是不是有"像别的服务"的回环地址（用来在报告里留一行提示）。 */
    public static String pickForeignLoopback(String line) {
        if (line == null || line.isEmpty()) return null;
        if (pickDshUrl(line) != null) return null;
        Matcher a = ANY_LOOPBACK.matcher(line);
        return a.find() ? (a.group(0)) : null;
    }

    private static String normalize(Matcher m) {
        String path = m.group(2) == null ? "" : m.group(2);
        return "http://127.0.0.1:" + m.group(1) + path;
    }
}
