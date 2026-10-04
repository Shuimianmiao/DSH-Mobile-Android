package com.dshmobile.probe;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * 「更新日志」页：把 {@link Changelog} 里的记录摊成一屏。
 *
 * <p>为什么值得有一页：用户问"这版改了什么"时，唯一现成的答案是让开发者去翻仓库 ——
 * 而 App 里本来就有全部信息（{@link Changelog}）。装完新版本第一次进界面弹的那次
 * 提示只有当前版本，想往回看就得有这一页。
 */
public class ChangelogActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = Ui.page(this, "更新日志", v -> finish());

        Changelog.Entry cur = Changelog.current();
        TextView sub = Ui.status(this,
                (cur == null ? "" : "当前版本 v" + cur.version + "（第 " + cur.code + " 版）· ")
                + "同签名覆盖安装，不清容器、API Key 与白名单。",
                Ui.SUB);
        root.addView(sub);

        List<Changelog.Entry> all = Changelog.all();
        for (int i = 0; i < all.size(); i++) {
            Changelog.Entry e = all.get(i);
            LinearLayout card = Ui.card(this, "v" + e.version + (i == 0 ? "（当前）" : ""), null);
            for (String item : e.items) {
                card.addView(bullet(item));
            }
            root.addView(card);
        }

        TextView tail = Ui.status(this,
                "\n完整的、带实测记录的技术日志在仓库的 CHANGELOG.md 里。", Ui.SUB);
        root.addView(tail);
    }

    /** 一条更新项：圆点 + 文字（自动换行）。 */
    private View bullet(String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView dot = new TextView(this);
        dot.setText("·");
        dot.setTextSize(13f);
        dot.setTextColor(Ui.SUB);
        dot.setPadding(0, Ui.dp(this, 6), Ui.dp(this, 8), 0);
        row.addView(dot);

        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13f);
        t.setTextColor(Ui.TEXT);
        t.setLineSpacing(Ui.dp(this, 3), 1f);
        t.setPadding(0, Ui.dp(this, 6), 0, 0);
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(t);
        return row;
    }
}
