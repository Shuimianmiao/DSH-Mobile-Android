package com.dshmobile.probe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从"界面没能加载出来"的原文里认出**是哪个插件**把界面卡住了。
 *
 * <h3>为什么需要单独一个纯逻辑类</h3>
 * 这段解析以前写在 {@code MainActivity.suspectBundles()} 里，只有一个正则，
 * 而那个正则**认不出 DSH 0.2 实际打出来的那句话**：
 *
 * <pre>
 *   Failed to load plugins
 *   dsh-pet: import failed (see console for the import error)   ← 状态词是 "import failed"
 * </pre>
 *
 * 旧正则是 {@code ^name:\s*(pending|failed|error)} —— 冒号后面直接要求状态词，
 * 遇到 {@code import failed} 就匹配不上。结果是：明明点出了罪魁祸首，
 * App 却当成"没点名"，退化成"停掉**全部**第三方插件"（真机上用户的 8 个插件
 * 就是这么被一起摘掉的）。所以把它抽出来，用真机日志做回归测试
 * （见 {@code tools/PluginMsgSelfTest.java}）。
 *
 * <h3>只解析、不判断"该不该停"</h3>
 * 这里把原文里点到的包名分成四类，由调用方决定动作：
 * <ul>
 *   <li>{@link Parsed#suspects}：登记在 profile 里、且不是官方/自带的 —— **可以停**；</li>
 *   <li>{@link Parsed#protectedOnes}：点到的恰好是官方/自带插件 —— 停它们没有意义，
 *       停第三方更是白折腾，调用方应当放弃自动自救；</li>
 *   <li>{@link Parsed#unregistered}：点到的名字不在 profile 的 bundle 列表里 ——
 *       它可能写在 {@code cordis.patch.yml} 的 insert 里（App 停不了），
 *       也可能是上一次自动停用摘掉的，都要如实告诉用户；</li>
 *   <li>{@link Parsed#named}：以上三者之和（按原文出现顺序、去重）。</li>
 * </ul>
 *
 * <p>纯字符串逻辑、不碰 Android API，所以能拿日志直接跑。
 */
public final class PluginMsg {

    private PluginMsg() { }

    /** 解析结果。 */
    public static final class Parsed {
        /** 原文里点名到的所有包名（按出现顺序、去重）。 */
        public final List<String> named = new ArrayList<>();
        /** named 中"登记在 profile、且非官方/自带"的 —— 可以停的。 */
        public final List<String> suspects = new ArrayList<>();
        /** named 中恰是官方/自带的。 */
        public final List<String> protectedOnes = new ArrayList<>();
        /** named 中不在 profile 里的（patch 层插件 / 已被摘掉的）。 */
        public final List<String> unregistered = new ArrayList<>();
        /** 文本特征看起来与插件有关（给报告用，不参与"要不要动手"的判断）。 */
        public boolean pluginRelated;

        @Override
        public String toString() {
            return "named=" + named + " suspects=" + suspects
                    + " protected=" + protectedOnes + " unregistered=" + unregistered;
        }
    }

    /**
     * 一行 {@code <包名>: <状态>}。
     *
     * <p>包名那段**不允许空格**，这一点很关键：DSH 的启动失败里还有一句
     * {@code web boot: 1 entry did not activate} —— 它长得也像"名字: 状态"，
     * 但 "web boot" 中间有空格，所以整行不会被当成插件名。
     */
    private static final Pattern ENTRY_LINE = Pattern.compile(
            "(?m)^\\s*(?:[·•*\\-]\\s*)?([@A-Za-z0-9][@A-Za-z0-9._/\\-]*)\\s*[:：]\\s*(.+?)\\s*$");

    /**
     * 后半截像不像"这个插件没起来"。
     *
     * <p>要认的形态（都来自真机日志）：
     * <pre>
     *   dsh-pet: import failed (see console for the import error)
     *   dsh-pet: import failed: Cannot find module 'x'
     *   dsh-speech: pending (waiting for service: settingsScope)
     *   dsh-x: activate failed
     *   dsh-y: failed
     * </pre>
     */
    private static final Pattern FAIL_TAIL = Pattern.compile(
              "^(?:(?:import|activate|load|mount|resolve|dependency)\\s+)?"
            + "(?:failed|error|pending|missing|aborted)\\b"
            + "|^did not activate\\b"
            + "|waiting for service",
            Pattern.CASE_INSENSITIVE);

    /** 一句话看起来是不是与插件有关（报告里记一笔，便于回看）。 */
    private static final Pattern PLUGIN_HINT = Pattern.compile(
            "plugin|activate|pending|waiting for service|failed to load|插件",
            Pattern.CASE_INSENSITIVE);

    /**
     * 解析一份失败原文。
     *
     * @param detail        页面/日志里的原文（null 安全）
     * @param registered    profile 里登记的 bundle 列表（{@link Env#profileBundles}）
     * @param protectedList 官方 + 自带插件名单（{@link Env#safeBundles}）
     */
    public static Parsed parse(String detail, Collection<String> registered,
                               Collection<String> protectedList) {
        Parsed out = new Parsed();
        if (detail == null || detail.isEmpty()) return out;
        String text = detail;
        out.pluginRelated = PLUGIN_HINT.matcher(text).find();

        Set<String> reg = new LinkedHashSet<>(registered == null ? java.util.Collections.emptyList() : registered);
        Set<String> prot = new LinkedHashSet<>(protectedList == null ? java.util.Collections.emptyList() : protectedList);
        Set<String> seen = new LinkedHashSet<>();

        Matcher m = ENTRY_LINE.matcher(text);
        while (m.find()) {
            String name = m.group(1).trim();
            String tail = m.group(2) == null ? "" : m.group(2).trim();
            if (!FAIL_TAIL.matcher(tail).find()) continue;
            if (!seen.add(name)) continue;                  // 同一句出现多次只算一次
            out.named.add(name);
            if (prot.contains(name)) out.protectedOnes.add(name);
            else if (reg.contains(name)) out.suspects.add(name);
            else out.unregistered.add(name);
        }
        return out;
    }

    /** 只用来拼日志/面板文案：不超过 n 个名字，其余用"…"省略。 */
    public static String join(List<String> names, int n) {
        if (names == null || names.isEmpty()) return "（无）";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size() && i < n; i++) {
            if (sb.length() > 0) sb.append('、');
            sb.append(names.get(i));
        }
        if (names.size() > n) sb.append("…（共 ").append(names.size()).append(" 个）");
        return sb.toString();
    }
}
