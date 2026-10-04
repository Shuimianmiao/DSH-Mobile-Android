import com.dshmobile.probe.Relay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 「中转站」纯逻辑的回归测试 —— 重点是**写进 DSH 设置的那段 YAML**。
 *
 * 为什么这个必须测：那段文本会被写进容器里的 `~/.dsh/settings.yaml`，
 * 而那个文件里还有用户的主题、模型选择、插件设置。写坏了不是"中转站不能用"，
 * 是**整个 DSH 的设置都没了**。所以这里逐条钉住：
 *   · 没有这一节时要新建、有别的顶层键时一个都不能丢；
 *   · DSH 网页端自己加的 provider（我们不认识的那些）必须原样保留；
 *   · 我们改过的 provider 要被新内容替换掉（不能越写越多）；
 *   · id 规则与凭据名派生要和 DSH 一致（acme-gateway → ACME_GATEWAY_API_KEY）。
 *
 * 用法（在仓库根目录）:
 *   javac -encoding UTF-8 -d %TEMP%\relaytest tools\ProvidersSelfTest.java ^
 *         DshMobile\app\src\main\java\com\dshmobile\probe\Relay.java
 *   java -Dstdout.encoding=UTF-8 -cp %TEMP%\relaytest ProvidersSelfTest
 */
public class ProvidersSelfTest {

    private static int fail = 0;

    private static void check(String title, boolean ok) { check(title, ok, null); }

    private static void check(String title, boolean ok, String extra) {
        if (!ok) fail++;
        System.out.println((ok ? "  ✅ " : "  ❌ ") + title + (ok || extra == null ? "" : "\n       " + extra));
    }

    private static Relay.Route route(String id, String name, String api, String url, String... models) {
        Relay.Route r = new Relay.Route();
        r.id = id;
        r.name = name;
        r.api = api;
        r.baseUrl = url;
        for (String m : models) r.models.add(new Relay.Model(m, m, false));
        return r;
    }

