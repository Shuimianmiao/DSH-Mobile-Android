# build.ps1 —— PC 侧一键编译 DSH 守护 dex（Windows PowerShell 5.1）
# 产物：out\vs-daemon.dex（dsh.VsDaemon）、out\shell-daemon.dex（dsh.ShellDaemon）
# 为什么要分开编：手机上是用 `CLASSPATH=<dex> app_process /system/bin dsh.<类名> <端口>` 起的，
# 一个 dex 里多塞一个类没必要，也让两种守护的 dex 各自可独立替换/校验。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File build.ps1
#   powershell -ExecutionPolicy Bypass -File build.ps1 -Clean
[CmdletBinding()]
param(
  # 强制先删掉 build/ 与 out/（dex 编译有缓存，换 SDK 后建议 -Clean）
  [switch]$Clean,
  # 覆盖工具链路径（默认就是本机现成的那套）
  [string]$JavaHome = 'C:\Program Files\Microsoft\jdk-17.0.13.11-hotspot',
  [string]$AndroidSdk = 'D:\apt phone\.android-sdk',
  [string]$BuildTools = '35.0.0'
)

$ErrorActionPreference = 'Stop'
# 让控制台按 UTF-8 输出，免得中文错误信息变乱码
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

$Root     = Split-Path -Parent $MyInvocation.MyCommand.Path
$SrcDsh   = Join-Path $Root 'src\dsh'
$BuildDir = Join-Path $Root 'build'
$OutDir   = Join-Path $Root 'out'
$Javac    = Join-Path $JavaHome 'bin\javac.exe'
$AndroidJar = Join-Path $AndroidSdk 'platforms\android-36\android.jar'
$D8       = Join-Path $AndroidSdk ("build-tools\{0}\d8.bat" -f $BuildTools)

foreach ($p in @($Javac, $AndroidJar, $D8)) {
  if (-not (Test-Path -LiteralPath $p)) { throw "缺少工具链文件：$p" }
}
if (-not (Test-Path -LiteralPath $SrcDsh)) { throw "缺源码目录：$SrcDsh" }

if ($Clean) {
  foreach ($d in @($BuildDir, $OutDir)) { if (Test-Path -LiteralPath $d) { Remove-Item -LiteralPath $d -Recurse -Force } }
}
foreach ($d in @($BuildDir, $OutDir)) { if (-not (Test-Path -LiteralPath $d)) { New-Item -ItemType Directory -Path $d | Out-Null } }

