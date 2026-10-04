package dsh;

import android.content.Context;
import android.provider.Settings;
import java.io.*;
import java.net.*;
import java.lang.reflect.Method;

/** 以 shell(uid 2000) 身份常驻的最小命令/设置守护（HTTP，仅绑 127.0.0.1，X-Token 门控）。
 *  POST /exec      正文=shell 命令            → 输出（含 [EXIT=n]）
 *  GET  /settings?ns=global&key=K            → 值
 *  POST /settings?ns=global&key=K&value=V    → ok
 *  GET  /health                              → uid/端口
 *
 *  ── 本文件 = 群友 v1.1 源码 + 文档 §7「已知未修三件套」的修复 + token 路径加固 ──
 *   [加固 D] 每请求一个线程（原版 handle() 直接在 accept 循环里串行执行 → 一条卡住的命令永久堵死端点）。
 *   [加固 E] 子进程不继承 CLASSPATH，并给 HOME/PATH 合理值（原版用 Runtime.exec 继承一切；
 *            内部走 app_process 的命令如 pm 会捡到守护自己的 dex → 卡死）。
 *   [加固 F] 看门狗超时连孙进程一起收掉（原版只 destroy() 那个 sh，孙进程握着管道 → 看门狗形同虚设）。
 *   [加固 G] 端口与 token 文件从 args 取，token 默认 /data/local/tmp/dsh-vsd/.shd-token
 *            （原版写死 /sdcard/dsh/.shd-token：共享存储上任何有存储权限的 App 都能读/改，文档 §8）。
 *  其余行为（路由、响应格式、SHD_* 日志行、中文按字节读的修法）逐字照抄。
 */
public final class ShellDaemon {
  static Context ctx;
  static synchronized Context ctx() throws Exception {
    if (ctx == null) {
      Class<?> at = Class.forName("android.app.ActivityThread");
      Object thr = at.getMethod("systemMain").invoke(null);
      Context sys = (Context) at.getMethod("getSystemContext").invoke(thr);
      ctx = sys.createPackageContext("com.android.shell", 0);
      System.out.println("SHD_CTX ok"); System.out.flush();
    }
    return ctx;
  }
  static String token = "none";

  // [加固 G] 默认 token 文件路径（可用 args[1] 覆盖）。
  static final String DEFAULT_TOKEN_FILE = "/data/local/tmp/dsh-vsd/.shd-token";
  // [加固 F] 子进程工作目录：/data/local/tmp 是 shell 可写的最稳目录，避免继承守护的 cwd。
  static final String WORKDIR = "/data/local/tmp";
  // [加固 F] setsid 是否存在（启动时探测一次）。存在 → 子进程自成进程组，超时才能"按组"清干净。
  static volatile boolean HAS_SETSID = false;

  // [加固 D] HTTP 线程池：命令可能跑 20 秒，绝不能占着 accept 线程。
  // 用固定池（不是每请求 new Thread）是因为 /exec 可以起任意吃 CPU 的子进程，
  // 无上限线程会让守护自己被拖死 —— 队列满则 accept 停顿，形成背压。
  static final java.util.concurrent.ExecutorService POOL =
      java.util.concurrent.Executors.newFixedThreadPool(8);

