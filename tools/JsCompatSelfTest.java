import com.dshmobile.probe.JsCompat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * JsCompat（App 里跑的那份 Java 实现）与 tools/rewrite-static-block.mjs
 * （在真实 bundle 上用 V8 验证过的参考实现）**逐字节对账**。
 *
 * 为什么要它：改写发生在用户手机的传输链路上，改坏了就是"界面永远白屏"。
 * 参考实现能在 PC 上用 Node 验证（`node --check` 按 ES module 解析通过），
 * 但真正跑在 App 里的是 Java 那份 —— 两者必须产出一模一样的字节，
 * 否则"验证过的"这句话就不成立。
 *
 * 用法（在仓库根目录）:
 *   javac -encoding UTF-8 -d %TEMP%\jstest tools\JsCompatSelfTest.java ^
 *         DshMobile\app\src\main\java\com\dshmobile\probe\JsCompat.java
 *   java -cp %TEMP%\jstest JsCompatSelfTest tools\dshweb\index.js tools\dshweb\index.rewritten.js
 */
public class JsCompatSelfTest {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: JsCompatSelfTest <原始.js> <参考实现改写后的.js>");
            System.exit(1);
        }
        String src = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
        String ref = new String(Files.readAllBytes(Paths.get(args[1])), StandardCharsets.UTF_8);

        JsCompat.Result r = JsCompat.rewrite(src);
        System.out.println("ambiguous   = " + r.ambiguous);
        System.out.println("count       = " + r.count);
        System.out.println("长度        = " + r.code.length() + "（参考 " + ref.length() + "）");
        boolean same = r.code.equals(ref);
        System.out.println("与参考一致  = " + same);
        if (!same) {
            int n = Math.min(r.code.length(), ref.length());
            int i = 0;
            while (i < n && r.code.charAt(i) == ref.charAt(i)) i++;
            int s = Math.max(0, i - 80);
            int e = Math.min(n, i + 80);
            System.out.println("首个差异 @ " + i);
            System.out.println("  Java: …" + r.code.substring(s, e).replaceAll("\\s+", " ") + "…");
            System.out.println("  参考: …" + ref.substring(s, e).replaceAll("\\s+", " ") + "…");
            System.exit(2);
        }
        System.out.println("✅ Java 实现与参考实现输出完全一致");
    }
}
