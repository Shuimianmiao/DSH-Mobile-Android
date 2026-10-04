# vscreen-src —— 受信任虚拟屏的两个守护（源码 + 编译脚本）

这里放的是**随 App 分发的那两个 dex 的源码**：

| 源码 | 编出来的 dex | 进 APK 的位置 | 干什么 |
|---|---|---|---|
| `src/dsh/VsDaemon.java` | `out/vs-daemon.dex` | `app/src/main/assets/vsd/vs-daemon.dex` | 建**受信任虚拟屏**（`FLAG_TRUSTED` + `OWN_CONTENT`）、取帧、改分辨率、把应用放上屏 |
| `src/dsh/ShellDaemon.java` | `out/shell-daemon.dex` | `app/src/main/assets/vsd/shell-daemon.dex` | 以 **shell(uid 2000)** 身份跑命令（`/exec`）与读写 settings（`/settings`） |

> **方案与两个守护由群友「如月雨露」制作**（v1.1 技术文档），本目录是**移植进来的副本**：
> 我们只做了下面那批安全加固与缺陷修复，其余行为、HTTP 路由、响应格式一律照抄。
> 版权/许可与"我们改了什么"另外记在 `THIRD-PARTY.md` 第 6 节、`docs/第三方插件清单.md`。

---

## 1. 为什么要这套东西

只有 **shell 身份**能建"受信任"虚拟屏（`ADD_TRUSTED_DISPLAY` 是 signature 级权限）；
有了受信任屏，应用才能被放进 **freeform 窗口容器**里 → 从根上**不跳屏**，
于是"后台跑一个应用 + 取它的画面 + 按坐标操作"才成立。

App 自己（普通 uid）`createVirtualDisplay` 建的屏**没有 TRUSTED** → 一定会跳屏，
所以**没有兜底路线**，必须借 shell：要么 Shizuku，要么用户自己跑一次 adb。

两条通道 + 一条回环：

```
:18240  VsDaemon      /list  /<i>/info  /<i>/frame[?w=N]  POST /res  /resize  /launch
:18242  ShellDaemon   POST /exec（正文=命令）  /settings  /health      （另起一份在 :18244）
:3099   App 手机控制桥  GET /vscreen?op=ensure|status|stop   ← 唯一能"把守护拉起来"的地方
```

三个端口都**只绑 127.0.0.1**，且**每个请求校验 `X-Token`**；token 文件默认
`/data/local/tmp/dsh-vsd/.shd-token`（可由 `main(String[] args)` 的第二个参数覆盖）。

启动方式（App 就是这么起的）：

```sh
CLASSPATH=/data/local/tmp/dsh-vsd/vs-daemon.dex    app_process /system/bin dsh.VsDaemon    18240 /data/local/tmp/dsh-vsd/.shd-token
CLASSPATH=/data/local/tmp/dsh-vsd/shell-daemon.dex app_process /system/bin dsh.ShellDaemon 18242 /data/local/tmp/dsh-vsd/.shd-token
CLASSPATH=/data/local/tmp/dsh-vsd/shell-daemon.dex app_process /system/bin dsh.ShellDaemon 18244 /data/local/tmp/dsh-vsd/.shd-token
```

---

## 2. 相对原方案改了什么（[加固 A]~[加固 G]）

原方案自己的文档 §8「安全现状」点名了三条，§7 又列了三条缺陷；这里全部落地：

| 编号 | 位置 | 改动 | 为什么 |
|---|---|---|---|
| **A** | VsDaemon | `new ServerSocket(port)` → `new ServerSocket(port, 50, 127.0.0.1)` + **每请求校验 X-Token**（读不到 token 文件就 fail-closed，一律 403） | 原版**绑所有网卡 + 零鉴权**：同一个 WiFi 下任何人能读屏、还能发 `/launch` |
| **B** | VsDaemon | accept 后把请求丢给线程池（原版单线程 accept 循环） | 一条慢请求/慢客户端会把整个端点堵死 |
| **C** | VsDaemon | token 文件默认落 `/data/local/tmp/dsh-vsd/.shd-token`（原 `/sdcard`） | `/sdcard` 上任何有存储权限的 App 都能读 |
| **D** | ShellDaemon | 每请求一线程（固定池 + 背压） | 原版 `handle()` 直接在 accept 循环里串行执行 → 一条卡住的命令永久堵死端点 |
| **E** | ShellDaemon | 子进程用 `ProcessBuilder` 起，**移除 `CLASSPATH`**、补 `PATH`/`HOME` | 子命令继承守护的 `CLASSPATH` 时，内部用 `app_process` 的命令（如 `pm`）会捡到守护 dex 卡死 |
| **F** | ShellDaemon | 超时按**进程组**杀：`setsid` 让子进程自成会话组 → `kill -9 -<pgid>` | 原版只 `destroy()` 那个 `sh`，孙进程还握着管道，20 秒看门狗形同虚设 |
| **G** | ShellDaemon | 端口与 token 文件从 `args` 取；中文按**字节**记账（`Content-Length` 是字节数，不是字符数） | 原版写死 `/sdcard/dsh/.shd-token`；按字符读 body 会让中文命令挂到 30 秒超时 |

行为保持不变的：HTTP 路由与响应格式、虚拟屏 flags（`PUBLIC|TOUCH|OWNFOCUS|TRUSTED|OWN_CONTENT`）、
**拒绝把宿主 App（`com.dshmobile.probe`）放上虚拟屏**、`/res` 的同 dp 档位算法、
`/exec` 的 `[EXIT=n]` / `[TIMEOUT]` / `[EXIT=-9]` 约定。

---

## 3. 怎么重新编译

