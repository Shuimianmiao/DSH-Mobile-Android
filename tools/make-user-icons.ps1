# DSH Mobile - build launcher-icon resources from user-contributed pictures.
#
# Why this script exists
# ---------------------
# Android does NOT let an app use an arbitrary picture as its launcher icon at
# runtime: android:icon must be a resource compiled into the APK. The only
# supported way to offer "pick your own shortcut picture" is to ship one
# <activity-alias> per picture and enable exactly one of them at runtime
# (see IconSwitcher.java).
#
# So every picture the users send us has to be turned into res/ resources here,
# once, at build time.
#
# Per picture we emit 5 files (ASCII ids only - the Chinese labels live in
# IconSwitcher.java):
#   res/mipmap-xxhdpi/ic_launcher_<id>.jpg      48dp legacy icon (API 24-25)
#   res/mipmap-xxxhdpi/ic_launcher_<id>.jpg
#   res/mipmap-xxhdpi/ic_launcher_<id>_fg.jpg   108dp adaptive foreground
#   res/mipmap-xxxhdpi/ic_launcher_<id>_fg.jpg
#   res/mipmap-anydpi-v26/ic_launcher_<id>.xml  adaptive-icon (API 26+)
# plus one line of <color> per picture in res/values/ic_launcher_uu_colors.xml
#
# Layout decisions (measured, not guessed)
# ---------------------------------------
#   * Adaptive icon layers are 108x108dp, but only the central 72x72dp is ever
#     visible under any launcher mask. So the picture is drawn at exactly 72dp
#     and the surrounding ring is filled with the picture's own edge colour -
#     the ring is never visible, it only exists because a JPEG cannot be
#     transparent, and it is also what <background> points at so the parallax
#     zoom of the launcher has something matching behind it.
#   * Pictures are centre-cropped to a square first: launcher masks are square
#     or circular, and letterboxing a 966x1308 photo would waste the safe zone.
#   * JPEG (q88) instead of PNG: these are photographs, PNG would add ~7MB to a
#     3.4MB APK. Nothing here needs transparency.
#
# Usage:  powershell -File tools/make-user-icons.ps1 [-SourceDir <dir>] [-ResDir <dir>]
#
# NOTE: this file must keep its UTF-8 BOM - Windows PowerShell 5.1 reads
# BOM-less files as GBK and then mangles the Chinese strings below.

