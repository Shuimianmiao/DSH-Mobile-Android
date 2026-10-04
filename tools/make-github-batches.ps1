# 生成"网页上传"用的分批目录（_github上传\）。
#
# 为什么需要它：GitHub 的**网页上传**每批最多 100 个文件，而源码树已经 193 个
# （`app/src/main/res` 一个目录就占 90 个）。以前这几批是手工拖的，多一个文件
# 就可能超限（0.2.8.4 新增了 4 个文件，第一批从 99 变成 103 —— 直接传不上去），
# 所以固化成脚本：**按目录切、自动数数、并断言"三批合起来与源码树逐条一致"**。
#
# 用法（仓库根目录）：
#   powershell -File tools\make-github-batches.ps1
#
# 输入：DSH-Mobile-Android-源码\（由 tools\make-source-package.ps1 生成）
# 产出（每个目录里那一层，就是网页上传时要拖进去的东西）：
#   第1批-拖到仓库根目录\            根文件 + app(不含 res) + docs + gradle + LICENSES + plugin
#   第2批-tools-拖到仓库根目录\       tools\
#   第3批-res-拖到-app-src-main\     res\（上传前先在仓库里点进 app/src/main）
#
# 注意第 3 批里是 **res 本身**、不是 app/src/main/res 这一长串 ——
# 网页上传是"你进到哪个目录、它就落在哪个目录"。

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root 'DSH-Mobile-Android-源码'
$out = Join-Path $root '_github上传'

if (-not (Test-Path $src)) { throw "缺少源码树：$src（先跑 tools\make-source-package.ps1）" }
if (-not (Test-Path $out)) { New-Item -ItemType Directory -Path $out -Force | Out-Null }

$b1 = Join-Path $out '第1批-拖到仓库根目录'
$b2 = Join-Path $out '第2批-tools-拖到仓库根目录'
$b3 = Join-Path $out '第3批-res基础与uu01-10-拖到-app-src-main'
$b4 = Join-Path $out '第4批-res图标uu11-20-拖到-app-src-main'
$b5 = Join-Path $out '第5批-res图标uu21-30-拖到-app-src-main'

# 旧的批目录（含历史命名）一律清掉，避免上次的残留混进这次上传
Get-ChildItem $out -Directory | Where-Object { $_.Name -like '第*批*' } | ForEach-Object {
    Remove-Item $_.FullName -Recurse -Force
}

Write-Host '── 1/4 第 1 批：整棵树去掉 tools\ 与 app\src\main\res ──'
Copy-Item $src $b1 -Recurse -Force
Remove-Item (Join-Path $b1 'tools') -Recurse -Force
Remove-Item (Join-Path $b1 'docs') -Recurse -Force
Remove-Item (Join-Path $b1 'app\src\main\res') -Recurse -Force

Write-Host '── 2/4 第 2 批：tools\ 与 docs\（拖到仓库根目录）──'
New-Item -ItemType Directory -Path $b2 -Force | Out-Null
Copy-Item (Join-Path $src 'tools') $b2 -Recurse -Force
Copy-Item (Join-Path $src 'docs') $b2 -Recurse -Force

Write-Host '── 3/6 第 3 批：res\ 里的基础资源 + uu01~uu10（拖到 app/src/main）──'
Write-Host '── 4/6 第 4 批：res\ 里的 uu11~uu20（同样拖到 app/src/main）──'
Write-Host '── 5/6 第 5 批：res\ 里的 uu21~uu30（同样拖到 app/src/main）──'
New-Item -ItemType Directory -Path $b3 -Force | Out-Null
New-Item -ItemType Directory -Path $b4 -Force | Out-Null
New-Item -ItemType Directory -Path $b5 -Force | Out-Null
# res 现在 190 个文件（8 套配色 + 30 套投稿图标，每套 5 个资源）——一次传不完，
# 按图标编号拆三段（uu01~10 / uu11~20 / uu21~30），每批都留在 100 个文件以下。
# 三批都保留 res\ 这一层，先后拖进 app/src/main/res 即可（同名目录会合并）。
Get-ChildItem (Join-Path $src 'app\src\main\res') -Recurse -File -Force | ForEach-Object {
    $rel = $_.FullName.Substring((Join-Path $src 'app\src\main').Length + 1)
    $m = [regex]::Match($_.Name, 'ic_launcher_uu(\d+)')
    $num = if ($m.Success) { [int]$m.Groups[1].Value } else { 0 }
    $target = if ($num -ge 21) { $b5 } elseif ($num -ge 11) { $b4 } else { $b3 }
    $dst = Join-Path $target $rel
    $dir = Split-Path -Parent $dst
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    Copy-Item $_.FullName $dst -Force
}
Write-Host '── 6/6 对账：每批少于 100，且五批合起来 = 源码树 ──'
function Rel($base, $f) { $f.FullName.Substring($base.Length + 1).Replace('\', '/') }
$srcFiles = Get-ChildItem $src -Recurse -File -Force
$map = @{}
foreach ($f in $srcFiles) { $map[(Rel $src $f).ToLower()] = $f.Length }

$batches = @(
    @{ label = '第1批'; dir = $b1; prefix = '' },
    @{ label = '第2批'; dir = $b2; prefix = '' },
    @{ label = '第3批'; dir = $b3; prefix = 'app/src/main/' },
    @{ label = '第4批'; dir = $b4; prefix = 'app/src/main/' },  # 同样要拖进 app/src/main
    @{ label = '第5批'; dir = $b5; prefix = 'app/src/main/' }
)
foreach ($b in $batches) {
    $files = Get-ChildItem $b.dir -Recurse -File -Force
    if ($files.Count -ge 100) {
        throw "$($b.label) 有 $($files.Count) 个文件；GitHub 要求**少于** 100，请再拆一批"
    }
    foreach ($f in $files) {
        $repoRel = ($b.prefix + (Rel $b.dir $f)).ToLower()
        if (-not $map.ContainsKey($repoRel)) {
            throw "$($b.label) 里多出来一个文件（源码树里没有）：$repoRel"
        }
        if ($map[$repoRel] -ne $f.Length) { throw "$($b.label) 的 $repoRel 字节数不一致" }
        $map.Remove($repoRel)
    }
    Write-Host ("  {0}: {1} 个文件" -f $b.label, $files.Count)
}
if ($map.Count -gt 0) {
    throw "漏了 $($map.Count) 个文件没进任何批次：$(($map.Keys | Select-Object -First 5) -join ', ')"
}
Write-Host ("  ✅ 五批合计 {0} 个文件，与源码树逐条一致" -f $srcFiles.Count)
