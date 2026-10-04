import com.dshmobile.probe.VScreenCmd;

/**
 * VScreenCmd 的回归测试 —— 「虚拟屏一键安装」里最容易写错、又只能真机才暴露的东西：
 * 那段以 shell 身份执行的启动脚本，和"守护到底起没起来"的 JSON 解析。
 *
 * 为什么值得测（都是真会踩的）：
 *   · 脚本里少一个引号 → 整段命令被 shell 拆错，守护起不来，而退出码可能还是 0；
 *   · 杀旧实例写成 pkill -f → 把执行这条脚本的 shell 自己杀掉，"重启"变成"什么都没发生"；
 *   · 不绑回环 / 不校验 token → 同一个 WiFi 下任何人都能读屏（原方案的洞，我们补上了）；
 *   · /list 的 JSON 解析写错 → 界面显示"没在跑"，其实在跑。
 *
 * 用法（在仓库根目录）:
 *   javac -encoding UTF-8 -d %TEMP%/vstest tools/VScreenCmdSelfTest.java ^
 *         DshMobile/app/src/main/java/com/dshmobile/probe/VScreenCmd.java
 *   java -Dstdout.encoding=UTF-8 -cp %TEMP%/vstest VScreenCmdSelfTest
 */
public class VScreenCmdSelfTest {

    private static int fail = 0;

    private static void ok(String what, boolean cond, String detail) {
        if (!cond) fail++;
        System.out.println((cond ? "  ✅ " : "  ❌ ") + what + (cond ? "" : "\n       " + detail));
    }

    private static void eq(String what, Object want, Object got) {
        boolean c = want == null ? got == null : want.equals(got);
        if (!c) fail++;
        System.out.println((c ? "  ✅ " : "  ❌ ") + what + (c ? "" : "\n       期望 " + want + " ／ 实得 " + got));
    }

    public static void main(String[] args) {
        String token = "0123456789abcdef0123456789abcdef";

        System.out.println("── 单引号转义（脚本里唯一安全的包法）──");
        eq("普通串", "'abc'", VScreenCmd.shq("abc"));
        eq("带空格", "'a b'", VScreenCmd.shq("a b"));
        // 这一条是关键：token 里若出现单引号，不转义就会把后面的命令放出来
        eq("带单引号必须转义成 '\\''", "'a'\\''b'", VScreenCmd.shq("a'b"));
        eq("带 $ 与反引号不被展开", "'a$B`c`'", VScreenCmd.shq("a$B`c`"));
        eq("null 当空串", "''", VScreenCmd.shq(null));

        System.out.println("── 安装脚本：该有的都要有 ──");
        String s = VScreenCmd.installScript("/sdcard/Android/data/com.dshmobile.probe/files/vsd", token);
        ok("dex 与 token 落到 /data/local/tmp/dsh-vsd",
                s.contains("D=" + VScreenCmd.DEVICE_DIR) && s.contains("$D/vs-daemon.dex")
                        && s.contains("$D/shell-daemon.dex") && s.contains("$D/" + VScreenCmd.TOKEN_FILE), s);
        ok("dex 与 token chmod 600（只有 shell 能读）",
                s.contains("chmod 600 $D/vs-daemon.dex $D/shell-daemon.dex $D/" + VScreenCmd.TOKEN_FILE), s);
        ok("暂存路径里的单引号会被包好（路径带空格也能用）",
                s.contains("S='/sdcard/Android/data/com.dshmobile.probe/files/vsd'"), s);
        ok("清陈旧 dalvik-cache（否则 app_process 直接 Aborted）",
                s.contains("rm -rf /data/local/tmp/dalvik-cache"), s);
        ok("按 cmdline 精确杀旧实例，而不是 pkill -f",
                s.contains("/proc/$p/cmdline") && s.contains("grep -q VsDaemon")
                        && s.contains("grep -q ShellDaemon") && !s.contains("pkill -f"), s);
        ok("两个 shell 端点实例（18242 与 18244）",
                s.contains("dsh.ShellDaemon " + VScreenCmd.PORT_SHD + " ")
                        && s.contains("dsh.ShellDaemon " + VScreenCmd.PORT_SHD2 + " "), s);
        ok("虚拟屏守护用 18240", s.contains("dsh.VsDaemon " + VScreenCmd.PORT_VSD + " "), s);
        ok("nohup 脱离本次调用（父进程退出不影响守护）", s.contains("nohup sh -c"), s);
        ok("启动后回读日志并打 /list 与 /health（不只信退出码）",
                s.contains("cat $D/vsd.log") && s.contains(":" + VScreenCmd.PORT_VSD + "/list")
                        && s.contains(":" + VScreenCmd.PORT_SHD + "/health"), s);
        ok("curl 带上 X-Token", s.contains("-H " + VScreenCmd.shq("X-Token: " + token)), s);
        ok("带 token 文件参数启动两个守护",
                s.contains("dsh.VsDaemon " + VScreenCmd.PORT_VSD + " " + VScreenCmd.DEVICE_DIR + "/" + VScreenCmd.TOKEN_FILE)
                        && s.contains("dsh.ShellDaemon " + VScreenCmd.PORT_SHD + " " + VScreenCmd.DEVICE_DIR + "/" + VScreenCmd.TOKEN_FILE), s);

        System.out.println("── 停止脚本 ──");
        String stop = VScreenCmd.stopScript();
        ok("两类守护都收掉", stop.contains("grep -q VsDaemon") && stop.contains("grep -q ShellDaemon"), stop);

        System.out.println("── /list 解析（真实形状）──");
        String list = "[{\"i\":0,\"displayId\":5,\"width\":636,\"height\":1400,\"frames\":123,\"trusted\":true},"
                + "{\"i\":1,\"displayId\":6,\"width\":1400,\"height\":636,\"frames\":0,\"trusted\":true}]";
        eq("屏数", 2, VScreenCmd.screenCount(list));
        eq("trusted", true, VScreenCmd.trusted(list));
        eq("第 0 块的 displayId", 5, VScreenCmd.displayId(list, 0));
        eq("第 1 块的 displayId", 6, VScreenCmd.displayId(list, 1));
        eq("第 0 块的帧数", 123, VScreenCmd.frames(list, 0));
        eq("不存在的第 2 块 → -1", -1, VScreenCmd.displayId(list, 2));
        eq("空 JSON → 0 块", 0, VScreenCmd.screenCount(null));
        eq("守护自己报错时的非 JSON 文本 → 0 块", 0, VScreenCmd.screenCount("bad index"));
        eq("摘要（人话）", "2 块屏 · 主屏 displayId=5 · 已取帧 123",
                VScreenCmd.describe(2, true, 5, 123));
        eq("没有屏时不给人话（界面显示「没在跑」）", null, VScreenCmd.describe(0, false, -1, -1));
        ok("少了 trusted 标记要能看出来",
                VScreenCmd.describe(1, false, 5, 0).contains("受信任"), VScreenCmd.describe(1, false, 5, 0));

        System.out.println("── /health 解析 ──");
        eq("uid=2000 ok → 2000", 2000, VScreenCmd.uidFromHealth("uid=2000 ok\n"));
        eq("root → 0", 0, VScreenCmd.uidFromHealth("uid=0 ok\n"));
        eq("403 DENIED → -1（界面会显示「返回异常」）", -1, VScreenCmd.uidFromHealth("DENIED\n"));
        eq("null → -1", -1, VScreenCmd.uidFromHealth(null));

        System.out.println(fail == 0 ? "\n全部通过 ✅" : "\n有 " + fail + " 条失败 ❌");
        if (fail > 0) System.exit(1);
    }
}
