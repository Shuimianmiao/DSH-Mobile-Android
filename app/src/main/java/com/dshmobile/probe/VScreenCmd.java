package com.dshmobile.probe;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「虚拟屏自动化」的**纯逻辑**：拼启动脚本、解析守护返回的 JSON。
 *
 * <h3>为什么单独抽一个类</h3>
 * 这套东西（受信任虚拟屏守护 + shell 命令端点守护）是群友「如月雨露」做的方案，
 * 我们在 App 里做的是"把两个 dex 放到 /data/local/tmp 并用 shell 身份拉起来"。
 * 真正容易写错、又只能在真机上试的是那段脚本（引号、路径、杀旧实例、端口），
 * 所以把它做成**不碰任何 Android API** 的纯函数，用 {@code tools/VScreenCmdSelfTest.java}
 * 在 PC 上就能回归 —— 改错一个引号不用装到手机上才知道。
 *
 * <p>另一个雷是"守护到底起没起来"：只看命令退出码没用（nohup 起来的那条 sh 立刻就退出），
 * 必须回头查 HTTP 接口。所以解析那两段 JSON 的逻辑也放在这里，一起测。
 */
public final class VScreenCmd {

    private VScreenCmd() { }

    /** 守护与脚本在手机上的工作目录（shell 可写、App 只读日志）。 */
    public static final String DEVICE_DIR = "/data/local/tmp/dsh-vsd";
    /** 受信任虚拟屏守护的端口。 */
    public static final int PORT_VSD = 18240;
    /** shell 命令端点守护的端口（文档要求至少两个实例：一个卡死另一个还在）。 */
    public static final int PORT_SHD = 18242;
    public static final int PORT_SHD2 = 18244;
    /** token 文件名（放在 DEVICE_DIR 里，权限 600 —— 不再是 /sdcard 上那种人人可读）。 */
    public static final String TOKEN_FILE = ".shd-token";

    /**
     * 一键安装并启动的脚本（以 **shell 身份**执行）。
     *
     * <p>步骤与文档一致，但有几处刻意的不同，都写在这里免得以后有人"顺手改回去"：
     * <ul>
     *   <li>dex 与 token 都放 {@code /data/local/tmp/dsh-vsd} 并 chmod 600 ——
     *       原方案放在 {@code /sdcard}（文档 §8 自己点名：共享存储上任何有存储权限的
     *       App 都能读 token、改 dex，改完下次启动就是任意代码执行）；</li>
     *   <li>目录 chmod 711：App 自己要能读日志排查（日志 644），但 token/dex 仍然只有 shell 能读；</li>
     *   <li>杀旧实例按 {@code /proc/<pid>/cmdline} 精确匹配类名 ——
     *       不能用 {@code pkill -f}，那会匹配到正在执行这条脚本的 shell 自己；</li>
     *   <li>启动后**不**只信退出码：脚本末尾把日志和 /list 原样打出来，App 侧再单独查一次 HTTP。</li>
     * </ul>
     *
     * @param stageDir App 把 assets 解出来的暂存目录（shell 能读）
     * @param token    X-Token 口令
     */
    public static String installScript(String stageDir, String token) {
        String d = DEVICE_DIR;
        StringBuilder b = new StringBuilder();
        b.append("D=").append(d).append('\n');
        b.append("S=").append(shq(stageDir)).append('\n');
        b.append("mkdir -p $D || exit 20\n");
        b.append("cp -f $S/vs-daemon.dex $D/vs-daemon.dex || exit 21\n");
        b.append("cp -f $S/shell-daemon.dex $D/shell-daemon.dex || exit 22\n");
        b.append("printf '%s\\n' ").append(shq(token)).append(" > $D/").append(TOKEN_FILE)
                .append(" || exit 23\n");
        b.append("chmod 600 $D/vs-daemon.dex $D/shell-daemon.dex $D/").append(TOKEN_FILE).append('\n');
        b.append("chmod 711 $D\n");
        // 旧实例（上一次装的 / 别的端口起的）先全部收掉，避免端口占用与两份守护抢屏
        b.append(killLoop("VsDaemon"));
        b.append(killLoop("ShellDaemon"));
        // ★ 陈旧的 dalvik-cache 会让 app_process 直接 Aborted/Killed（文档 §3 的坑）
        b.append("rm -rf /data/local/tmp/dalvik-cache\n");
        b.append("rm -f $D/vsd.log $D/shd.log $D/shd2.log\n");
        b.append(startCmd("vs-daemon.dex", "dsh.VsDaemon", PORT_VSD, "$D/vsd.log"));
        b.append(startCmd("shell-daemon.dex", "dsh.ShellDaemon", PORT_SHD, "$D/shd.log"));
        b.append(startCmd("shell-daemon.dex", "dsh.ShellDaemon", PORT_SHD2, "$D/shd2.log"));
        // 守护要建两块虚拟屏 + 起 dalvik，给足时间；这 10 秒也是在等它们把日志写出来
        b.append("sleep 10\n");
        b.append("chmod 644 $D/*.log 2>/dev/null\n");
        b.append("echo '--- VsDaemon ---'\n");
        b.append("cat $D/vsd.log 2>/dev/null\n");
        b.append("echo '--- ShellDaemon ---'\n");
        b.append("head -6 $D/shd.log 2>/dev/null\n");
        b.append("echo '--- list ---'\n");
        b.append("curl -s -m 6 -H ").append(shq("X-Token: " + token))
                .append(" http://127.0.0.1:").append(PORT_VSD).append("/list 2>/dev/null\n");
        b.append("echo\n");
        b.append("echo '--- health ---'\n");
        b.append("curl -s -m 6 -H ").append(shq("X-Token: " + token))
                .append(" http://127.0.0.1:").append(PORT_SHD).append("/health 2>/dev/null\n");
        b.append("echo\n");
        return b.toString();
    }

