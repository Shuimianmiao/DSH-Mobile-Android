# 生成"源码包"用于 GitHub Release / 仓库上传。
#
# 权威来源永远是这两个目录，staging 目录只是拼装现场：
#   DshMobile\            真正的 Gradle 工程（能直接构建出 APK）
#   tools\patch.js        窄屏补丁的源文件（构建时由 syncMobilePatch 拷进 assets）
#   分享\                 面向使用者的说明与截图
#
# 用法（在仓库根目录）:
#   pwsh -File tools\make-source-package.ps1
#
# 产出:
#   DSH-Mobile-Android-源码\        可直接上传的目录树
#   DSH-Mobile-Android-源码.zip     同内容的压缩包

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root 'DshMobile'
$stage = Join-Path $root 'DSH-Mobile-Android-源码'
$zip = Join-Path $root 'DSH-Mobile-Android-源码.zip'

function Copy-Tree($from, $to) {
    if (-not (Test-Path $from)) { throw "缺少来源: $from" }
    $parent = Split-Path -Parent $to
    if (-not (Test-Path $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    if (Test-Path $to) { Remove-Item $to -Recurse -Force }
    Copy-Item $from $to -Recurse -Force
}

Write-Host '── 1/3 同步工程源码 ──'
foreach ($p in @(
    'app\build.gradle',
    'app\src\main\AndroidManifest.xml',
    'app\src\main\java',
    'app\src\main\aidl',
    'app\src\main\res',
    'app\src\main\assets',
    'build.gradle',
    'settings.gradle',
    'gradle.properties',
    'gradlew',
    'gradlew.bat',
    'gradle'
)) {
    $from = Join-Path $src $p
    if (-not (Test-Path $from)) { throw "DshMobile 里缺少: $p" }
    Copy-Tree $from (Join-Path $stage $p)
    Write-Host "  $p"
}

# 这几个文件只在 staging 目录里维护（工程本身不需要它们就能构建），
# 但发布出去不能少 —— 缺了就断言失败，而不是悄悄发布一个残缺的包。
foreach ($p in @('LICENSE', 'README.md', 'THIRD-PARTY.md', 'LICENSES', '.gitignore')) {
    if (-not (Test-Path (Join-Path $stage $p))) { throw "staging 目录里缺少: $p" }
}
Write-Host '  LICENSE / README.md / THIRD-PARTY.md / LICENSES / .gitignore（沿用 staging）'

# ── 先把 staging 的 tools\ 整个清掉 ──
# Copy-Tree 只覆盖同名文件，**不会删掉目标目录里多出来的文件** ——
# 上一版实测：staging\tools 里沉淀着好几个历史上拷进去、现在早已删掉的
# verify-*.js，它们会跟着源码包一起发出去（别人看到一堆没用的脚本）。
$stageTools = Join-Path $stage 'tools'
if (Test-Path $stageTools) { Remove-Item $stageTools -Recurse -Force }

# build.gradle 里同步 assets 的任务会在两个候选路径里找 patch.js，
# 源码包必须自带一份，否则别人 clone 下来构建会缺文件。
Copy-Tree (Join-Path $root 'tools\patch.js') (Join-Path $stage 'tools\patch.js')
Write-Host '  tools\patch.js'

# 排查 WebView 用的调试工具（连 Chrome DevTools 协议，含 --crash）
Copy-Tree (Join-Path $root 'tools\cdp.mjs') (Join-Path $stage 'tools\cdp.mjs')
Write-Host '  tools\cdp.mjs'

# 打包脚本也进仓库：README 的「目录说明」里点名了它们，
# 不放进去就是死链接；而且别人想自己出一个可分发/可分享的包也得靠它们。
# （make-github-batches.ps1 = 网页上传用的分批目录，192 个文件得分 3 批）
foreach ($p in @('make-source-package.ps1', 'make-share-package.ps1', 'make-github-batches.ps1')) {
    Copy-Tree (Join-Path $root "tools\$p") (Join-Path $stage "tools\$p")
    Write-Host "  tools\$p"
}

# 用户投稿图标的生成脚本：别人 fork 之后要加自己的图，靠的就是它。
# （原图 tools\usericons-raw\ 不进仓库 —— 那是群友投稿的照片，
#   已经转好的图标资源在 app\src\main\res 里，照样能构建。）
Copy-Tree (Join-Path $root 'tools\make-user-icons.ps1') (Join-Path $stage 'tools\make-user-icons.ps1')
Write-Host '  tools\make-user-icons.ps1'

# 排查/回归工具：
#   · 老 WebView 语法降级那一套（参考实现 + 体检器 + 逐字节对账）
#   · UrlPick 的回归测试（"哪个地址才是 DSH"—— 用用户日志里的真实行做的用例）
#   · 前端自检的回归测试（"界面起不来"的判定只能看启动卡，不能被聊天记录带偏）
#   · 失败原文的解析回归（DSH 0.2 打的是 "xxx: import failed"，旧正则认不出）
#   · 虚拟屏一键安装的脚本/解析回归（VScreenCmdSelfTest）
foreach ($p in @('rewrite-static-block.mjs', 'dsh-api-inventory.mjs', 'JsCompatSelfTest.java',
                 'UrlPickSelfTest.java', 'WebdogSelfTest.mjs', 'PluginMsgSelfTest.java',
                 'ProvidersSelfTest.java', 'VScreenCmdSelfTest.java')) {
    Copy-Tree (Join-Path $root "tools\$p") (Join-Path $stage "tools\$p")
    Write-Host "  tools\$p"
}

# 虚拟屏守护的**源码与编译脚本**（群友「如月雨露」的方案）也进仓库：
#   · 别人 clone 下来能自己编译那两个 dex（APK assets 里带的是编译产物）；
#   · 只带 src 与 build.ps1、README，**不带 out\*.dex** —— 二进制不该进仓库。
Copy-Tree (Join-Path $root 'tools\vscreen-src\src') (Join-Path $stage 'tools\vscreen-src\src')
Copy-Tree (Join-Path $root 'tools\vscreen-src\build.ps1') (Join-Path $stage 'tools\vscreen-src\build.ps1')
Copy-Tree (Join-Path $root 'tools\vscreen-src\README.md') (Join-Path $stage 'tools\vscreen-src\README.md')
Write-Host '  tools\vscreen-src\（守护源码 + build.ps1）'

Copy-Tree (Join-Path $src 'CHANGELOG.md') (Join-Path $stage 'CHANGELOG.md')
Write-Host '  CHANGELOG.md'

# 自带插件的源码也要进仓库 —— 否则别人只看到 assets 里一个 .tgz 二进制，
# 既不知道它是什么、也没法改。打包脚本一并附上，保证产物可复现。
Copy-Tree (Join-Path $root 'plugin\dsh-phone') (Join-Path $stage 'plugin\dsh-phone')
Copy-Tree (Join-Path $root 'plugin\dsh-phone-files') (Join-Path $stage 'plugin\dsh-phone-files')
Copy-Tree (Join-Path $root 'plugin\dsh-link') (Join-Path $stage 'plugin\dsh-link')
Copy-Tree (Join-Path $root 'plugin\dsh-vscreen') (Join-Path $stage 'plugin\dsh-vscreen')
Copy-Tree (Join-Path $root 'plugin\build-tgz.ps1') (Join-Path $stage 'plugin\build-tgz.ps1')
Write-Host '  plugin\ (dsh-phone + dsh-phone-files + dsh-link + dsh-vscreen 源码 + 打包脚本)'

# 使用者文档：源码包和分享包里是同一份，避免两边说法不一致。
Copy-Tree (Join-Path $root '分享\安装与使用说明.md') (Join-Path $stage 'docs\安装与使用说明.md')
Copy-Tree (Join-Path $root '分享\Shizuku授权教程.md') (Join-Path $stage 'docs\Shizuku授权教程.md')
Copy-Tree (Join-Path $root '分享\插件市场说明.md') (Join-Path $stage 'docs\插件市场说明.md')
Copy-Tree (Join-Path $root '分享\img') (Join-Path $stage 'docs\img')
Write-Host '  docs\'

Write-Host '── 2/3 敏感文件检查 ──'
$leaks = Get-ChildItem $stage -Recurse -File |
    Where-Object { $_.Name -match 'release\.keystore|keystore\.properties|\.jks$|\.keystore$' }
if ($leaks) {
    $leaks | ForEach-Object { Write-Host "  !! $($_.FullName)" }
    throw '源码包里出现了签名密钥，已中止'
}
Write-Host '  OK：没有密钥文件'

Write-Host '── 3/3 打包 ──'
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $zip -Force
$z = Get-Item $zip
Write-Host ("  完成: {0}  ({1:N0} 字节)" -f $z.Name, $z.Length)
