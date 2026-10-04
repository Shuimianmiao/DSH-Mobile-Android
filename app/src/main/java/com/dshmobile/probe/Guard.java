package com.dshmobile.probe;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用途守卫：**在对话里出现明显违法用途的关键词时，弹一次警告并记进运行日志**。
 *
 * <h3>为什么要有它</h3>
 * 作者发现有人拿这个 App 去做违法的事（代刷课、代签到、批量注册、诈骗话术、
 * 爬取个人信息这类）。这类用途一旦发生，受伤的是别人，而工具作者也会被牵连。
 * 所以这里做一层"看得见的边界"：命中关键词就明确告诉使用者 ——
 * 这个软件不为你做这些事，后果由你自己承担。
 *
 * <h3>它是什么、不是什么（别误解成"监控"）</h3>
 * <ul>
 *   <li>**纯本地**：关键词表在代码里，扫描在手机上做，**不与任何服务器通信**，
 *       不上报、不统计、不记录对话内容 —— 只在你自己的运行日志里留一行"命中了哪条规则"；</li>
 *   <li>**只提醒，不阻止**：App 不会因此拒绝服务或杀掉会话（误判的代价必须小，
 *       而"关键词命中"本身远不足以判定违法）；</li>
 *   <li>**不是安全边界**：它挡不住真心要作恶的人（改关键词、换个说法就绕过了）。
 *       真正的边界写在 {@code 免责声明-第三方插件.md} 和 README 里 —— 那是法律与态度问题，
 *       不是几行正则能解决的。</li>
 * </ul>
 *
 * <h3>为什么放在 App 侧而不是做成 DSH 插件</h3>
 * 放容器里就得跟着内核 API 走（内核升级可能失效，客户端插件还可能让界面起不来 ——
 * 用户已经踩过 `dsh-pet` 那次）。这里的扫描复用前端注入已有的 tick，
 * 不依赖内核任何接口，也不会把界面拖垮。
 */
public final class Guard {

    private Guard() { }

    private static final String K_SHOWN = "guard_shown_at";

    /**
     * 关键词 → 给用户看的那一句（"你这次要做的是…"）。
     *
     * <p>措辞刻意直白：这是要让人当场停一下，不是要写得委婉。
     * 列表只收**用途明确、几乎没有正当场景**的词；模糊的词（比如"自动化""批量"）
     * 一律不收 —— 那会把正常用途也弹一脸，警告就没人看了。
     */
    private static final Map<String, String> RULES = new LinkedHashMap<>();
    static {
        RULES.put("代刷课", "代替他人完成课程学习");
        RULES.put("刷课", "代替他人完成课程学习");
        RULES.put("代刷网课", "代替他人完成课程学习");
        RULES.put("自动签到", "伪造考勤记录");
        RULES.put("自动打卡", "伪造考勤记录");
        RULES.put("代签到", "伪造考勤记录");
        RULES.put("学习通", "代替完成课程/考勤（学校明确认定为学术不端）");
        RULES.put("超星", "代替完成课程/考勤（学校明确认定为学术不端）");
        RULES.put("抢课脚本", "破坏选课秩序");
        RULES.put("代考", "代替他人考试");
        RULES.put("替考", "代替他人考试");
        RULES.put("开盒", "非法获取、公开他人个人信息");
        RULES.put("人肉搜索", "非法获取、公开他人个人信息");
        RULES.put("社工库", "非法获取、买卖个人信息");
        RULES.put("查户籍", "非法获取个人信息");
        RULES.put("身份证号批量", "非法获取、买卖个人信息");
        RULES.put("手机号批量", "非法获取、买卖个人信息");
        RULES.put("爬取个人信息", "非法获取个人信息");
        RULES.put("银行卡四件套", "帮助信息网络犯罪活动（买卖银行卡/账户）");
        RULES.put("跑分", "为赌博/诈骗资金洗钱");
        RULES.put("洗钱", "掩饰、隐瞒犯罪所得");
        RULES.put("诈骗话术", "诈骗");
        RULES.put("诈骗脚本", "诈骗");
        RULES.put("钓鱼网站", "诈骗/非法获取账号");
        RULES.put("盗号", "非法获取他人账号");
        RULES.put("破解付费", "侵犯著作权/非法获取服务");
        RULES.put("外挂", "破坏计算机信息系统");
        RULES.put("赌博引流", "为赌博活动引流");
        RULES.put("色情引流", "传播违法信息");
    }

    /** 给页面用的规则清单（页面侧拿它去扫可见文本）。 */
    public static String patternsJson() {
        JSONArray arr = new JSONArray();
        for (Map.Entry<String, String> e : RULES.entrySet()) {
            try {
                JSONObject o = new JSONObject();
                o.put("k", e.getKey());
                o.put("w", e.getValue());
                arr.put(o);
            } catch (Throwable ignore) { }
        }
        return arr.toString();
    }

    /** 命中的一句话（给对话框用）。 */
    public static String warningText(String hit, String what) {
        return "检测到疑似违规用途：" + hit + "\n\n"
                + "这类需求看起来是要" + (what == null || what.isEmpty() ? "从事法律禁止的事情" : what) + "。\n\n"
                + "Tdsh 只是一个把 AI 跑在手机里的工具，**不为你做这些事**：\n"
                + "· 代刷课、代签到、代考属于学术不端，学校会处分，作者也会被追究；\n"
                + "· 开盒、买卖个人信息、跑分洗钱、诈骗、外挂等是刑事犯罪，不是「违规」；\n"
                + "· 出了事，责任在你，与本项目作者无关。\n\n"
                + "如果你是在做正当的自动化（自己的设备、自己的账号、允许自动化的场景），"
                + "请把需求说得更具体；这条提示只在本地判断，不上传任何内容。";
    }

    /**
     * 是否该弹（同一台手机 12 小时内只弹一次，避免每轮对话都糊一脸）。
     *
     * <p>为什么要限频：关键词是"看起来像"，不是"确实违法"。弹太勤只会让用户
     * 条件反射地点掉，反而失去提醒的意义。
     */
    public static boolean shouldShow(Context c) {
        try {
            long last = c.getSharedPreferences(PhoneBridge.PREFS, Context.MODE_PRIVATE)
                    .getLong(K_SHOWN, 0L);
            return System.currentTimeMillis() - last > 12L * 3600 * 1000;
        } catch (Throwable t) {
            return true;
        }
    }

    public static void markShown(Context c) {
        try {
            c.getSharedPreferences(PhoneBridge.PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(K_SHOWN, System.currentTimeMillis()).apply();
        } catch (Throwable ignore) { }
    }
}