function Invoke-Native {
  param([string]$Exe, [string[]]$ArgList, [string]$What)
  Write-Host ">> $What" -ForegroundColor Cyan
  Write-Host ("   " + $Exe + " " + ($ArgList -join ' ')) -ForegroundColor DarkGray
  # 两个 PowerShell 5.1 的坑，这里都要绕开：
  #  1) 原生程序写 stderr 时，`& exe 2>&1` 会把每一行变成 NativeCommandError，
  #     再叠上 $ErrorActionPreference='Stop' 就直接炸掉脚本 —— 所以用 Start-Process 分离取流。
  #  2) Start-Process 是把参数数组用空格拼成一整条命令行的，不会替含空格的参数补引号
  #     （本项目根目录就叫 "D:\apt phone"，实测报「无效的标记: phone\.android-sdk\...」）
  #     —— 所以下面自己按 Windows 规则给带空格的参数套双引号（也别绕道 cmd.exe /c 拼串，同理会被拆开）。
  $so = [System.IO.Path]::GetTempFileName()
  $se = [System.IO.Path]::GetTempFileName()
  $code = -1
  try {
    $quoted = @()
    foreach ($a in $ArgList) {
      if ($a -match '[\s"]') { $quoted += ('"' + ($a -replace '(\\*)"', '$1$1\"') + '"') } else { $quoted += $a }
    }
    $p = Start-Process -FilePath $Exe -ArgumentList $quoted -NoNewWindow -Wait -PassThru `
                       -RedirectStandardOutput $so -RedirectStandardError $se
    $code = $p.ExitCode
    $txt = ''
    foreach ($f in @($so, $se)) {
      if (Test-Path -LiteralPath $f) {
        $t = (Get-Content -LiteralPath $f -Raw)
        if ($t -and $t.Trim().Length -gt 0) { $txt += (($t -replace "`r`n", "`n").TrimEnd() + "`n") }
      }
    }
    $txt = $txt.TrimEnd()
    if ($txt.Length -gt 0) { Write-Host $txt }
    return @{ Code = $code; Out = $txt }
  } finally {
    foreach ($f in @($so, $se)) { if (Test-Path -LiteralPath $f) { Remove-Item -LiteralPath $f -Force -ErrorAction SilentlyContinue } }
  }
}

# ── 1) javac ────────────────────────────────────────────────────────────────
# 编译口径说明（README 里同步写了）：
#   javac 17 自带 --release 8 的 ct.sym 里没有 android.* 类，所以只能走
#   `-source 8 -target 8 -bootclasspath <android.jar>`（android.jar 的 class 版本是 52/Java8，
#   不会触发 "class file version 太高"，本机实测直接可用）。
#   万一将来换成高版本 android.jar（class 版本 > 8）导致 javac 报错，
#   脚本会自动退回 `--release 8 -classpath <android.jar>`（javac 会忽略 bootclasspath 的旧类）。
function Compile-Class {
  param([string]$Name)
  $javaFile = Join-Path $SrcDsh "$Name.java"
  $clsDir   = Join-Path $BuildDir $Name
  if (Test-Path -LiteralPath $clsDir) { Remove-Item -LiteralPath $clsDir -Recurse -Force }
  New-Item -ItemType Directory -Path $clsDir | Out-Null

  $argsPrimary = @(
    '-encoding', 'UTF-8',
    '-source', '8', '-target', '8',
    '-bootclasspath', $AndroidJar,
    '-classpath', $AndroidJar,
    '-nowarn',
    '-d', $clsDir,
    $javaFile
  )
  $r = Invoke-Native -Exe $Javac -ArgList $argsPrimary -What "javac $Name.java (source/target 8 + bootclasspath)"
  if ($r.Code -ne 0) {
    Write-Host "   主口径失败，退回 --release 8 -classpath <android.jar>" -ForegroundColor Yellow
    $argsFallback = @(
      '-encoding', 'UTF-8',
      '--release', '8',
      '-classpath', $AndroidJar,
      '-nowarn',
      '-d', $clsDir,
      $javaFile
    )
    $r = Invoke-Native -Exe $Javac -ArgList $argsFallback -What "javac $Name.java (--release 8 fallback)"
    if ($r.Code -ne 0) { throw "javac $Name.java 两种口径都失败" }
  }
  return $clsDir
}

# ── 2) d8 ───────────────────────────────────────────────────────────────────
# d8 只接受 class 文件/容器，不接受源码；--lib 指 android.jar 让它别去解析 android.* 的实现。
function Build-Dex {
  param([string]$Name, [string]$DexName, [string]$ClsDir)
  $classes = Get-ChildItem -LiteralPath $ClsDir -Recurse -Filter *.class | ForEach-Object { $_.FullName }
  if (-not $classes -or $classes.Count -eq 0) { throw "$Name 没编出 class" }
  $args = @('--release', '--min-api', '21', '--lib', $AndroidJar, '--output', $OutDir) + $classes
  # d8.bat 是批处理，& 调用即可；它默认把结果命名成 classes.dex，随后改名成我们要的名字。
  $r = Invoke-Native -Exe $D8 -ArgList $args -What "d8 $Name -> $DexName"
  if ($r.Code -ne 0) { throw "d8 $Name 失败" }
  $produced = Join-Path $OutDir 'classes.dex'
  if (-not (Test-Path -LiteralPath $produced)) { throw "d8 没产出 $produced" }
  $final = Join-Path $OutDir $DexName
  Move-Item -LiteralPath $produced -Destination $final -Force
  return $final
}

$vsCls = Compile-Class -Name 'VsDaemon'
$shCls = Compile-Class -Name 'ShellDaemon'
$vsDex = Build-Dex -Name 'VsDaemon'    -DexName 'vs-daemon.dex'    -ClsDir $vsCls
$shDex = Build-Dex -Name 'ShellDaemon' -DexName 'shell-daemon.dex' -ClsDir $shCls

# ── 3) 校验：字节数 + dex magic（每个 dex 的前 8 字节 = "dex\n0NN\0"） ──────
Write-Host ''
Write-Host '== 产物校验 ==' -ForegroundColor Green
foreach ($f in @($vsDex, $shDex)) {
  $fi   = Get-Item -LiteralPath $f
  $head = [System.IO.File]::ReadAllBytes($f)[0..7]
  $magic = -join ($head | ForEach-Object { if ($_ -ge 32 -and $_ -lt 127) { [char]$_ } else { '\x{0:X2}' -f $_ } })
  Write-Host ("{0}  {1} bytes  first8={2}" -f $fi.FullName, $fi.Length, $magic)
}
Write-Host 'OK'
