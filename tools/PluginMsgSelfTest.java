import com.dshmobile.probe.PluginMsg;

import java.util.Arrays;
import java.util.List;

/**
 * PluginMsg 的回归测试 —— 用例全部来自**真机日志**。
 *
 * 背景（用户 0.2.8.2 上的事故）：
 *   · DSH 的启动失败文案是 `dsh-pet: import failed (see console for the import error)`，
 *     旧正则只认 `name: (pending|failed|error)`，认不出 `import failed`；
 *   · 认不出的后果不是"少停一个"，而是退化成"停掉**全部**第三方插件"——
 *     真机上用户的 8 个插件被一起摘掉，界面反而进不去了。
 * 另外还要保证 `web boot: 1 entry did not activate` 这种句子**不会**被当成插件名
 *（它中间有空格，但旧写法很容易不小心把它当成 "web boot" 这个包）。
 *
 * 用法（在仓库根目录）:
 *   javac -encoding UTF-8 -d %TEMP%\pmtest tools\PluginMsgSelfTest.java ^
 *         DshMobile\app\src\main\java\com\dshmobile\probe\PluginMsg.java
 *   java -cp %TEMP%\pmtest PluginMsgSelfTest
 */
public class PluginMsgSelfTest {

    /** 官方 + 自带（Env.safeBundles()）。 */
    private static final List<String> PROTECTED = Arrays.asList(
            "@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app",
            "dsh-phone", "dsh-phone-files");

    /** 真机上 profile 里登记过的那些。 */
    private static final List<String> REGISTERED = Arrays.asList(
            "@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app",
            "dsh-phone", "dsh-phone-files",
            "dsh-pet", "qq-mode-console", "qq-agent-presets", "dsh-speech");

    private static int fail = 0;

    private static PluginMsg.Parsed parse(String detail) {
        return PluginMsg.parse(detail, REGISTERED, PROTECTED);
    }

    private static void expectSuspects(String title, String detail, String... want) {
        PluginMsg.Parsed p = parse(detail);
        List<String> got = p.suspects;
        boolean ok = got.equals(Arrays.asList(want));
        if (!ok) fail++;
        System.out.println((ok ? "  ✅ " : "  ❌ ") + title
                + "\n       期望 " + Arrays.asList(want) + " ／ 实得 " + got);
    }

    private static void expectNamed(String title, String detail, String... want) {
        PluginMsg.Parsed p = parse(detail);
        boolean ok = p.named.equals(Arrays.asList(want));
        if (!ok) fail++;
        System.out.println((ok ? "  ✅ " : "  ❌ ") + title
                + "\n       期望 " + Arrays.asList(want) + " ／ 实得 " + p.named
                + (p.named.isEmpty() ? "" : "（suspects=" + p.suspects + "）"));
    }

    private static void expectBool(String title, boolean got, boolean want) {
        boolean ok = got == want;
        if (!ok) fail++;
        System.out.println((ok ? "  ✅ " : "  ❌ ") + title + "（实得 " + got + "）");
    }

    public static void main(String[] args) {
        System.out.println("── 真机上实际出现过的那一段（必须点出 dsh-pet）──");
        expectSuspects("DSH 0.2 的启动卡原文", "Failed to load plugins\n"
                + "dsh-pet: import failed (see console for the import error)", "dsh-pet");
        expectSuspects("import failed 带原因", "dsh-pet: import failed: Cannot find module 'x'", "dsh-pet");
        expectSuspects("老形态 pending (waiting for service)",
                "dsh-speech: pending (waiting for service: settingsScope)", "dsh-speech");
        expectSuspects("activate failed", "qq-mode-console: activate failed", "qq-mode-console");
        expectSuspects("最朴素的 failed", "qq-agent-presets: failed", "qq-agent-presets");
        expectSuspects("中文冒号", "dsh-pet：import failed", "dsh-pet");
        expectSuspects("前面带项目符号", "  · dsh-pet: import failed", "dsh-pet");
        expectSuspects("两句都点名（按出现顺序、去重）",
                "dsh-pet: import failed\ndsh-speech: pending (waiting for service: x)\n"
                        + "dsh-pet: import failed", "dsh-pet", "dsh-speech");

        System.out.println("── 不能误判成包名的句子 ──");
        expectNamed("web boot: 1 entry did not activate", "web boot: 1 entry did not activate");
        expectNamed("Failed to load plugins（标题行）", "Failed to load plugins");
        expectNamed("时间戳", "17:05\n16:36\n17:14");
        expectNamed("URL 行", "https://github.com/Derpyu520/qq-bridge");
        expectNamed("状态行", "our-free-model/mimo-v2.5-free\nlight\n10 轮 188 步·56 tok/s");
        expectNamed("空白页兜底文案", "(空白页面：主界面 25 秒内没有渲染出来)");
        expectNamed("null / 空串", null);
        expectNamed("空串", "");

        System.out.println("── 分类：官方自带 / 不在 profile 里 ──");
        {
            PluginMsg.Parsed p = parse("dsh-phone: activate failed");
            expectBool("点名的是自带插件 → 不算 suspect", p.suspects.isEmpty(), true);
            expectBool("点名的是自带插件 → 进 protectedOnes",
                    p.protectedOnes.equals(Arrays.asList("dsh-phone")), true);
        }
        {
            PluginMsg.Parsed p = parse("some-other/plugin-x: import failed: 超时");
            expectBool("不在 profile 里 → 不算 suspect", p.suspects.isEmpty(), true);
            expectBool("不在 profile 里 → 进 unregistered",
                    p.unregistered.equals(Arrays.asList("some-other/plugin-x")), true);
        }

        System.out.println("── 文本特征（只影响报告里那一行）──");
        expectBool("含 插件 字样 → pluginRelated",
                parse("插件可能把界面卡住了").pluginRelated, true);
        expectBool("英文 failed to load → pluginRelated",
                parse("Failed to load plugins").pluginRelated, true);
        expectBool("纯空白页文案 → 不是插件相关",
                parse("(空白页面：主界面 25 秒内没有渲染出来)").pluginRelated, false);

        System.out.println(fail == 0 ? "\n✅ 全部通过" : "\n❌ 失败 " + fail + " 项");
        System.exit(fail == 0 ? 0 : 1);
    }
}