  public static void main(String[] a) throws Exception {
    int port = a.length > 0 ? Integer.parseInt(a[0]) : 18242;
    // [加固 G] token 文件从 args[1] 取（沿用 shell 侧「自动带 token」的调用约定，只改了默认落点）。
    String tokenFile = (a.length > 1 && a[1] != null && a[1].trim().length() > 0) ? a[1].trim() : DEFAULT_TOKEN_FILE;
    System.out.println("SHD_BOOT pid=" + android.os.Process.myPid() + " uid=" + android.os.Process.myUid());
    System.out.flush();
    try {
      BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(tokenFile), "UTF-8"));
      String l = r.readLine(); r.close();
      if (l != null && l.trim().length() > 8) token = l.trim();
    } catch (Throwable t) { System.out.println("SHD_TOKEN_ERR " + t); }
    System.out.println("SHD_TOKEN " + (token.length() > 8 ? "ok" : "none"));
    System.out.flush();
    // [加固 F] 探测 setsid：Android 6+ 的 toybox 自带 /system/bin/setsid。
    // 有它 → 子进程 execute 在自己的会话/进程组里，看门狗超时才能一次带走整棵进程树；
    // 没有 → 退回原版写法（只 kill 到 sh 自己），并在启动日志里写明，避免"以为收干净了"。
    try { HAS_SETSID = new File("/system/bin/setsid").exists(); } catch (Throwable t) { HAS_SETSID = false; }
    System.out.println("SHD_SETSID " + (HAS_SETSID ? "yes" : "no"));
    System.out.flush();
    ServerSocket ss = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"));
    System.out.println("SHD_READY port=" + port + " uid=" + android.os.Process.myUid());
    System.out.flush();
    while (true) {
      final Socket s = ss.accept();
      // [加固 D] accept 只负责收连接，处理体丢线程池 —— 原来 handle(s) 在这里串行执行，
      // 一条 `sleep 999` 或一个卡住的命令就让整个 :18242 永久不可用（文档 §7 缺陷①）。
      try { POOL.execute(new Runnable() { public void run() {
        try { handle(s); } catch (Throwable t) {} finally { try { s.close(); } catch (Exception e) {} }
      } }); } catch (Throwable t) { try { s.close(); } catch (Exception e) {} }
    }
  }

  static void handle(Socket s) throws Exception {
    s.setSoTimeout(30000);
    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
    String req = in.readLine();
    if (req == null) return;
    String[] p = req.split(" ");
    String method = p[0], path = p.length > 1 ? p[1] : "/";
    int len = 0; String tok = null, line;
    while ((line = in.readLine()) != null && line.length() > 0) {
      String low = line.toLowerCase();
      if (low.startsWith("content-length:")) len = Integer.parseInt(line.substring(15).trim());
      if (low.startsWith("x-token:")) tok = line.substring(8).trim();
    }
    // ⚠️ Content-Length 是【字节数】，不能拿它当【字符数】去读：
    // 中文 1 字符 = 3 字节 → 声明的字节数永远大于会到达的字符数 → 读循环死等 → 挂到 30s 超时。
    // 按字节记账：逐字符读，累加该字符的 UTF-8 字节长度，读满 len 字节为止。
    StringBuilder bs = new StringBuilder();
    int bytes = 0;
    while (bytes < len) {
      int c = in.read();
      if (c < 0) break;
      char ch = (char) c;
      bs.append(ch);
      if (ch < 0x80) bytes += 1;
      else if (ch < 0x800) bytes += 2;
      else if (Character.isHighSurrogate(ch)) { /* 4 字节字符的高位，等低位一起计 */ }
      else if (Character.isLowSurrogate(ch)) bytes += 4;
      else bytes += 3;
    }
    String bodyStr = bs.toString();
    String out; int code = 200;
    if (!token.equals(tok)) { out = "DENIED\n"; code = 403; }
    else if (path.startsWith("/health")) out = "uid=" + android.os.Process.myUid() + " ok\n";
    else if (path.startsWith("/exec")) out = exec(bodyStr);
    else if (path.startsWith("/settings")) out = settings(path, bodyStr);
    else { out = "not found\n"; code = 404; }
    byte[] b = out.getBytes("UTF-8");
    OutputStream o = s.getOutputStream();
    o.write(("HTTP/1.1 " + code + " OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
    o.write(b); o.flush();
  }

  /** [加固 E] 建子进程：不继承 CLASSPATH + 补 HOME/PATH。
   *  为什么必须去掉 CLASSPATH：守护自己是被 `CLASSPATH=<dex> app_process …` 拉起来的，
   *  这个环境变量会原样传给子命令；`pm`/`am` 这类命令内部又是一层 app_process，
   *  它会拿 CLASSPATH 当自己的 dex → 加载到守护 dex → 起不来/卡死（文档 §7 缺陷②）。
   *  为什么要显式给 PATH/HOME：少了 PATH，`sh -c` 里的裸命令名找不到；HOME 缺失时部分工具会去
   *  写一个不存在的主目录。两者都给成 shell 身份的常规值，行为与 adb shell 一致。
   */
  static Process build(String cmd) throws Exception {
    // [加固 F] 用 setsid 让子进程自成一个会话/进程组（有 setsid 时）：
    // 这样才能在超时时用 `kill -9 -<pgid>` 一次带走 sh 及其所有孙进程。
    // 顺带让最内层 sh 把【自己的进程组 id】作为第一条 stdout 打回来 —— 这是拿 pgid 最可靠的
    // 办法（理由见 pidOf 注释：本机 Process.pid() 返回 0）。
    //
    // ⚠️ 实测踩到的坑（勿改回 printf）：最早写成 `printf '\037DSHPGID=%d\n' $$`，
    // 但这条命令要穿过「Java → ProcessBuilder 的 /system/bin/sh -c（无引号形式）→ exec setsid →
    // 内层 sh -c '<单引号包裹>'」共 3 层 shell 解析，\037 被吃掉成字面 "037"、\n 变成 "n"，
    // 实测子 shell 吐的是 `037DSHPGID=27055nGRANDCHILD=27060`（见 README 实测记录）。
    // 现在改成「变量拼串 + echo」：全程没有内嵌引号、没有转义序列，三层解析后字节稳定
    // （实测 `44 53 48 50 47 49 44 3d ... 0a` = "DSHPGID=<pid>\n"，正好在行首）。
    String body = "echo " + PGID_MARK + "$$; " + cmd;
    String full = HAS_SETSID ? ("exec setsid /system/bin/sh -c " + shq(body)) : body;
    ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", full);
    pb.directory(new File(WORKDIR));
    pb.environment().remove("CLASSPATH");
    pb.environment().put("PATH", "/sbin:/system/sbin:/system/bin:/system/xbin");
    pb.environment().put("HOME", "/data/local/tmp");
    return pb.start();
  }

  /** 单引号包裹，供 `sh -c '...'` 里安全嵌入原命令（命令里的单引号按 POSIX 惯例转义）。 */
  static String shq(String s) {
    return "'" + s.replace("'", "'\\''") + "'";
  }

  /** 进程组 id 标记（纯 ASCII，无转义）：子 shell 第一条输出就是 `DSHPGID=<pgid>`。 */
  static final String PGID_MARK = "DSHPGID=";

  /**
   * 取 pgid 的"兜底"办法：反射 Process 的 pid。
   *
   * ⚠️ 实测（小米 PD2408 / Android 15，见 README「实测踩到的坑」）：
   *   API 24+ 的 `Process.pid()` 在本机 app_process 环境里返回 **0**，
   *   反射 `java.lang.UNIXProcess.pid` 也拿不到 → 于是 `kill -9 -<pid>` 变成 `kill -9 -0`，
   *   代码里 `pid > 0` 的判断直接跳过 → **进程组永远收不掉，看门狗形同虚设**。
   *   这就是为什么主路径改成由子 shell 自己汇报 pgid（PGID_MARK），反射只当兜底。
   */
  static long pidOf(Process pr) {
    try { Method m = Process.class.getMethod("pid"); Object v = m.invoke(pr); if (v instanceof Integer) return ((Integer) v).longValue(); }
    catch (Throwable t) {}
    try { java.lang.reflect.Field f = Process.class.getDeclaredField("pid"); f.setAccessible(true); Object v = f.get(pr); if (v instanceof Integer) return ((Integer) v).longValue(); }
    catch (Throwable t) {}
    return 0L;
  }

  /**
   * [加固 F] 超时清理：先 destroy()，再 `kill -9 -<pgid>` 收掉整个进程组。
   *
   * **为什么用负号**：`kill -9 -PGID` 的语义是"把信号发给该进程组里的每一个进程"，
   * 正号只能杀单个 pid。原来只 destroy() 那个 sh，孙进程（`sleep 30`、`pm` 里的 app_process 等）
   * 不会被回收，它们仍握着 stdout/stderr 管道 → drain 线程读不到 EOF → 看门狗等于没生效
   * （文档 §7 缺陷③）。前提是子进程得是进程组组长 —— 这是 setsid 干的活。
   *
   * pgid 来源两路：① 子 shell 自己汇报的 PGID_MARK（主路径，实测可靠）；
   * ② 反射 Process.pid()（兜底，实测本机返回 0 → 不可用）。pgid<=0 时绝不执行负号 kill：
   * `kill -9 -0` 会杀掉"调用者自己的进程组"，也就是把守护自己连同 HTTP 线程一起带走。
   */
  static void killTree(Process pr, long[] pgidBox) {
    try { pr.destroy(); } catch (Throwable t) {}
    // pgid 由 drain 线程解析标记后写进这个数组；正常情况下早就有了，
    // 但"刚起就被超时"的窗口不能靠运气 —— 最多再等 3 秒（每 50ms 看一次）。
    long pgid = 0L;
    for (int i = 0; i < 60; i++) {
      pgid = pgidBox != null ? pgidBox[0] : 0L;
      if (pgid > 0) break;
      try { Thread.sleep(50L); } catch (InterruptedException ie) { break; }
    }
    // 兜底：标记始终没来（例如 shell 还没跑到 printf 就被 destroy 了）→ 试反射拿 pid。
    if (pgid <= 0) pgid = pidOf(pr);
    if (HAS_SETSID && pgid > 0) {
      // 用独立 sh 执行：kill 是 toybox sh 的内建，不依赖 /system/bin/kill 是否存在。
      try {
        Process k = new ProcessBuilder("/system/bin/sh", "-c", "kill -9 -" + pgid).redirectErrorStream(true).start();
        k.waitFor();
      } catch (Throwable t) {}
    }
    try {
      Method dm = Process.class.getMethod("destroyForcibly");
      dm.invoke(pr);
    } catch (Throwable t) {
      try { pr.destroy(); } catch (Throwable t2) {}
    }
  }

  static String exec(String cmd) throws Exception {
    if (cmd == null || cmd.trim().isEmpty()) return "[EXIT=2] empty\n";
    // 每条命令独立一个 pgid 容器；drain 线程写、看门狗线程读（同一对象，无需静态通道）。
    final long[] pgid = {0L};
    Process pr = build(cmd);   // [加固 E/F] ProcessBuilder + 去 CLASSPATH + setsid + pgid 汇报
    final StringBuilder sb = new StringBuilder();
    Thread t1 = drain(pr.getInputStream(), sb, pgid), t2 = drain(pr.getErrorStream(), sb, null);
    // ⚠️ 别轮询：旧实现每 40ms 轮询 exitValue()，等于给每条命令强加约 40ms 税
    // （实测 /exec "echo hi" 恒为 46~50ms，抖动几乎为 0，就是这个 40ms 的形状）。
    // 改成 waitFor() 阻塞 + 看门狗线程保留 20s 超时。
    final Process fpr = pr; final boolean[] killed = {false};
    Thread watchdog = new Thread(new Runnable() { public void run() {
      try { Thread.sleep(20000L); killed[0] = true; killTree(fpr, pgid); } catch (Throwable t) {}
    }});
    watchdog.setDaemon(true); watchdog.start();
    boolean done = false;
    try { pr.waitFor(); done = true; } catch (InterruptedException ie) {}
    watchdog.interrupt();
    if (killed[0]) sb.append("[TIMEOUT]\n");
    else if (!done) { killTree(pr, pgid); }
    t1.join(1500); t2.join(1500);
    sb.append("[EXIT=").append(killed[0] ? -9 : (done ? pr.exitValue() : -9)).append("]\n");
    return sb.toString();
  }

  /**
   * 排空子进程输出（按【字节】），顺带在【行首】识别 pgid 标记行并把值写回 pgidOut。
   *
   * ⚠️ 两个必须按字节做的理由（都是实测踩出来的，别改回按字符/按行读）：
   *  1) **中文**：早先的实现 `sb.append((char) c)` 是"一个字节当一个 char"，
   *     UTF-8 的「中」= E4 B8 AD 会被拆成 3 个 char → 输出变成「ä¸­」这种乱码
   *     （实测 `echo 中文测试` 返回 `DSHPGID=ä¸ææµè¯`）。正确做法是把原始字节先收进
   *     ByteArrayOutputStream，流结束后整体按 UTF-8 解码 —— 一个字节都不提前解释。
   *  2) **标记**：pgid 标记本身是 ASCII，按字节比对即可，用不着先解码。
   *
   * 为什么要有状态机而不是 contains：用户命令输出里可能恰好出现同样的文本，
   * 只有"恰好出现在行首"才算标记，而且只认第一条（子 shell 启动时打的那条）。
   */
  static Thread drain(final InputStream is, final StringBuilder sb, final long[] pgidOut) {
    Thread t = new Thread(new Runnable() { public void run() {
      final byte[] mark = PGID_MARK.getBytes();
      final int S_TEXT = 0, S_MARK = 1, S_DIGITS = 2;
      int state = S_TEXT, match = 0, digits = 0;
      boolean solved = (pgidOut == null);
      ByteArrayOutputStream raw = new ByteArrayOutputStream();
      try {
        int c;
        while ((c = is.read()) >= 0) {
          switch (state) {
            case S_TEXT:
              if (!solved && c == (mark[0] & 0xFF)) { state = S_MARK; match = 1; }
              break;
            case S_MARK:
              if (match < mark.length && c == (mark[match] & 0xFF)) {
                match++;
                if (match == mark.length) state = S_DIGITS;
              } else {
                // 误匹配：把暂存的这几个字节原样补回输出，绝不吞用户数据。
                raw.write(mark, 0, match);
                state = S_TEXT; match = 0;
              }
              break;
            case S_DIGITS:
              if (c >= '0' && c <= '9') { digits = digits * 10 + (c - '0'); continue; }
              // 数字段结束：记下 pgid，并丢掉结束标记的那个字节（换行）—— 整行标记都不进输出。
              pgidOut[0] = digits; solved = true; state = S_TEXT; match = 0; digits = 0;
              continue;
          }
          if (state == S_TEXT) raw.write(c);
        }
        // 流恰好断在误匹配中间时，把暂存字节补回去（否则会丢用户输出）。
        if (state == S_MARK && match > 0 && match < mark.length) raw.write(mark, 0, match);
      } catch (Throwable e) {}
      try { sb.append(new String(raw.toByteArray(), "UTF-8")); } catch (Throwable e) {}
    }});
    t.start(); return t;
  }

  static String settings(String path, String body) throws Exception {
    String ns = q(path, "ns"), key = q(path, "key"), val = q(path, "value");
    if (key == null || key.isEmpty()) return "need key\n";
    if ("global".equals(ns)) {
      if (val == null) return String.valueOf(Settings.Global.getString(ctx().getContentResolver(), key)) + "\n";
      Settings.Global.putString(ctx().getContentResolver(), key, val); return "ok\n";
    } else if ("secure".equals(ns)) {
      if (val == null) return String.valueOf(Settings.Secure.getString(ctx().getContentResolver(), key)) + "\n";
      Settings.Secure.putString(ctx().getContentResolver(), key, val); return "ok\n";
    } else {
      if (val == null) return String.valueOf(Settings.System.getString(ctx().getContentResolver(), key)) + "\n";
      Settings.System.putString(ctx().getContentResolver(), key, val); return "ok\n";
    }
  }

  static String q(String path, String k) {
    int i = path.indexOf('?'); if (i < 0) return null;
    for (String kv : path.substring(i + 1).split("&")) {
      int e = kv.indexOf('=');
      if (e > 0 && kv.substring(0, e).equals(k)) {
        try { return URLDecoder.decode(kv.substring(e + 1), "UTF-8"); } catch (Exception x) { return kv.substring(e + 1); }
      }
    }
    return null;
  }
}
