package com.dshmobile.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 「中转站」页：把中转站 / 自部署网关 / Claude 原生接口接进 DSH。
 *
 * <h3>这一页解决什么</h3>
 * DSH 只在**设置里出现 {@code llm-pi-ai:}` 这一节**时才注册自定义路由
 * （见 {@link Providers} 的类注释，那是逐行查过内核源码的）。手机上改这个文件很不方便：
 * 要进容器、要手写 YAML、还得自己派生凭据名。这一页把整件事变成"填几个框"。
 *
 * <h3>两条承诺（也是页面上写着的）</h3>
 * <ul>
 *   <li><b>密钥只存本机</b>：写进 DSH 的只有 {@code apiKeyEnv: XXX_API_KEY} 这种引用，
 *       密钥留在 App 私有目录，启动容器时作为环境变量注入；</li>
 *   <li><b>不碰别人的设置</b>：只改写 settings.yaml 里的 {@code llm-pi-ai} 一节，
 *       DSH 网页端自己加的提供方原样保留（{@link Providers#mergeSection}）。</li>
 * </ul>
 *
 * <p>界面分两态（列表 / 编辑），靠一个字段切换、不另开 Activity ——
 * 手机上返回键的语义更简单：编辑态按返回 = 取消编辑。
 *
 * <p><b>界面灵感来自「小眼睛」</b>（用户要求在这里标注）。
 */
public class RelayActivity extends Activity {

    private LinearLayout root;
    private boolean editing;
    private Relay.Route draft;
    private boolean creating;
    private TextView statusLine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = Ui.page(this, "中转站", v -> onBackPressed());
        render();
    }

    @Override
    public void onBackPressed() {
        if (editing) {
            editing = false;
            draft = null;
            render();
            return;
        }
        super.onBackPressed();
    }

    // ─────────────────────────── 列表态 ───────────────────────────

    private void render() {
        root.removeAllViews();
        if (editing) renderEditor();
        else renderList();
    }

    private void renderList() {
        root.addView(Ui.card(this, null,
                "连接中转站、自部署服务或其他兼容 OpenAI / Anthropic 协议的接口。"
                + "保存后模型会出现在 DSH 的模型选择器里，选哪个用哪个。"
                + "密钥只存本机（App 私有目录），经环境变量注入容器，不写进配置文件。"));

        List<Relay.Route> list = Providers.load(this);
        for (Relay.Route r : list) {
            LinearLayout card = Ui.card(this, null, null);
            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView t = new TextView(this);
            t.setText(r.name == null || r.name.trim().isEmpty() ? r.id : r.name);
            t.setTextColor(Ui.TEXT);
            t.setTextSize(16);
            col.addView(t);
            TextView d = new TextView(this);
            d.setText(r.apiLabel() + " · " + r.models.size() + " 个模型");
            d.setTextColor(Ui.SUB);
            d.setTextSize(12);
            d.setPadding(0, Ui.dp(this, 3), 0, 0);
            col.addView(d);
            TextView addr = new TextView(this);
            addr.setText(r.baseUrl == null ? "" : r.baseUrl);
            addr.setTextColor(Ui.SUB);
            addr.setTextSize(12);
            addr.setPadding(0, Ui.dp(this, 2), 0, 0);
            col.addView(addr);
            head.addView(col);
            card.addView(head);

            LinearLayout btns = new LinearLayout(this);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            TextView edit = Ui.button(this, "编辑", false, v -> {
                draft = r;
                creating = false;
                editing = true;
                render();
            });
            edit.setLayoutParams(btnLp());
            btns.addView(edit);
            TextView del = Ui.button(this, "删除", false, v -> confirmDelete(r));
            del.setLayoutParams(btnLp());
            btns.addView(del);
            card.addView(btns);
            root.addView(card);
        }

        if (list.isEmpty()) {
            root.addView(Ui.card(this, "还没有添加任何提供方",
                    "点下面的按钮加一个。需要的信息在你买的中转站/网关的「接入说明」里都有："
                    + "接口地址（填到 …/v1）、一个密钥、以及要用的模型名。"));
        }

        TextView add = Ui.button(this, "+ 添加自定义提供商", true, v -> {
            draft = new Relay.Route();
            draft.api = Relay.API_OPENAI_CHAT;
            creating = true;
            editing = true;
            render();
        });
        root.addView(add);

        root.addView(Ui.card(this, "协议说明",
                "OpenAI Chat Completions：适用于绝大多数中转站。\n"
                + "OpenAI Responses：适用于只提供 /v1/responses 的新接口。\n"
                + "Anthropic Messages：适用于 Claude 系原生接口。\n"
                + "接口路径由 DSH 按协议自动拼接，API 地址填到 …/v1 即可。\n"
                + "模型目录里标「文本+图像」的模型才能收图片；不认识的先去掉，"
                + "在对话里直接用模型 ID 也能发（不用非在这里登记）。"));

        /*
         * 生效方式：DSH 是在启动时读设置的，改完必须重启它。
         * 这里给一个一键按钮 —— 否则用户会以为"保存了但没用"。
         */
        TextView restart = Ui.button(this, "重启 DSH 让它生效", false, v -> {
            DshService.requestRestart();
            Toast.makeText(this, "正在重启 DSH，界面会自己回来", Toast.LENGTH_LONG).show();
            appendReport("  [中转站] 用户手动重启 DSH 以加载新的提供方\n");
        });
        root.addView(restart);

        statusLine = Ui.status(this, "", Ui.SUB);
        root.addView(statusLine);

        // 用户要求的署名
        TextView credit = Ui.status(this, "界面灵感来自「小眼睛」", 0xFF6B7076);
        credit.setGravity(Gravity.END);
        root.addView(credit);
    }

    private LinearLayout.LayoutParams btnLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = Ui.dp(this, 8);
        return lp;
    }

    private void confirmDelete(final Relay.Route r) {
        new AlertDialog.Builder(this)
                .setTitle("删除这个提供方？")
                .setMessage((r.name == null || r.name.isEmpty() ? r.id : r.name)
                        + "\n\n会从 DSH 的设置里移除它（模型选择器里不再出现），"
                        + "并忘掉存在本机的密钥。已用它聊过的会话不受影响。")
                .setPositiveButton("删除", (d, w) -> {
                    Providers.remove(this, r.id);
                    String msg = Providers.push(this);
                    toast(msg);
                    render();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ─────────────────────────── 编辑态 ───────────────────────────

    private void renderEditor() {
        final Relay.Route r = draft;

        root.addView(Ui.card(this, creating ? "新建自定义提供商" : "编辑自定义提供商",
                "连接中转站、自部署服务或其他兼容 OpenAI / Anthropic 协议的接口，需填写 API 地址、协议和模型。"));

        // Provider ID
        if (creating) {
            root.addView(field("Provider ID", r.id, "以小写字母开头的标识，在请求中唯一标识该提供商，"
                    + "并用于派生凭据名（如 ACME_GATEWAY_API_KEY）", false, v -> r.id = v.trim()));
        } else {
            root.addView(Ui.card(this, "Provider ID", r.id
                    + "\n\n（已经保存过的提供方不能改名：改名等于换一个提供方，"
                    + "密钥与模型都要重填。要换就删掉重建。）"));
        }

        root.addView(field("显示名称", r.name, "可选，只用于界面展示", false, v -> r.name = v.trim()));
        root.addView(field("API 地址", r.baseUrl, "填到 …/v1 就行（例如 https://gateway.example/v1）",
                false, v -> r.baseUrl = v.trim()));

        // API 协议
        LinearLayout apiCard = Ui.card(this, "API 协议", null);
        root.addView(apiCard);
        apiCard.addView(choice("OpenAI Chat Completions", "绝大多数中转站用这个",
                Relay.API_OPENAI_CHAT.equals(r.api), () -> {
                    r.api = Relay.API_OPENAI_CHAT; render();
                }));
        apiCard.addView(choice("OpenAI Responses", "只提供 /v1/responses 的新接口",
                Relay.API_OPENAI_RESP.equals(r.api), () -> {
                    r.api = Relay.API_OPENAI_RESP; render();
                }));
        apiCard.addView(choice("Anthropic Messages", "Claude 系原生接口",
                Relay.API_ANTHROPIC.equals(r.api), () -> {
                    r.api = Relay.API_ANTHROPIC; render();
                }));

        // 密钥
        root.addView(field("API 密钥", Providers.key(this, r.id),
                "只存本机（App 私有目录），经环境变量注入容器，不写进任何配置文件；"
                + "自部署、免鉴权的服务可以留空", true, v -> Providers.setKey(this, r.id, v.trim())));

        // 模型目录
        LinearLayout models = Ui.card(this, "模型目录",
                "目录为空无法保存；模型选择器里没列出的 ID 也可以在对话里直接发。");
        root.addView(models);
        for (final Relay.Model m : new ArrayList<>(r.models)) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            TextView id = new TextView(this);
            id.setText(m.id);
            id.setTextColor(Ui.TEXT);
            id.setTextSize(15);
            id.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(id);
            TextView tag = Ui.button(this, m.image ? "文本+图像" : "纯文本", false, v -> {
                m.image = !m.image;
                render();
            });
            tag.setLayoutParams(wrapLp());
            row.addView(tag);
            TextView del = Ui.button(this, "✕", false, v -> {
                r.models.remove(m);
                render();
            });
            del.setLayoutParams(wrapLp());
            row.addView(del);
            models.addView(row);
        }
        if (r.models.isEmpty()) {
            models.addView(Ui.status(this, "目录为空 —— 获取可用模型或手动添加", Ui.SUB));
        }

        // 手动添加模型
        final EditText manual = new EditText(this);
        manual.setHint("手动添加模型 ID");
        manual.setTextSize(15);
        manual.setTextColor(Ui.TEXT);
        manual.setHintTextColor(0xFF6B7076);
        manual.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        manual.setBackground(Ui.round(0xFF141416, Ui.STROKE, 8, this));
        models.addView(manual);
        models.addView(Ui.button(this, "+ 添加", false, v -> {
            String id = manual.getText().toString().trim();
            if (id.isEmpty()) return;
            for (Relay.Model m : r.models) if (m.id.equals(id)) return;
            r.models.add(new Relay.Model(id, id, false));
            render();
        }));

        final TextView discoverState = Ui.status(this, "", Ui.SUB);
        models.addView(Ui.button(this, "↻ 获取可用模型", false, v -> {
            if (r.baseUrl == null || r.baseUrl.trim().isEmpty()) {
                discoverState.setText("先填 API 地址");
                return;
            }
            if (r.id == null || r.id.trim().isEmpty()) {
                discoverState.setText("先填 Provider ID（发现模型要用它派生凭据名）");
                return;
            }
            discoverState.setText("正在问这个服务有哪些模型…");
            final String key = Providers.key(this, r.id);
            new Thread(() -> {
                String err = "";
                List<Relay.Model> got = new ArrayList<>();
                try {
                    got = Providers.discover(r, key);
                } catch (Throwable t) {
                    err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                }
                final List<Relay.Model> found = got;
                final String error = err;
                runOnUiThread(() -> {
                    if (!error.isEmpty()) {
                        discoverState.setText("获取失败：" + error);
                        appendReport("  [中转站] 获取模型失败（" + r.id + "）：" + error + "\n");
                        return;
                    }
                    LinkedHashSet<String> have = new LinkedHashSet<>();
                    for (Relay.Model m : r.models) have.add(m.id);
                    int added = 0;
                    for (Relay.Model m : found) {
                        if (have.add(m.id)) { r.models.add(m); added++; }
                    }
                    discoverState.setText("发现 " + found.size() + " 个模型，新加入 " + added
                            + " 个（不认识的可先去掉，支持图像的记得点亮「文本+图像」）");
                    appendReport("  [中转站] " + r.id + " 发现 " + found.size()
                            + " 个模型，新加入 " + added + " 个\n");
                    render();
                });
            }, "relay-discover").start();
        }));
        models.addView(discoverState);

        // 保存 / 取消
        root.addView(Ui.button(this, creating ? "创建提供商" : "保存修改", true, v -> save(r)));
        root.addView(Ui.button(this, "取消", false, v -> {
            editing = false;
            draft = null;
            render();
        }));
    }

    private LinearLayout.LayoutParams wrapLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 8);
        lp.topMargin = 0;
        return lp;
    }

    /** 保存：先本地校验，再写进容器的 settings.yaml（写入有备份/校验/回滚，见 Providers）。 */
    private void save(Relay.Route r) {
        String idErr = Relay.validateId(r.id);
        if (!idErr.isEmpty()) { toast(idErr); return; }
        if (r.baseUrl == null || r.baseUrl.trim().isEmpty()) { toast("API 地址不能为空"); return; }
        if (!r.baseUrl.startsWith("http://") && !r.baseUrl.startsWith("https://")) {
            toast("API 地址要以 http:// 或 https:// 开头");
            return;
        }
        if (r.models.isEmpty()) { toast("模型目录不能为空 —— 先「获取可用模型」或手动加一个"); return; }
        if (r.name == null) r.name = "";

        Providers.upsert(this, r);
        String msg = Providers.push(this);
        appendReport("  [中转站] 保存 " + r.id + "（" + r.models.size() + " 个模型）：" + msg + "\n");
        editing = false;
        draft = null;
        render();
        toast(msg);
    }

    private View field(String title, String value, String hint, boolean secret, final FieldCb cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        box.setPadding(p, Ui.dp(this, 12), p, Ui.dp(this, 12));
        box.setBackground(Ui.round(Ui.CARD, Ui.STROKE, 16, this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 12);
        box.setLayoutParams(lp);
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(Ui.TEXT);
        label.setTextSize(15);
        box.addView(label);
        EditText et = new EditText(this);
        et.setText(value == null ? "" : value);
        et.setTextSize(15);
        et.setTextColor(Ui.TEXT);
        et.setInputType(secret
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        et.setBackground(Ui.round(0xFF141416, Ui.STROKE, 8, this));
        box.addView(et);
        et.setOnFocusChangeListener((v, has) -> { if (!has) cb.on(et.getText().toString()); });
        // 编辑态里也可能直接点"保存"而输入框还没失焦，所以再兜一层
        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) { cb.on(s.toString()); }
        });
        if (hint != null) {
            TextView h = new TextView(this);
            h.setText(hint);
            h.setTextColor(0xFF7F8489);
            h.setTextSize(12);
            h.setPadding(0, Ui.dp(this, 6), 0, 0);
            box.addView(h);
        }
        return box;
    }

    private interface FieldCb { void on(String v); }

    private View choice(String title, String desc, boolean selected, Runnable pick) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 12);
        box.setPadding(p, Ui.dp(this, 10), p, Ui.dp(this, 10));
        box.setBackground(Ui.round(selected ? 0xFF1E3A5F : 0xFF17171A,
                selected ? Ui.ACCENT : Ui.STROKE, 10, this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        box.setLayoutParams(lp);
        TextView t = new TextView(this);
        t.setText((selected ? "● " : "○ ") + title);
        t.setTextColor(Ui.TEXT);
        t.setTextSize(15);
        box.addView(t);
        if (desc != null) {
            TextView d = new TextView(this);
            d.setText(desc);
            d.setTextColor(Ui.SUB);
            d.setTextSize(12);
            d.setPadding(0, Ui.dp(this, 4), 0, 0);
            box.addView(d);
        }
        box.setOnClickListener(v -> pick.run());
        return box;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void appendReport(String line) {
        Env.report(this, line);
    }
}