    public static void main(String[] args) {
        System.out.println("── Provider ID 规则（与 DSH 派生凭据名的规则一致）──");
        check("普通小写连字符 id 合法", Relay.validateId("acme-gateway").isEmpty());
        check("单个字母也合法", Relay.validateId("a").isEmpty());
        check("数字结尾合法", Relay.validateId("gw2").isEmpty());
        check("大写开头要拒绝", !Relay.validateId("Acme").isEmpty());
        check("下划线要拒绝", !Relay.validateId("acme_gw").isEmpty());
        check("点号要拒绝", !Relay.validateId("acme.gw").isEmpty());
        check("数字开头要拒绝", !Relay.validateId("2acme").isEmpty());
        check("空要拒绝", !Relay.validateId("").isEmpty());
        check("null 要拒绝", !Relay.validateId(null).isEmpty());
        check("连字符不能开头", !Relay.validateId("-acme").isEmpty());

        System.out.println("── 凭据名派生 ──");
        check("acme-gateway → ACME_GATEWAY_API_KEY",
                "ACME_GATEWAY_API_KEY".equals(Relay.envNameOf("acme-gateway")), Relay.envNameOf("acme-gateway"));
        check("单段 id 也对", "GW_API_KEY".equals(Relay.envNameOf("gw")), Relay.envNameOf("gw"));

        System.out.println("── YAML 标量：别把文件写坏 ──");
        check("普通地址不加引号",
                "https://gateway.example/v1".equals(Relay.yamlScalar("https://gateway.example/v1")),
                Relay.yamlScalar("https://gateway.example/v1"));
        check("含空格的名称要加引号",
                Relay.yamlScalar("我的 中转站").startsWith("\""), Relay.yamlScalar("我的 中转站"));
        check("含冒号空格的要加引号",
                Relay.yamlScalar("a: b").startsWith("\""), Relay.yamlScalar("a: b"));
        check("空串加引号", "\"\"".equals(Relay.yamlScalar("")), Relay.yamlScalar(""));

        System.out.println("── 新建条目（真机上的 cordis.patch.yml 原文里本来没有 llm-pi-ai）──");
        {
            // 下面是**真机**（vivo / dsh 0.2.0-rc.2）上 cordis.patch.yml 的原文
            String oldYaml = "# Your patch layer for this dsh profile, applied after every bundle layer:\n"
                    + "# a top-level YAML array of loader patch entries (id-targeted config\n"
                    + "# overrides, disables, and insert lists; `!!js` expressions allowed).\n"
                    + "- id: ui-settings-general\n"
                    + "  name: \"@deepseek-ai/dsh-client-ui-settings-general\"\n"
                    + "  config:\n"
                    + "    welcomeNoticeVersion: 2026-09-28.1\n"
                    + "- id: ui-theme\n"
                    + "  name: \"@deepseek-ai/dsh-client-ui-theme\"\n"
                    + "  config:\n"
                    + "    preference: dark\n"
                    + "- id: agent-default-model\n"
                    + "  name: \"@deepseek-ai/dsh-agent-default-model\"\n"
                    + "  config:\n"
                    + "    provider: deepseek-official\n"
                    + "    model: deepseek-flash\n"
                    + "    reasoningEffort: high\n";
            String merged = Relay.mergeSection(oldYaml,
                    Arrays.asList(route("acme-gateway", "我的中转站", Relay.API_OPENAI_RESP,
                            "https://gateway.example/v1", "grok-4.7")));
            check("原有补丁条目都在",
                    merged.contains("- id: ui-settings-general") && merged.contains("- id: ui-theme")
                    && merged.contains("- id: agent-default-model"), merged);
            check("新条目被追加", merged.contains("- id: llm-pi-ai"), merged);
            check("带上包名（DSH 的补丁条目都有 name）", merged.contains("name: \"@deepseek-ai/dsh-llm-pi-ai\""), merged);
            check("provider 在 config.providers 下面", merged.contains("    providers:\n      acme-gateway:"), merged);
            check("凭据引用写的是派生名", merged.contains("apiKeyEnv: ACME_GATEWAY_API_KEY"), merged);
            check("协议写进去了", merged.contains("api: openai-responses"), merged);
            check("地址写进去了", merged.contains("baseURL: https://gateway.example/v1"), merged);
            check("模型带 id/name/input",
                    merged.contains("          - id: grok-4.7") && merged.contains("input: [text]"), merged);
            check("条目扫描能看到全部 4 条",
                    Relay.entryIds(merged).equals(Arrays.asList(
                            "ui-settings-general", "ui-theme", "agent-default-model", "llm-pi-ai")),
                    Relay.entryIds(merged).toString());
            check("末尾以换行结束", merged.endsWith("\n"), "结尾=" + merged.charAt(merged.length() - 1));
        }

        System.out.println("── 别人加的 provider 必须原样保留（DSH 网页端加的）──");
        {
            String oldYaml = "- id: ui-theme\n"
                    + "  name: \"@deepseek-ai/dsh-client-ui-theme\"\n"
                    + "  config:\n"
                    + "    preference: dark\n"
                    + "- id: llm-pi-ai\n"
                    + "  name: \"@deepseek-ai/dsh-llm-pi-ai\"\n"
                    + "  config:\n"
                    + "    providers:\n"
                    + "      web-added:\n"
                    + "        apiKeyEnv: WEB_ADDED_API_KEY\n"
                    + "        api: anthropic-messages\n"
                    + "        baseURL: https://claude.example/v1\n"
                    + "        models:\n"
                    + "          - id: claude-x\n"
                    + "            name: claude-x\n"
                    + "            input: [text, image]\n"
                    + "- id: agent-default-model\n"
                    + "  name: \"@deepseek-ai/dsh-agent-default-model\"\n"
                    + "  config:\n"
                    + "    provider: deepseek-official\n";
            String merged = Relay.mergeSection(oldYaml,
                    Arrays.asList(route("acme", "", Relay.API_OPENAI_CHAT, "https://acme.example/v1", "m1")));
            check("别人的 provider 还在", merged.contains("web-added:"), merged);
            check("别人的模型与协议没被改",
                    merged.contains("claude-x") && merged.contains("anthropic-messages"), merged);
            check("我们的是新增而不是替换掉别人的",
                    merged.contains("      acme:") && merged.contains("      web-added:"), merged);
            check("条目后面的其它条目没被吞掉",
                    merged.contains("- id: agent-default-model") && merged.contains("provider: deepseek-official"), merged);
            check("条目 id 齐全（ui-theme / llm-pi-ai / agent-default-model）",
                    Relay.entryIds(merged).equals(Arrays.asList(
                            "ui-theme", "llm-pi-ai", "agent-default-model")),
                    Relay.entryIds(merged).toString());
            check("llm-pi-ai 只出现一次",
                    merged.split("- id: llm-pi-ai", -1).length - 1 == 1,
                    "出现 " + (merged.split("- id: llm-pi-ai", -1).length - 1) + " 次");
        }

        System.out.println("── 改我们自己的 provider：替换而不是重复 ──");
        {
            String oldYaml = "- id: llm-pi-ai\n"
                    + "  name: \"@deepseek-ai/dsh-llm-pi-ai\"\n"
                    + "  config:\n"
                    + "    providers:\n"
                    + "      acme:\n"
                    + "        apiKeyEnv: ACME_API_KEY\n"
                    + "        api: openai-completions\n"
                    + "        baseURL: https://old.example/v1\n"
                    + "        models:\n"
                    + "          - id: old-model\n"
                    + "            name: old-model\n"
                    + "            input: [text]\n";
            String merged = Relay.mergeSection(oldYaml,
                    Arrays.asList(route("acme", "新名字", Relay.API_OPENAI_CHAT,
                            "https://new.example/v1", "new-model")));
            check("旧地址被替换", !merged.contains("old.example"), merged);
            check("旧模型被替换", !merged.contains("old-model"), merged);
            check("新内容在", merged.contains("new.example") && merged.contains("new-model"), merged);
            check("只出现一次 acme:",
                    merged.split("      acme:", -1).length - 1 == 1,
                    "出现 " + (merged.split("      acme:", -1).length - 1) + " 次");
            check("名称也更新了（中文会被加引号，这没问题）",
                    merged.contains("displayName:") && merged.contains("新名字"), merged);
        }

        System.out.println("── 空列表：把我们写过的删掉，别人的留着 ──");
        {
            String oldYaml = "- id: llm-pi-ai\n"
                    + "  name: \"@deepseek-ai/dsh-llm-pi-ai\"\n"
                    + "  config:\n"
                    + "    providers:\n"
                    + "      ours:\n"
                    + "        apiKeyEnv: OURS_API_KEY\n"
                    + "        api: openai-completions\n"
                    + "        baseURL: https://o.example/v1\n"
                    + "        models:\n"
                    + "          - id: a\n"
                    + "            name: a\n"
                    + "            input: [text]\n"
                    + "      theirs:\n"
                    + "        apiKeyEnv: THEIRS_API_KEY\n"
                    + "        api: openai-completions\n"
                    + "        baseURL: https://t.example/v1\n"
                    + "        models:\n"
                    + "          - id: b\n"
                    + "            name: b\n"
                    + "            input: [text]\n";
            String merged = Relay.mergeSection(oldYaml, new ArrayList<Relay.Route>(), Arrays.asList("ours"));
            check("我们的没了", !merged.contains("ours:"), merged);
            check("别人的还在", merged.contains("theirs:"), merged);
        }

        System.out.println("── 图片能力与多模型 ──");
        {
            Relay.Route r = route("gw", "gw", Relay.API_OPENAI_CHAT, "https://gw.example/v1");
            r.models.add(new Relay.Model("vision-1", "vision-1", true));
            r.models.add(new Relay.Model("text-1", "text-1", false));
            String merged = Relay.mergeSection("", Arrays.asList(r));
            check("图像模型写 text, image", merged.contains("input: [text, image]"), merged);
            check("纯文本模型写 text", merged.contains("input: [text]\n"), merged);
            check("两个模型都在", merged.split("- id: vision-1", -1).length - 1 == 1
                    && merged.split("- id: text-1", -1).length - 1 == 1, merged);
        }

        System.out.println("── 条目扫描器本身 ──");
        check("能数出条目 id",
                Relay.entryIds("- id: a\n  config:\n    x: 1\n- id: b\n").equals(Arrays.asList("a", "b")),
                Relay.entryIds("- id: a\n  config:\n    x: 1\n- id: b\n").toString());
        check("注释不算",
                Relay.entryIds("# - id: x\n- id: a\n").equals(Arrays.asList("a")),
                Relay.entryIds("# - id: x\n- id: a\n").toString());
        check("null 安全", Relay.entryIds(null).isEmpty(), Relay.entryIds(null).toString());

        System.out.println(fail == 0 ? "\n✅ 全部通过" : "\n❌ 失败 " + fail + " 项");
        System.exit(fail == 0 ? 0 : 1);
    }
}