```powershell
powershell -ExecutionPolicy Bypass -File tools\vscreen-src\build.ps1
# 换过 SDK / 想彻底重来：
powershell -ExecutionPolicy Bypass -File tools\vscreen-src\build.ps1 -Clean
```

工具链（本机现成的，脚本里可覆盖）：

| 用途 | 路径 |
|---|---|
| JDK17 | `C:\Program Files\Microsoft\jdk-17.0.13.11-hotspot` |
| `android.jar` | `D:\apt phone\.android-sdk\platforms\android-36\android.jar` |
| `d8.bat` | `D:\apt phone\.android-sdk\build-tools\35.0.0\d8.bat` |

编译口径：`javac -source 8 -target 8 -bootclasspath android.jar`（android.jar 的 class 版本是 52，
不会触发"class file version 太高"）；万一将来换成更高版本的 android.jar，脚本会自动退回
`--release 8 -classpath android.jar`。d8 用 `--release --min-api 21 --lib android.jar`。

**编完记得把两个 dex 复制进 `DshMobile\app\src\main\assets\vsd\`**（APK 里带的是产物），
然后重编 APK。仓库里**不放 `out/` 与 `build/`**（二进制/中间产物不进仓库）。

---

## 4. 实测记录（都是真机跑出来的，不是推断）

设备：vivo V2408A / Android 16（adb 序列号 `10AF2D0K22004N1`）。
把 App 生成的安装脚本原样落盘执行（与「一键安装并启动」是同一条脚本）：

```
killed VsDaemon pid=18102
killed ShellDaemon pid=27167
VSD_TOKEN ok file=/data/local/tmp/dsh-vsd/.shd-token len=36
TRUSTED[0] displayId=30 636x1400
TRUSTED[1] displayId=31 1400x636
READY port=18240
SHD_BOOT pid=31195 uid=2000 / SHD_TOKEN ok / SHD_SETSID yes / SHD_READY port=18242 uid=2000
```

HTTP 契约（PC 侧 node 直连，与 `dsh-vscreen` 插件同一套请求）：

| 请求 | 结果 |
|---|---|
| `GET /list` 不带 token | **403 `DENIED`**（加固 A 生效） |
| `GET /list` 带 token | `[{"i":0,"displayId":32,...,"trusted":true},{...}]` |
| `POST /launch {pkg:"com.dshmobile.probe"}` | `{"ok":false,"error":"refused: host app must never be launched on a virtual display"}` |
| `POST /launch {pkg:"com.android.settings"}` | `{"ok":true,...,"displayId":32,"wm":5}`，随后 `frames` 从 0 涨到 42 |
| `GET /0/frame?w=360` | 200，**23955 字节，JPEG 头 `ff d8 ff`**（画面确实是「设置」） |
| `POST :18242/exec` body=`echo 中文测试; id` | `中文测试` + `uid=2000(shell) ...` + `[EXIT=0]`（**中文正确、且输出里不再有内部标记行**） |
| `POST :18242/exec` body=`printf '中' \| od -An -tx1` | `e4 b8 ad`（请求体里的中文按字节原样送到子进程） |
| `POST :18242/exec` body=`input -d 32 tap 300 700` | `[EXIT=0]`（虚拟屏上的点按生效） |
| `GET :18242/health` | `uid=2000 ok` |

### 编译/运行踩到的坑（别再踩）

1. **陈旧的 `/data/local/tmp/dalvik-cache` 会让 `app_process` 直接 `Aborted`/`Killed`**
   → 启动脚本里先 `rm -rf /data/local/tmp/dalvik-cache`（**只清这一个目录**，别碰别处）。
2. **`Process.pid()` 在 `app_process` 环境里返回 0**（实测），拿不到子进程 pid 就没法按组杀 →
   改成让内层 shell 自己把进程组 id 打回来（`DSHPGID=` 标记行），反射取 pid 只当兜底；
   这个标记行**只是守护内部用**，绝不出现在给调用方的输出里。
3. **多层 shell 里的转义会被吃掉**：最早写 `printf '\037DSHPGID=%d\n' $$`，
   要穿过「Java → `/system/bin/sh -c`（无引号形式）→ `exec setsid` → 内层 `sh -c '…'`」共 3 层解析，
   `\037` 变字面 `037`、`\n` 变 `n`。现在改成"变量拼串 + echo"，全程无内嵌引号/转义序列。
4. **输出必须按字节收、最后整体按 UTF-8 解码**：按字符读会把 UTF-8 的「中」拆成 3 个 char → 乱码。
5. **`VsDaemon` 的 `Context` 必须惰性创建**（启动期调 `ActivityThread.systemMain()` 在某些机型上会被静默 Killed）。
6. **停止守护 = 虚拟屏上跑着的应用会被系统一起收掉**（实测），这是方案固有行为，不是 bug。

---

## 5. 谁在用、怎么用

- **App 侧**：`手机权限 → 虚拟屏自动化`（`VScreen.java` / `VScreenCmd.java` / `VScreenActivity.java`）
  —— 一键装、重启、停止、看状态；没有 Shizuku 的用户可以在那一页复制 adb 命令。
- **DSH 侧**：插件 `dsh-vscreen`（7 个工具：`vscreen_ensure/list/shot/tap/swipe/key/app`），
  由 App 随包自动装进 profile；工具的用法也写在工作区 `AGENTS.md` 第 6 节里。

边界（写进界面与文档了）：守护的命绑 adb 调试通道（关开关/重启手机 → 几秒内全灭，重新装一次即可）；
`:18242` 等于"常驻在手机上的 adb shell"，token 泄露等于交出 shell —— 所以它默认不启用、只绑回环、可随时停止。
