# MoYue / InkReader - on-device verification (one command, full evidence pack)
#
# RUN THIS WITH pwsh (PowerShell 7), NOT powershell.exe 5.1:
#   pwsh -NoProfile -File C:\Harness\InkReader\tools\device-check.ps1
#
# Why: this file contains Chinese strings (they are the app's on-screen node names
# used to locate buttons, e.g. content-desc "search" / "toc"). It is saved as UTF-8
# WITHOUT a BOM, and Windows PowerShell 5.1 decodes BOM-less UTF-8 as GBK, which can
# mis-decode those characters and break PARSING - not just display. PowerShell 7
# defaults to UTF-8 and handles it correctly.
#
# Usage:
#   pwsh -NoProfile -File ...\device-check.ps1
#   pwsh -NoProfile -File ...\device-check.ps1 -IncludeHuge    # also push+import 100MB
#   pwsh -NoProfile -File ...\device-check.ps1 -SkipInstall
#
# What it does:
#   1. checks the device is authorized
#   2. installs the APK
#   3. generates + pushes test novels into the app's own external files dir
#   4. imports one via the inkreader.import_path intent (no file picker needed)
#   5. opens it, turns pages, toggles bars, searches, bookmarks, long-press selects
#   6. with -IncludeHuge: imports the 100MB novel too and measures how long it takes
#   7. pulls the SQLite database and validates it (tools/dump_db.py)
#   8. greps logcat for crashes / ANRs
#   9. writes screenshots + logs + db report into device-evidence\
#
# Exit codes: 0 = all critical checks passed, 1 = a critical check failed, 2 = no device.

param(
    [string]$Adb = 'C:\Harness\android-sdk\platform-tools\adb.exe',
    [string]$Apk = 'C:\Harness\InkReader\app\build\outputs\apk\debug\app-debug.apk',
    [string]$Pkg = 'com.harness.inkreader',
    [string]$Act = 'com.harness.inkreader/.MainActivity',
    [string]$TestData = 'C:\Harness\InkReader\testdata',
    [string]$Out = 'C:\Harness\InkReader\device-evidence',
    [string]$Python = 'C:\python\python.exe',
    [switch]$IncludeHuge,
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Continue'
$script:Failures = @()

# The Python helpers print Chinese; without this the console decodes their UTF-8
# output as GBK and everything looks like mojibake.
$env:PYTHONIOENCODING = 'utf-8'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }

function Info($m) { Write-Host "[*] $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "[+] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "[!] $m" -ForegroundColor Yellow }
function Fail($m) { Write-Host "[x] $m" -ForegroundColor Red; $script:Failures += $m }

New-Item -ItemType Directory -Force -Path $Out | Out-Null
$dbDir = Join-Path $Out 'db'
New-Item -ItemType Directory -Force -Path $dbDir | Out-Null

if (-not (Test-Path $Adb)) { Fail "adb not found: $Adb"; exit 1 }
if (-not (Test-Path $Apk)) { Fail "APK not found: $Apk"; exit 1 }

