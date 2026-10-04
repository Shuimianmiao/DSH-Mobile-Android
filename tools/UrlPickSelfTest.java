import com.dshmobile.probe.UrlPick;

/**
 * UrlPick 的回归测试 —— 用的全是**用户日志里的真实行**。
 *
 * 背景：装了第三方插件（billion-context）之后，插件在自己端口上起了个面板服务，
 * 那行地址混进了 dsh 的输出，被旧逻辑当成"DSH 的地址"抓走 → 整个界面变成了
 * 插件的网页。这个测试就是保证"只认 DSH 自己那个带 token 的地址"。
 *
 * 用法（在仓库根目录）:
 *   javac -encoding UTF-8 -d %TEMP%/urltest tools/UrlPickSelfTest.java ^
 *         DshMobile/app/src/main/java/com/dshmobile/probe/UrlPick.java
 *   java -cp %TEMP%/urltest UrlPickSelfTest
 */
public class UrlPickSelfTest {

    private static int fail = 0;

    private static void expect(String line, String want) {
        String got = UrlPick.pickDshUrl(line);
        boolean ok = (want == null) ? (got == null) : want.equals(got);
        if (!ok) fail++;
        System.out.println((ok ? "  ✅ " : "  ❌ ") + brief(line)
                + "\n       期望 " + want + " ／ 实得 " + got);
    }

    private static String brief(String s) {
        String t = s == null ? "null" : s.replaceAll("\\s+", " ").trim();
        return t.length() > 96 ? t.substring(0, 96) + "…" : t;
    }

    public static void main(String[] args) {
        System.out.println("── DSH 自己的地址（必须认出来）──");
        expect("    dsh| dsh web: http://127.0.0.1:3080/?token=joEyPUjDjqc27qatdpO2qDwKpwc8cINskWVrHjeONPE",
                "http://127.0.0.1:3080/?token=joEyPUjDjqc27qatdpO2qDwKpwc8cINskWVrHjeONPE");
        expect("dsh web: http://127.0.0.1:3080/?token=_lLoFw2miCXjNNLaP20XWIfvvG4xlbbgh2OunG33waQ",
                "http://127.0.0.1:3080/?token=_lLoFw2miCXjNNLaP20XWIfvvG4xlbbgh2OunG33waQ");
        expect("  dsh web: http://localhost:3080/?token=U-qcawCM9jX4-muP2EDpjwV289dlodHYa_2mME8FWnU (pid 12)",
                "http://127.0.0.1:3080/?token=U-qcawCM9jX4-muP2EDpjwV289dlodHYa_2mME8FWnU");
        expect("listening on http://0.0.0.0:3080/?token=abcDEF123",
                "http://127.0.0.1:3080/?token=abcDEF123");
        expect("http://127.0.0.1:3080/?token=xCvg6bjjbKCwB5finsoL5az9NHPQUGABpjJ76q0BKX0",
                "http://127.0.0.1:3080/?token=xCvg6bjjbKCwB5finsoL5az9NHPQUGABpjJ76q0BKX0");

        System.out.println("── 插件的面板地址 / 别的服务（必须拒掉）──");
        expect("    dsh| 2026-10-01T16:38:24.463Z [info] [v=dev] native-attach: drop http://127.0.0.1:18787 (pid 28618): owner process gone", null);
        expect("   WebView 加载: http://127.0.0.1:18787", null);
        expect("  ✅ 服务就绪（前台服务）: http://127.0.0.1:18787", null);
        expect("billion-context dashboard: http://127.0.0.1:18787", null);
        expect("本地代理 CLIProxyAPI 监听 http://127.0.0.1:8317", null);
        expect("http://127.0.0.1:3099", null);

        System.out.println("── 边界 ──");
        expect(null, null);
        expect("", null);
        expect("没有任何地址的一行", null);
        // 老/未来版本兜底：dsh 自己那行不带 token 也要认
        expect("dsh web listening on http://127.0.0.1:3080", "http://127.0.0.1:3080");
        // 插件那行即使带个端口也不该被当成 dsh 公告
        expect("plugin ready on http://127.0.0.1:18787/", null);
        // token 里出现奇怪字符也不能被截断（截断 → 401 白屏，且报错里看不出原因）
        expect("dsh web: http://127.0.0.1:3080/?token=a~b+c/d=e",
                "http://127.0.0.1:3080/?token=a~b+c/d=e");
        // 公告里没有斜杠、直接跟查询串的形式：不能把 token 丢掉
        expect("dsh web: http://127.0.0.1:3080?token=abc123",
                "http://127.0.0.1:3080?token=abc123");

        System.out.println("── 附：外来回环地址能被单独识别（报告用）──");
        String foreign = UrlPick.pickForeignLoopback(
                "dsh| native-attach: drop http://127.0.0.1:18787 (pid 1): owner process gone");
        boolean okForeign = "http://127.0.0.1:18787".equals(foreign);
        if (!okForeign) fail++;
        System.out.println((okForeign ? "  ✅ " : "  ❌ ") + "识别到外来服务: " + foreign);

        System.out.println(fail == 0 ? "\n✅ 全部通过" : "\n❌ 失败 " + fail + " 项");
        System.exit(fail == 0 ? 0 : 1);
    }
}