param(
    [string]$SourceDir = (Join-Path $PSScriptRoot 'usericons-raw'),
    [string]$ResDir = (Join-Path $PSScriptRoot '..\DshMobile\app\src\main\res')
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

# id -> source picture. Keep in sync with IconSwitcher.all() and AndroidManifest.xml.
$Table = @(
    @{ id = 'uu01'; file = 'Image_1790867088056_484.jpg';  frame = 0 },
    @{ id = 'uu02'; file = 'Image_1790867088056_724.gif';  frame = 0 },
    @{ id = 'uu03'; file = 'Image_1790867088367_192.jpg';  frame = 0 },
    @{ id = 'uu04'; file = 'Image_1790867088561_359.jpg';  frame = 0 },
    @{ id = 'uu05'; file = 'Image_1790867088602_536.jpg';  frame = 0 },
    @{ id = 'uu06'; file = 'Image_1790867088649_67.jpg';   frame = 0 },
    @{ id = 'uu07'; file = 'Image_1790867088787_567.png';  frame = 0 },
    @{ id = 'uu08'; file = 'Image_1790867088835_192.jpg';  frame = 0 },
    @{ id = 'uu09'; file = 'Image_1790867088896_421.png';  frame = 0 },
    @{ id = 'uu10'; file = 'Image_1790867088952_880.jpg';  frame = 0 },
    # -- 第二批（16 张，编号 uu11..uu26；按原文件名升序排列）--
    @{ id = 'uu11'; file = 'Image_1790939958837_786.jpg'; frame = 0 },
    @{ id = 'uu12'; file = 'Image_1790939960779_87.jpg';  frame = 0 },
    @{ id = 'uu13'; file = 'Image_1790939962673_628.jpg'; frame = 0 },
    @{ id = 'uu14'; file = 'Image_1790939965615_596.jpg'; frame = 0 },
    @{ id = 'uu15'; file = 'Image_1790939969304_990.jpg'; frame = 0 },
    @{ id = 'uu16'; file = 'Image_1790939982866_48.jpg';  frame = 0 },
    @{ id = 'uu17'; file = 'Image_1790939987393_984.jpg'; frame = 0 },
    @{ id = 'uu18'; file = 'Image_1790939991679_961.jpg'; frame = 0 },
    @{ id = 'uu19'; file = 'Image_1790939994844_950.jpg'; frame = 0 },
    @{ id = 'uu20'; file = 'Image_1790939996621_949.jpg'; frame = 0 },
    @{ id = 'uu21'; file = 'Image_1790939998411_354.jpg'; frame = 0 },
    @{ id = 'uu22'; file = 'Image_1790940000231_885.jpg'; frame = 0 },
    @{ id = 'uu23'; file = 'Image_1790940002071_665.jpg'; frame = 0 },
    @{ id = 'uu24'; file = 'Image_1790940004049_821.jpg'; frame = 0 },
    @{ id = 'uu25'; file = 'Image_1790940005896_8.jpg';   frame = 0 },
    @{ id = 'uu26'; file = 'Image_1790940007796_757.jpg'; frame = 0 },
    # -- 第三批（4 张，编号 uu27..uu30；前两张是 jpg，后两张原图是动图，
    #    进仓库前已用 WIC 解出第 0 帧存成 png —— 桌面图标本来就只能是静态的）--
    @{ id = 'uu27'; file = 'Image_1791102044965_324.jpg'; frame = 0 },
    @{ id = 'uu28'; file = 'Image_1791102048841_270.jpg'; frame = 0 },
    @{ id = 'uu29'; file = 'Image_1791102052002_373.png'; frame = 0 },
    @{ id = 'uu30'; file = 'Image_1791102054470_716.png'; frame = 0 }
)

# dp -> px for the densities we ship (two is enough: Android scales down cleanly,
# and every extra density is another copy of a photograph inside the APK).
$Densities = @(
    @{ name = 'xxhdpi';  scale = 3.0 },
    @{ name = 'xxxhdpi'; scale = 4.0 }
)

$FgDp = 108         # adaptive icon layer size
$FgVisibleDp = 72   # the part any launcher mask is able to show
$LegacyDp = 48

function Get-JpegCodec {
    [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() |
        Where-Object { $_.MimeType -eq 'image/jpeg' } | Select-Object -First 1
}

$JpegCodec = Get-JpegCodec
$JpegParams = New-Object System.Drawing.Imaging.EncoderParameters(1)
$JpegParams.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter(
    [System.Drawing.Imaging.Encoder]::Quality, [int64]88)

function Save-Jpeg($bmp, $path) {
    $dir = Split-Path -Parent $path
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $bmp.Save($path, $JpegCodec, $JpegParams)
}

function New-HighQualityGraphics($bmp) {
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    return $g
}

# Centre-crop to a square and scale to $side, returned as a new Bitmap.
function Get-SquareBitmap($imgIn, $side) {
    $s = [Math]::Min($imgIn.Width, $imgIn.Height)
    $x = [int](($imgIn.Width - $s) / 2)
    $y = [int](($imgIn.Height - $s) / 2)
    $dst = New-Object System.Drawing.Bitmap($side, $side, [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $g = New-HighQualityGraphics $dst
    try {
        $rect = New-Object System.Drawing.Rectangle(0, 0, $side, $side)
        $g.DrawImage($imgIn, $rect, (New-Object System.Drawing.Rectangle($x, $y, $s, $s)),
            [System.Drawing.GraphicsUnit]::Pixel)
    } finally { $g.Dispose() }
    return $dst
}

# Average colour of the outermost 3% ring - used to fill the invisible margin so
# the icon blends with itself instead of showing a white or black frame.
function Get-EdgeColor($bmp) {
    $m = [Math]::Max(1, [int]([Math]::Min($bmp.Width, $bmp.Height) * 0.03))
    $r = 0.0; $g = 0.0; $b = 0.0; $n = 0.0
    for ($y = 0; $y -lt $bmp.Height; $y++) {
        for ($x = 0; $x -lt $bmp.Width; $x++) {
            if ($x -ge $m -and $x -lt ($bmp.Width - $m) -and $y -ge $m -and $y -lt ($bmp.Height - $m)) { continue }
            $c = $bmp.GetPixel($x, $y)
            $r += $c.R; $g += $c.G; $b += $c.B; $n++
        }
    }
    if ($n -eq 0) { return [System.Drawing.Color]::White }
    return [System.Drawing.Color]::FromArgb(255, [int]($r / $n), [int]($g / $n), [int]($b / $n))
}

function New-RoundedPath($rect, $radius) {
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $radius * 2
    $p.AddArc($rect.X, $rect.Y, $d, $d, 180, 90)
    $p.AddArc($rect.Right - $d, $rect.Y, $d, $d, 270, 90)
    $p.AddArc($rect.Right - $d, $rect.Bottom - $d, $d, $d, 0, 90)
    $p.AddArc($rect.X, $rect.Bottom - $d, $d, $d, 90, 90)
    $p.CloseFigure()
    return $p
}

$colorLines = New-Object System.Collections.Generic.List[string]
$made = 0

foreach ($row in $Table) {
    $path = Join-Path $SourceDir $row.file
    if (-not (Test-Path $path)) { throw "missing source picture: $path" }
    $img = [System.Drawing.Image]::FromFile($path)
    try {
        # Only animated GIFs need frame selection; asking a JPEG for its frame
        # count makes GDI+ throw on some encoders.
        if ([System.IO.Path]::GetExtension($path).ToLowerInvariant() -eq '.gif') {
            try {
                $img.SelectActiveFrame([System.Drawing.Imaging.FrameDimension]::Time, $row.frame) | Out-Null
            } catch { Write-Host "  (frame select failed, using frame 0)" }
        }
        # Work on a flattened 1024x1024 copy: it also makes GIF frames safe to draw.
        # Cleared to white first - transparent PNG corners would otherwise turn
        # black (a 24bpp bitmap starts zeroed) and drag the edge colour down.
        $work = New-Object System.Drawing.Bitmap(1024, 1024, [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
        $gw = New-HighQualityGraphics $work
        try {
            $gw.Clear([System.Drawing.Color]::White)
            $sw = [Math]::Min($img.Width, $img.Height)
            $sx = [int](($img.Width - $sw) / 2); $sy = [int](($img.Height - $sw) / 2)
            $gw.DrawImage($img, (New-Object System.Drawing.Rectangle(0, 0, 1024, 1024)),
                (New-Object System.Drawing.Rectangle($sx, $sy, $sw, $sw)), [System.Drawing.GraphicsUnit]::Pixel)
        } finally { $gw.Dispose() }

        $bg = Get-EdgeColor $work
        $colorLines.Add("    <color name=`"icon_bg_$($row.id)`">#$('{0:X2}{1:X2}{2:X2}' -f $bg.R, $bg.G, $bg.B)</color>")

        foreach ($d in $Densities) {
            $fgPx = [int]($FgDp * $d.scale)
            $visPx = [int]($FgVisibleDp * $d.scale)
            $legPx = [int]($LegacyDp * $d.scale)

            # -- adaptive foreground: picture at 72dp centred on a 108dp canvas --
            $canvas = New-Object System.Drawing.Bitmap($fgPx, $fgPx, [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
            $gc = New-HighQualityGraphics $canvas
            try {
                $gc.Clear($bg)
                $pic = Get-SquareBitmap $work $visPx
                $off = [int](($fgPx - $visPx) / 2)
                $rect = New-Object System.Drawing.Rectangle($off, $off, $visPx, $visPx)
                $pp = New-RoundedPath $rect ([int]($visPx * 0.14))
                try {
                    $gc.SetClip($pp)
                    $gc.DrawImage($pic, $rect)
                } finally { $pp.Dispose(); $pic.Dispose() }
            } finally { $gc.Dispose() }
            Save-Jpeg $canvas (Join-Path $ResDir "mipmap-$($d.name)\ic_launcher_$($row.id)_fg.jpg")
            $canvas.Dispose()

            # -- legacy icon (API 24-25): the picture alone, rounded corners --
            $leg = New-Object System.Drawing.Bitmap($legPx, $legPx, [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
            $gl = New-HighQualityGraphics $leg
            try {
                $gl.Clear($bg)
                $pic2 = Get-SquareBitmap $work $legPx
                $rect2 = New-Object System.Drawing.Rectangle(0, 0, $legPx, $legPx)
                $pp2 = New-RoundedPath $rect2 ([int]($legPx * 0.22))
                try {
                    $gl.SetClip($pp2)
                    $gl.DrawImage($pic2, $rect2)
                } finally { $pp2.Dispose(); $pic2.Dispose() }
            } finally { $gl.Dispose() }
            Save-Jpeg $leg (Join-Path $ResDir "mipmap-$($d.name)\ic_launcher_$($row.id).jpg")
            $leg.Dispose()
        }

        # -- adaptive-icon XML (API 26+) --
        $xmlDir = Join-Path $ResDir 'mipmap-anydpi-v26'
        if (-not (Test-Path $xmlDir)) { New-Item -ItemType Directory -Force -Path $xmlDir | Out-Null }
        $xml = @"
<?xml version="1.0" encoding="utf-8"?>
<!--
  用户投稿图标（uu们添加的图标）：$($row.file)
  前景是 108dp 画布、画面占中央 72dp（任何遮罩最多只能看到 72dp），
  四周那一圈填的是画面自己的边缘色 —— 它永远看不见，只是 JPEG 不能透明，
  同时 <background> 也指向它，桌面视差放大时背后颜色是接得上的。
  资源由 tools/make-user-icons.ps1 生成，不要手改。
-->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/icon_bg_$($row.id)" />
    <foreground android:drawable="@mipmap/ic_launcher_$($row.id)_fg" />
</adaptive-icon>
"@
        $xmlPath = Join-Path $xmlDir "ic_launcher_$($row.id).xml"
        [System.IO.File]::WriteAllText($xmlPath, $xml, (New-Object System.Text.UTF8Encoding($false)))

        $work.Dispose()
        $made++
        Write-Host ("  {0}  <- {1}  bg=#{2:X2}{3:X2}{4:X2}" -f $row.id, $row.file, $bg.R, $bg.G, $bg.B)
    } finally {
        $img.Dispose()
    }
}

$valuesDir = Join-Path $ResDir 'values'
if (-not (Test-Path $valuesDir)) { New-Item -ItemType Directory -Force -Path $valuesDir | Out-Null }
$colorsFile = @"
<?xml version="1.0" encoding="utf-8"?>
<!--
  用户投稿图标的自适应图标背景色（取自每张图的边缘平均色）。
  由 tools/make-user-icons.ps1 生成，不要手改。
-->
<resources>
$($colorLines -join "`r`n")
</resources>
"@
[System.IO.File]::WriteAllText((Join-Path $valuesDir 'ic_launcher_uu_colors.xml'), $colorsFile,
    (New-Object System.Text.UTF8Encoding($false)))

Write-Host "done: $made icons -> $ResDir"