    /** 停掉守护（守护一停，虚拟屏上跑着的应用会被系统一起收掉 —— 这是方案的固有行为）。 */
    public static String stopScript() {
        StringBuilder b = new StringBuilder();
        b.append(killLoop("VsDaemon"));
        b.append(killLoop("ShellDaemon"));
        b.append("echo stopped\n");
        return b.toString();
    }

    /** 只重启虚拟屏守护（换分辨率/崩了之后用），不动 shell 端点。 */
    public static String restartVsdScript(String token) {
        StringBuilder b = new StringBuilder();
        b.append(killLoop("VsDaemon"));
        b.append(startCmd("vs-daemon.dex", "dsh.VsDaemon", PORT_VSD, DEVICE_DIR + "/vsd.log"));
        b.append("sleep 9\n");
        b.append("cat ").append(DEVICE_DIR).append("/vsd.log 2>/dev/null\n");
        voidUnused(token);
        return b.toString();
    }

    /**
     * 按 {@code /proc/<pid>/cmdline} 精确杀掉某一类守护。
     *
     * <p>为什么不能 {@code pkill -f}：这条脚本自己就是以 {@code sh -c} 跑的，
     * 命令里带着类名，{@code pkill -f} 会把自己也匹配上、直接把自己杀掉，
     * 于是"重启守护"变成"什么都没发生"。
     */
    private static String killLoop(String className) {
        return "for p in $(pidof app_process); do\n"
                + "  if tr '\\0' ' ' < /proc/$p/cmdline 2>/dev/null | grep -q " + className + "; then\n"
                + "    kill -9 $p 2>/dev/null && echo killed " + className + " pid=$p\n"
                + "  fi\n"
                + "done\n";
    }

    /** 脱离本次调用启动：{@code nohup} + 后台 + 重定向，父进程退出不影响它。 */
    private static String startCmd(String dex, String cls, int port, String log) {
        return "nohup sh -c 'CLASSPATH=" + DEVICE_DIR + "/" + dex + " app_process /system/bin "
                + cls + " " + port + " " + DEVICE_DIR + "/" + TOKEN_FILE + "' > " + log
                + " 2>&1 &\n";
    }

    /** 单引号包住一段文本（脚本里唯一安全的方式：内部单引号用 '\'' 转义）。 */
    public static String shq(String s) {
        if (s == null) s = "";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static void voidUnused(String ignored) { }

    // ── 解析守护返回的 JSON（不引 JSONObject，纯正则 —— 这样 PC 上能直接测） ──

    /** {@code /list} 里有几块屏（有 displayId 才算）。 */
    public static int screenCount(String json) {
        if (json == null) return 0;
        return count(json, "\"displayId\"");
    }

    /** 守护真的建了受信任屏了吗（{@code trusted:true}）。 */
    public static boolean trusted(String json) {
        return json != null && json.contains("\"trusted\":true");
    }

    /** 取第 {@code i} 块屏的 displayId；找不到返回 -1。 */
    public static int displayId(String json, int i) {
        if (json == null) return -1;
        Matcher m = Pattern.compile("\\{[^{}]*\"i\"\\s*:\\s*" + i + "\\b[^{}]*\\}").matcher(json);
        if (!m.find()) return -1;
        return intIn(m.group(), "displayId", -1);
    }

    /** 取第 {@code i} 块屏的帧数（用来判断画面有没有在动）。 */
    public static int frames(String json, int i) {
        if (json == null) return -1;
        Matcher m = Pattern.compile("\\{[^{}]*\"i\"\\s*:\\s*" + i + "\\b[^{}]*\\}").matcher(json);
        if (!m.find()) return -1;
        return intIn(m.group(), "frames", -1);
    }

    /**
     * 给界面看的一句话摘要。
     *
     * <p>刻意只说人话：用户关心的是"有没有在跑、几块屏、是哪块、画面动没动"，
     * 不是 JSON。守护没起来时返回 null，由调用方决定显示什么。
     */
    public static String describe(int screens, boolean trusted, int displayId0, int frames0) {
        if (screens <= 0) return null;
        StringBuilder sb = new StringBuilder();
        sb.append(screens).append(" 块屏");
        if (!trusted) sb.append("（缺少受信任标记：可能不是 shell 身份起的）");
        if (displayId0 >= 0) sb.append(" · 主屏 displayId=").append(displayId0);
        if (frames0 >= 0) sb.append(" · 已取帧 ").append(frames0);
        return sb.toString();
    }

    /** {@code /health} 的返回形如 {@code uid=2000 ok}。 */
    public static int uidFromHealth(String health) {
        if (health == null) return -1;
        Matcher m = Pattern.compile("uid=(-?\\d+)").matcher(health);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static int count(String s, String needle) {
        int n = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    private static int intIn(String s, String key, int def) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : def;
    }
}