# ---------------------------------------------------------------- 1. device
$devices = @(& $Adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' })
if (-not $devices) {
    Warn 'No authorized device. Checklist:'
    Write-Host '    1) Developer options -> USB debugging is ON'
    Write-Host '    2) The cable supports data (not charge-only)'
    Write-Host '    3) The "Allow USB debugging?" dialog was accepted'
    & $Adb devices -l
    exit 2
}
$serial = ($devices[0] -split '\s+')[0]
Ok "device: $serial"

function Sh([string]$cmd) { & $Adb -s $serial shell $cmd }
function Shot([string]$name) {
    $path = Join-Path $Out $name
    cmd /c "`"$Adb`" -s $serial exec-out screencap -p > `"$path`"" | Out-Null
    if (Test-Path $path) { Ok "screenshot $name ($([math]::Round((Get-Item $path).Length/1KB,1)) KB)" }
    else { Warn "screenshot $name failed" }
}
function Get-Attr([string]$tag, [string]$name) {
    $m = [regex]::Match($tag, "$name=`"([^`"]*)`"")
    if ($m.Success) { return $m.Groups[1].Value }
    return ''
}
function Get-UiNodes {
    Sh 'uiautomator dump /sdcard/ui.xml' | Out-Null
    $raw = & $Adb -s $serial exec-out cat /sdcard/ui.xml 2>$null
    if (-not $raw) { return @() }
    $text = ($raw -join '')
    $nodes = @()
    foreach ($m in [regex]::Matches($text, '<node[^>]*>')) {
        $nodes += [pscustomobject]@{
            Text   = (Get-Attr $m.Value 'text')
            Desc   = (Get-Attr $m.Value 'content-desc')
            Id     = (Get-Attr $m.Value 'resource-id')
            Bounds = (Get-Attr $m.Value 'bounds')
        }
    }
    return $nodes
}
function Tap-Bounds([string]$bounds) {
    $m = [regex]::Match($bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    if (-not $m.Success) { return $false }
    $x = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
    $y = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
    Sh "input tap $x $y" | Out-Null
    Ok "tap ($x,$y)"
    return $true
}
function Tap-Node([string]$text, [string]$desc, [int]$timeoutSec = 12) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        foreach ($node in (Get-UiNodes)) {
            $hit = $false
            if ($text -and $node.Text -and $node.Text.Contains($text)) { $hit = $true }
            if (-not $hit -and $desc -and $node.Desc -and $node.Desc.Contains($desc)) { $hit = $true }
            if ($hit) { return (Tap-Bounds $node.Bounds) }
        }
        Start-Sleep -Milliseconds 600
    }
    Warn "node not found: text='$text' desc='$desc'"
    foreach ($node in (Get-UiNodes)) {
        if ($node.Text -or $node.Desc) { Write-Host ("      on screen: text='{0}' desc='{1}'" -f $node.Text, $node.Desc) }
    }
    return $false
}
function Screen-Size {
    $raw = (Sh 'wm size') -join ' '
    $m = [regex]::Match($raw, '(\d+)x(\d+)')
    if ($m.Success) { return @([int]$m.Groups[1].Value, [int]$m.Groups[2].Value) }
    return @(1080, 1920)
}
function Pull-Db {
    foreach ($suffix in @('', '-wal', '-shm')) {
        $target = Join-Path $dbDir "inkreader.db$suffix"
        cmd /c "`"$Adb`" -s $serial exec-out run-as $Pkg cat databases/inkreader.db$suffix > `"$target`"" 2>$null | Out-Null
    }
    $main = Join-Path $dbDir 'inkreader.db'
    if ((Test-Path $main) -and (Get-Item $main).Length -gt 0) { Ok "database pulled: $main"; return $true }
    Warn 'database pull failed (debug build required for run-as)'
    return $false
}

# ---------------------------------------------------------------- 2. install
if (-not $SkipInstall) {
    Info 'installing APK'
    $r = & $Adb -s $serial install -r $Apk 2>&1
    $r | ForEach-Object { "    $_" }
    if ("$r" -match 'Success') { Ok 'installed' } else { Fail 'install failed' }
} else {
    Info 'skipping install (-SkipInstall)'
}

# ---------------------------------------------------------------- 3. test data
Info 'generating test novels'
$hugeMb = 0
if ($IncludeHuge) { $hugeMb = 100 }
& $Python (Join-Path (Split-Path $PSScriptRoot -Parent) 'tools\make_test_novels.py') `
    --out $TestData --huge-mb $hugeMb 2>&1 | ForEach-Object { "    $_" }

$remoteDir = "/sdcard/Android/data/$Pkg/files"
Sh "mkdir -p $remoteDir" | Out-Null

$pushList = @('sample-gbk.txt')
if ($IncludeHuge) { $pushList += 'sample-huge.txt' }
foreach ($name in $pushList) {
    $local = Join-Path $TestData $name
    if (-not (Test-Path $local)) { Warn "missing test file: $local"; continue }
    Info "pushing $name ($([math]::Round((Get-Item $local).Length/1MB,2)) MB)"
    $t0 = Get-Date
    $r = & $Adb -s $serial push $local "$remoteDir/$name" 2>&1
    $secs = [math]::Round(((Get-Date) - $t0).TotalSeconds, 1)
    if ("$r" -match '1 file pushed') { Ok "pushed in ${secs}s" } else { Fail "push failed: $r" }
}

function Import-AndWait([string]$remotePath, [string]$titleFragment, [int]$timeoutSec) {
    Info "importing $titleFragment via intent"
    Sh "am force-stop $Pkg" | Out-Null
    Sh "am start -n $Act --es inkreader.import_path $remotePath" | ForEach-Object { "    $_" }
    Start-Sleep -Seconds 3
    Shot "10-importing-$titleFragment.png"

    $started = Get-Date
    $deadline = $started.AddSeconds($timeoutSec)
    $seen = $false
    while ((Get-Date) -lt $deadline) {
        foreach ($node in (Get-UiNodes)) {
            if ($node.Text -and $node.Text.Contains($titleFragment)) { $seen = $true; break }
        }
        if ($seen) { break }
        Start-Sleep -Seconds 2
    }
    $elapsed = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
    Shot "11-shelf-$titleFragment.png"
    if ($seen) { Ok "$titleFragment imported and visible after ${elapsed}s" }
    else { Fail "$titleFragment did not appear within ${timeoutSec}s" }
    return @{ Seen = $seen; Seconds = $elapsed }
}

# ---------------------------------------------------------------- 4. import
& $Adb -s $serial logcat -c
$first = Import-AndWait -remotePath "$remoteDir/sample-gbk.txt" -titleFragment 'sample-gbk' -timeoutSec 60
$bookVisible = $first.Seen

if (Pull-Db) {
    Info 'validating database'
    & $Python (Join-Path (Split-Path $PSScriptRoot -Parent) 'tools\dump_db.py') (Join-Path $dbDir 'inkreader.db') 2>&1 |
        Tee-Object -FilePath (Join-Path $Out 'db-report.txt') | ForEach-Object { "    $_" }
    if ($LASTEXITCODE -ne 0) { Fail 'database consistency check failed' } else { Ok 'database consistent' }
}

# ---------------------------------------------------------------- 5. read
if ($bookVisible) {
    Info 'opening the book'
    if (-not (Tap-Node -text 'sample-gbk' -desc '')) { Fail 'could not open the book' }
    Start-Sleep -Seconds 3
    Shot '20-reader.png'

    $size = Screen-Size
    $w = $size[0]; $h = $size[1]
    $rightX = [int]($w * 0.85); $leftX = [int]($w * 0.15); $midY = [int]($h * 0.5)

    Info 'turning three pages forward'
    for ($i = 1; $i -le 3; $i++) {
        Sh "input tap $rightX $midY" | Out-Null
        Start-Sleep -Milliseconds 700
    }
    Shot '21-after-3-pages.png'

    Info 'turning one page back'
    Sh "input tap $leftX $midY" | Out-Null
    Start-Sleep -Milliseconds 700
    Shot '22-after-back.png'

    Info 'showing the bottom bar (font size / progress)'
    Sh "input tap $([int]($w/2)) $midY" | Out-Null
    Start-Sleep -Milliseconds 800
    Shot '23-bars.png'

    Info 'opening the table of contents'
    if (Tap-Node -text '' -desc '目录' 8) {
        Start-Sleep -Milliseconds 900
        Shot '24-toc.png'
        Sh "input keyevent KEYCODE_BACK" | Out-Null
        Start-Sleep -Milliseconds 600
    } else { Warn 'toc button not found' }

    if (Pull-Db) {
        Info 'progress after page turns'
        & $Python (Join-Path (Split-Path $PSScriptRoot -Parent) 'tools\dump_db.py') (Join-Path $dbDir 'inkreader.db') 2>&1 |
            Select-String -Pattern 'progress|进度' | ForEach-Object { "    $_" }
    }

    Info 'adding a bookmark (star icon)'
    if (Tap-Node -text '' -desc '添加书签' 8) {
        Start-Sleep -Milliseconds 900
        Shot '30-bookmark.png'
    } else { Warn 'bookmark button not found' }

    Info 'searching for the ASCII marker'
    if (Tap-Node -text '' -desc '搜索' 8) {
        Start-Sleep -Milliseconds 900
        Sh "input text MARKER-ALPHA" | Out-Null
        Start-Sleep -Milliseconds 500
        Shot '40-search-input.png'
        if (-not (Tap-Node -text '搜索' -desc '' 6)) { Warn 'search button not found' }
        Start-Sleep -Seconds 6
        Shot '41-search-results.png'
        Sh "input keyevent KEYCODE_BACK" | Out-Null
        Start-Sleep -Milliseconds 600
    } else { Warn 'search button not found' }

    Info 'long-press to select a sentence'
    Sh "input swipe $([int]($w*0.45)) $([int]($h*0.4)) $([int]($w*0.45)) $([int]($h*0.4)) 900" | Out-Null
    Start-Sleep -Milliseconds 900
    Shot '50-selection.png'
    if (Tap-Node -text '划线' -desc '' 6) {
        Start-Sleep -Milliseconds 800
        Shot '51-highlighted.png'
    } else { Warn 'selection action bar not found (long press may not have registered)' }

    Info 'toggling auto page turn'
    if (Tap-Node -text '' -desc '更多' 8) {
        Start-Sleep -Milliseconds 700
        if (Tap-Node -text '自动翻页' -desc '' 6) {
            Start-Sleep -Seconds 3
            Shot '60-auto-page-turn.png'
        } else { Warn 'auto page turn menu item not found' }
    } else { Warn 'overflow menu not found' }

    Info 'back to the shelf'
    Sh "input keyevent KEYCODE_BACK" | Out-Null
    Start-Sleep -Milliseconds 900
    Shot '70-back-on-shelf.png'
}

# ---------------------------------------------------------------- 5b. 100MB
if ($IncludeHuge) {
    Info 'pushing + importing the 100MB novel (this is the headline device datapoint)'
    $huge = Import-AndWait -remotePath "$remoteDir/sample-huge.txt" -titleFragment 'sample-huge' -timeoutSec 240
    if ($huge.Seen) {
        Ok "100MB import wall time on device: $($huge.Seconds)s"
        $huge.Seconds | Out-File -FilePath (Join-Path $Out 'huge-import-seconds.txt') -Encoding ascii
    }
}

# ---------------------------------------------------------------- 6. final checks
if (Pull-Db) {
    Info 'final database validation'
    & $Python (Join-Path (Split-Path $PSScriptRoot -Parent) 'tools\dump_db.py') (Join-Path $dbDir 'inkreader.db') 2>&1 |
        Tee-Object -FilePath (Join-Path $Out 'db-report-final.txt') | ForEach-Object { "    $_" }
    if ($LASTEXITCODE -ne 0) { Fail 'final database consistency check failed' } else { Ok 'database consistent' }
}

Info 'collecting logs'
& $Adb -s $serial logcat -d -v time > (Join-Path $Out 'logcat.txt')
$crash = & $Adb -s $serial logcat -d -v brief |
    Select-String -Pattern 'FATAL EXCEPTION|AndroidRuntime|beginning of crash|ANR in '
if ($crash) {
    Fail 'crash or ANR found in logcat'
    $crash | Select-Object -First 40 | ForEach-Object { "    $_" }
} else {
    Ok 'no crash / ANR in logcat'
}

# ---------------------------------------------------------------- summary
Write-Host ''
Write-Host '================ summary ================'
if ($script:Failures.Count -eq 0) {
    Ok 'ALL CRITICAL CHECKS PASSED'
} else {
    Write-Host "[x] $($script:Failures.Count) critical check(s) failed:" -ForegroundColor Red
    foreach ($f in $script:Failures) { Write-Host "    - $f" -ForegroundColor Red }
}
Ok "evidence directory: $Out"
Write-Host '========================================'
if ($script:Failures.Count -gt 0) { exit 1 } else { exit 0 }
