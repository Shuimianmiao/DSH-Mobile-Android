package dsh;
// DSH 可信虚拟屏守护（以 shell 身份运行 → 持有 ADD_TRUSTED_DISPLAY → 屏幕带 FLAG_TRUSTED → 支持窗口容器）
// 与 App 版 VsService 保持同一套 HTTP API：/list  /<i>/info  /<i>/frame[?w=]  POST /resize  POST /launch
//
// ── 本文件 = 群友 v1.1 源码 + 3 处加固（其余逻辑逐字照抄，见 README「加固点」） ──
//   [加固 A] 绑回环 + 每请求 X-Token 鉴权（原版 new ServerSocket(PORT) = 绑所有网卡 + 零鉴权，
//            文档 §8 点名：局域网内任何设备都能建屏/取帧/上屏，等于把 shell 权限的取屏面公开出去）。
//   [加固 B] 每请求一个线程（原版单线程 accept 循环：一条慢请求/慢客户端就把整个端点堵死，
//            文档 §7 记为已知缺陷——实测 9 连接堆队列、8 个 CLOSE_WAIT、十几分钟不自愈）。
//   [加固 C] 端口与 token 文件从 main(String[] args) 取（token 默认放到 /data/local/tmp/dsh-vsd/.shd-token，
//            不再用共享存储 /sdcard：文档 §8 说 /sdcard 上任何有存储权限的 App 都能读/改 token）。
// 启动方式沿用：CLASSPATH=<dex> app_process /system/bin dsh.VsDaemon <port> [tokenFile]
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VsDaemon {
  static final String TAG = "DSH-TRUSTED";
  static int PORT = 18241;   // 可由命令行参数覆盖（app_process 传参）
  static final int TRUSTED = 1 << 10, TOUCH = 1 << 6, OWNFOCUS = 1 << 14, OWN_CONTENT = 1 << 3;
  // 默认用【同 dp 降分辨率】：像素 1/4，App 逻辑布局不变（`/res` 可切档）
  static final int[][] SPEC = {{636,1400,280},{1400,636,280}};
  static final int[][] BASE  = {{1272,2800,560},{2800,1272,560}};   // 全分辨率基准，供 /res 计算

  // [加固 C] token 文件路径（可从 args[1] 覆盖）。默认放 /data/local/tmp/dsh-vsd/：
  // app 私有数据区对 shell 可见、其他 App 不可解析；而 /sdcard 是共享存储，任何拿到存储权限的
  // App 都能读走 token 甚至改掉它（文档 §8），等于把鉴权做成摆设。
  static final String DEFAULT_TOKEN_FILE = "/data/local/tmp/dsh-vsd/.shd-token";
  // [加固 A] 这里必须是 volatile：启动线程写完、HTTP 请求线程读，靠它做可见性保证。
  static volatile String TOKEN = null;
  // [加固 A] token 读不到时置 true → 按「永远 403」处理（fail-closed）。
  // 为什么不 fail-open（没 token 就放行）：那等于把原版的洞原样留着，加固毫无意义。
  static volatile boolean DENY_ALL = false;

  static class Slot {
    VirtualDisplay vd; ImageReader reader; int id=-1, w, h, dpi;
    volatile int frames = 0; byte[] jpeg; int jpegFrame = -1, jpegW = -1;
    final Object lock = new Object(); Image held = null;
  }
  static Slot[] slots = new Slot[SPEC.length];
  static HandlerThread ht;

  static Context shellCtx() throws Exception {
    Class<?> at = Class.forName("android.app.ActivityThread");
    Object thr = at.getMethod("systemMain").invoke(null);
    Context sys = (Context) at.getMethod("getSystemContext").invoke(thr);
    return sys.createPackageContext("com.android.shell", 0);
  }

  static void attach(final Slot s, ImageReader r) {
    r.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
      public void onImageAvailable(ImageReader rr) {
        Image img = null;
        try {
          img = rr.acquireLatestImage();
          if (img == null) return;
          Image old;
          synchronized (s.lock) { old = s.held; s.held = img; s.frames++; s.jpeg = null; }
          if (old != null) { try { old.close(); } catch (Throwable t) {} }
        } catch (Throwable t) {
          Log.w(TAG, "frame", t);
          if (img != null) { try { img.close(); } catch (Throwable t2) {} }
        }
      }
    }, new Handler(ht.getLooper()));
  }

  static byte[] jpeg(Slot s, int maxW) {
    synchronized (s.lock) {
      if (s.held == null) return null;
      if (s.jpeg != null && s.jpegFrame == s.frames && s.jpegW == maxW) return s.jpeg;
      Image img = s.held;
      int w = img.getWidth(), h = img.getHeight();
      Image.Plane pl = img.getPlanes()[0];
      ByteBuffer buf = pl.getBuffer();
      int rs = pl.getRowStride(), ps = pl.getPixelStride();
      Bitmap bmp = null, out = null;
      try {
        if (ps == 4 && rs == w * 4) {
          bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
          buf.rewind();
          bmp.copyPixelsFromBuffer(buf);
        } else {
          int[] px = new int[w * h];
          for (int y = 0; y < h; y++) { int b = y * rs;
            for (int x = 0; x < w; x++) { int o = b + x * ps;
              px[y * w + x] = (0xFF << 24) | ((buf.get(o) & 0xFF) << 16) | ((buf.get(o + 1) & 0xFF) << 8) | (buf.get(o + 2) & 0xFF); } }
          bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        }
        out = bmp;
        if (maxW > 0 && w > maxW) { int nh = (int) ((long) h * maxW / w); out = Bitmap.createScaledBitmap(bmp, maxW, nh, true); }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.JPEG, 70, bo);
        s.jpeg = bo.toByteArray(); s.jpegFrame = s.frames; s.jpegW = maxW;
        return s.jpeg;
      } catch (Throwable t) { Log.w(TAG, "jpeg", t); return null; }
      finally { if (out != null && out != bmp) out.recycle(); if (bmp != null) bmp.recycle(); }
    }
  }

  static String listJson() {
    StringBuilder b = new StringBuilder("[");
    for (int i = 0; i < slots.length; i++) { Slot s = slots[i]; if (s == null) continue;
      if (b.length() > 1) b.append(",");
      b.append("{\"i\":").append(i).append(",\"displayId\":").append(s.id)
       .append(",\"width\":").append(s.w).append(",\"height\":").append(s.h)
       .append(",\"frames\":").append(s.frames).append(",\"trusted\":true}"); }
    return b.append("]").toString();
  }
  static String js(String b, String k) {
    try { java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + k + "\"\\s*:\\s*\"([^\"]*)\"").matcher(b);
      return m.find() ? m.group(1) : null; } catch (Throwable t) { return null; }
  }
  static int jn(String b, String k, int def) {
    try { java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + k + "\"\\s*:\\s*(-?[0-9]+)").matcher(b);
      return m.find() ? Integer.parseInt(m.group(1)) : def; } catch (Throwable t) { return def; }
  }

  /** POST /res {i, level} —— 按"同 dp"档位切换：full|half|quarter（布局不变，只改像素） */
  static byte[] doRes(String body) {
    try {
      int i = jn(body, "i", 0); String lv = js(body, "level");
      if (i < 0 || i >= slots.length) return "{\"ok\":false,\"error\":\"bad i\"}".getBytes();
      if (lv == null) lv = "half";
      int div = "full".equals(lv) ? 1 : ("quarter".equals(lv) ? 4 : 2);
      int w = BASE[i][0] / div, h = BASE[i][1] / div, dpi = BASE[i][2] / div;
      String b = "{\"i\":" + i + ",\"width\":" + w + ",\"height\":" + h + ",\"dpi\":" + dpi + "}";
      return doResize(b, null);
    } catch (Throwable t) { return ("{\"ok\":false,\"error\":\"" + t + "\"}").getBytes(); }
  }

  static byte[] doResize(String body, Context ctx) {
    try {
      int i = jn(body, "i", -1), w = jn(body, "width", 0), h = jn(body, "height", 0), dpi = jn(body, "dpi", 0);
      if (i < 0 || i >= slots.length) return "{\"ok\":false,\"error\":\"bad i\"}".getBytes();
      Slot s = slots[i]; if (s == null) return "{\"ok\":false,\"error\":\"no slot\"}".getBytes();
      if (w <= 0 || h <= 0) return "{\"ok\":false,\"error\":\"w/h required\"}".getBytes();
      if (dpi <= 0) dpi = s.dpi;
      ImageReader nr = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
      attach(s, nr);
      ImageReader old = s.reader;
      synchronized (s.lock) { if (s.held != null) { try { s.held.close(); } catch (Throwable t) {} s.held = null; } s.jpeg = null; }
      s.reader = nr; s.w = w; s.h = h; s.dpi = dpi;
      s.vd.setSurface(nr.getSurface());
      s.vd.resize(w, h, dpi);
      if (old != null) { try { Thread.sleep(200); old.close(); } catch (Throwable t) {} }
      return ("{\"ok\":true,\"i\":" + i + ",\"displayId\":" + s.id + ",\"width\":" + w + ",\"height\":" + h + ",\"dpi\":" + dpi + "}").getBytes();
    } catch (Throwable t) { return ("{\"ok\":false,\"error\":\"" + t + "\"}").getBytes(); }
  }

  static byte[] doLaunch(String body, Context ctx) {
    try {
      String pkg = js(body, "pkg"), act = js(body, "act");
      // ⛔ 宿主 App 永不搬上虚拟屏：跨屏 = 配置变更 = MainActivity 重建 = 界面重启
      if ("com.dshmobile.probe".equals(pkg))
        return "{\"ok\":false,\"error\":\"refused: host app must never be launched on a virtual display\"}".getBytes();
      int i = jn(body, "i", 0), wm = jn(body, "wm", 5);   // 默认 freeform(5)
      if (i < 0 || i >= slots.length) i = 0;
      Slot s = slots[i]; if (s == null) return "{\"ok\":false,\"error\":\"no slot\"}".getBytes();
      Intent it = new Intent();
      if (act != null && act.length() > 0) it.setComponent(new ComponentName(pkg, act.charAt(0) == '.' ? pkg + act : act));
      else { it.setPackage(pkg); it.setAction(Intent.ACTION_MAIN); it.addCategory(Intent.CATEGORY_LAUNCHER); }
      it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
      ActivityOptions op = ActivityOptions.makeBasic();
      try { ActivityOptions.class.getMethod("setLaunchDisplayId", int.class).invoke(op, Integer.valueOf(s.id)); } catch (Throwable t) {}
      try { ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class).invoke(op, Integer.valueOf(wm)); } catch (Throwable t) {}
      try { ctx.startActivity(it, op.toBundle()); }
      catch (SecurityException se) {
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, it, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE, op.toBundle());
        pi.send();
      }
      return ("{\"ok\":true,\"pkg\":\"" + pkg + "\",\"i\":" + i + ",\"displayId\":" + s.id + ",\"wm\":" + wm + "}").getBytes("UTF-8");
    } catch (Throwable t) { return ("{\"ok\":false,\"error\":\"" + t + "\"}").getBytes(); }
  }

  // ─────────────────────────── [加固 A/C] token ───────────────────────────
  /**
   * 启动时读一次 token 文件，读不到就 DENY_ALL。
   * 为什么不在每个请求里读文件：① 每请求一次磁盘 IO 没必要；② 换 token 的语义就是重启守护。
   * 为什么不接受空 token：`token.equals(tok)` 在两侧都是 null 时会返回 true，
   * 那会让"没配 token"变成"无 token 放行"——原版的洞又回来了，所以显式 DENY_ALL。
   */
  static void loadToken(String file) {
    try {
      BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
      String l = r.readLine(); r.close();
      if (l != null) l = l.trim();
      if (l != null && l.length() > 8) {
        TOKEN = l; DENY_ALL = false;
        System.out.println("VSD_TOKEN ok file=" + file + " len=" + l.length());
      } else {
        DENY_ALL = true;
        System.out.println("VSD_TOKEN bad file=" + file + " -> DENY ALL (403)");
      }
    } catch (Throwable t) {
      DENY_ALL = true;
      System.out.println("VSD_TOKEN_ERR " + t + " file=" + file + " -> DENY ALL (403)");
    }
    System.out.flush();
  }

  /** 取请求头里的 X-Token；没有就返回 null（null 永远不等于已配置 token → 403）。 */
  static String headerToken(String headerLine) {
    if (headerLine == null) return null;
    String low = headerLine.toLowerCase();
    if (low.startsWith("x-token:")) return headerLine.substring(8).trim();
    return null;
  }

  /**
   * 单条连接的处理体（原版 accept 循环里的那段，逐字照抄；只在入口加了鉴权判断）。
   * [加固 B] 这个方法由线程池里的线程执行——它内部会 sleep（/resize 里 200ms）、会做 JPEG 编码，
   * 在原版里这些都跑在 accept 线程上，所以一条慢请求 = 整个端点停摆。
   */
  static void handle(Socket sock, final Context ctx) {
    try {
      // 慢客户端保护：线程化之后一条卡住的连接只占一个线程，但也不该永远占着（原版没有超时）。
      sock.setSoTimeout(30000);
      BufferedReader br = new BufferedReader(new InputStreamReader(sock.getInputStream()));
      String line = br.readLine();
      String path = (line == null || line.length() < 5) ? "/" : line.split(" ")[1];
      String method = (line == null || line.length() < 3) ? "GET" : line.split(" ")[0].toUpperCase();
      // [加固 A] 头必须整体读完才能 dispatch：X-Token 在头里，而原版只在 POST 分支读头，
      // GET 请求的头会留在缓冲里（无鉴权时无所谓，有鉴权就必须先读）。
      int clen = 0; String reqTok = null, hd;
      while ((hd = br.readLine()) != null && hd.length() > 0) {
        // 解析规则与原版逐字一致（含 15 这个偏移量），只是现在 GET 也会读头——因为 X-Token 在头里。
        if (hd.toLowerCase().startsWith("content-length:")) clen = Integer.parseInt(hd.substring(15).trim());
        String t = headerToken(hd);
        if (t != null) reqTok = t;
      }
      String bodyText = "";
      if ("POST".equals(method)) {
        // 原样照抄：POST body 按【字符数】读（本端点的 body 是 ASCII JSON，中文不经过这条路）。
        if (clen > 0 && clen < 200000) { char[] cb = new char[clen]; int g = 0;
          while (g < clen) { int r = br.read(cb, g, clen - g); if (r < 0) break; g += r; }
          bodyText = new String(cb, 0, g); }
      }
      // [加固 A] 鉴权闸门：放在任何业务分支之前，不通过就只回 403 + "DENIED"，
      // 不回任何槽位/帧/displayId 数据（错误信息也不回显 token，避免把正确 token 泄露到日志/响应里）。
      if (DENY_ALL || TOKEN == null || !TOKEN.equals(reqTok)) {
        byte[] d = "DENIED".getBytes("UTF-8");
        OutputStream o = sock.getOutputStream();
        o.write(("HTTP/1.1 403 Forbidden\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + d.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        o.write(d); o.flush();
        return;
      }
      int idx = -1; String sub = path;
      if (path.length() > 2 && path.charAt(1) >= '0' && path.charAt(1) <= '9') {
        int k = path.indexOf('/', 1); if (k < 0) k = path.length();
        idx = Integer.parseInt(path.substring(1, k));
        sub = k < path.length() ? path.substring(k) : "/";
      }
      byte[] body; String ct;
      if (path.startsWith("/launch")) { body = doLaunch(bodyText, ctx); ct = "application/json"; }
      else if (path.startsWith("/res")) { body = doRes(bodyText); ct = "application/json"; }
      else if (path.startsWith("/resize")) { body = doResize(bodyText, ctx); ct = "application/json"; }
      else if (path.startsWith("/list")) { body = listJson().getBytes("UTF-8"); ct = "application/json"; }
      else if (idx < 0 || idx >= slots.length || slots[idx] == null) { body = "bad index".getBytes("UTF-8"); ct = "text/plain"; }
      else if (sub.startsWith("/info")) {
        Slot s = slots[idx];
        body = ("{\"i\":" + idx + ",\"displayId\":" + s.id + ",\"width\":" + s.w + ",\"height\":" + s.h + ",\"frames\":" + s.frames + ",\"trusted\":true}").getBytes("UTF-8");
        ct = "application/json"; }
      else if (sub.startsWith("/frame")) {
        int maxW = 0; int qi = sub.indexOf("w=");
        if (qi >= 0) { try { maxW = Integer.parseInt(sub.substring(qi + 2).replaceAll("[^0-9].*$", "")); } catch (Throwable t) {} }
        byte[] l = jpeg(slots[idx], maxW);
        if (l == null) { body = "no-frame-yet".getBytes("UTF-8"); ct = "text/plain"; } else { body = l; ct = "image/jpeg"; } }
      else { body = ("/list  /<i>/info  /<i>/frame[?w=N]  POST /resize  POST /launch   (i=0.." + (slots.length - 1) + ")").getBytes("UTF-8"); ct = "text/plain"; }
      OutputStream o = sock.getOutputStream();
      o.write(("HTTP/1.1 200 OK\r\nContent-Type: " + ct + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
      o.write(body); o.flush();
    } catch (Throwable t) { Log.w(TAG, "req", t); }
    finally { try { sock.close(); } catch (Throwable t) {} }
  }

  // ─────────────────────── [加固 A/B] HTTP 服务端 ───────────────────────
  static void serve(final Context ctx) {
    new Thread(new Runnable() { public void run() {
      // [加固 B] 固定线程池而不是"每请求 new Thread"：帧编码是 CPU/内存密集的，
      // 无上限地开线程会在被外部打请求时把守护自己拖垮（守护一死 = 屏上应用全没）。
      // 8 个并发足够（客户端是单容器里的几个脚本），队列满了会让 accept 停顿 —— 这是我们要的背压。
      final ExecutorService pool = Executors.newFixedThreadPool(8);
      try {
        // [加固 A] 三个参数一个都不能少：backlog=50、绑 127.0.0.1。
        // 原来是 new ServerSocket(PORT) → 监听 [::]:PORT，局域网可达且零鉴权（文档 §8）。
        ServerSocket ss = new ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"));
        Log.i(TAG, "HTTP 127.0.0.1:" + PORT);
        while (true) {
          final Socket sock = ss.accept();
          try { pool.execute(new Runnable() { public void run() { handle(sock, ctx); } }); }
          catch (Throwable t) { try { sock.close(); } catch (Throwable t2) {} }
        }
      } catch (Throwable t) { Log.w(TAG, "srv", t); }
    } }).start();
  }

  public static void main(String[] args) throws Exception {
    if (args != null && args.length > 0) { try { PORT = Integer.parseInt(args[0].trim()); } catch (Throwable t) {} }
    // [加固 C] token 文件：args[1] 覆盖，默认 /data/local/tmp/dsh-vsd/.shd-token
    String tokenFile = (args != null && args.length > 1 && args[1] != null && args[1].trim().length() > 0)
        ? args[1].trim() : DEFAULT_TOKEN_FILE;
    loadToken(tokenFile);
    Looper.prepareMainLooper();
    Context ctx = shellCtx();
    ht = new HandlerThread("vsd"); ht.start();
    DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
    for (int i = 0; i < SPEC.length; i++) {
      Slot s = new Slot(); s.w = SPEC[i][0]; s.h = SPEC[i][1]; s.dpi = SPEC[i][2];
      s.reader = ImageReader.newInstance(s.w, s.h, PixelFormat.RGBA_8888, 3);
      attach(s, s.reader);
      s.vd = dm.createVirtualDisplay("dsh-trusted-" + i, s.w, s.h, s.dpi, s.reader.getSurface(),
          DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | TOUCH | OWNFOCUS | TRUSTED | OWN_CONTENT, null, new Handler(ht.getLooper()));
      s.id = s.vd.getDisplay().getDisplayId();
      slots[i] = s;
      System.out.println("TRUSTED[" + i + "] displayId=" + s.id + " " + s.w + "x" + s.h);
      System.out.flush();
    }
    serve(ctx);
    System.out.println("READY port=" + PORT);
    System.out.flush();
    Thread.sleep(86400000L);
  }
}
