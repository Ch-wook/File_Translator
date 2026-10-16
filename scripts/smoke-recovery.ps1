param([string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe")
$ErrorActionPreference = 'Stop'
function Invoke-Adb { & $Adb @args; if ($LASTEXITCODE -ne 0) { throw "adb command failed" } }
function Read-Ui {
    Invoke-Adb shell uiautomator dump /sdcard/studio-ui.xml | Out-Null
    $xmlText = & $Adb shell cat /sdcard/studio-ui.xml
    return [xml]($xmlText -join "`n")
}
function Tap-Node([string]$label) {
    $tree = Read-Ui
    $node = $tree.SelectNodes('//node') | Where-Object { $_.text -eq $label -or $_.'content-desc' -eq $label } | Select-Object -First 1
    if (-not $node) { throw "UI node not found: $label" }
    if ($node.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw 'Invalid bounds' }
    $x = [int](([int]$Matches[1]+[int]$Matches[3])/2)
    $y = [int](([int]$Matches[2]+[int]$Matches[4])/2)
    Invoke-Adb shell input tap $x $y
}
Invoke-Adb shell am force-stop kr.documentstudio
Invoke-Adb shell am start -W -n kr.documentstudio/.MainActivity | Out-Null
Tap-Node '새 문서'
Tap-Node '텍스트  .txt'
Tap-Node '문서 본문'
Invoke-Adb shell input text RecoverySmoke123
Invoke-Adb shell input keyevent 4
Tap-Node '필기 페이지'
$tree = Read-Ui
$ink = $tree.SelectNodes('//node') | Where-Object { $_.'content-desc' -eq '필기 페이지' } | Select-Object -First 1
if (-not $ink -or $ink.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw 'Ink canvas not found' }
$left=[int]$Matches[1];$top=[int]$Matches[2];$right=[int]$Matches[3];$bottom=[int]$Matches[4]
Invoke-Adb shell input swipe ([int]($left+($right-$left)*0.2)) ([int]($top+($bottom-$top)*0.4)) ([int]($left+($right-$left)*0.7)) ([int]($top+($bottom-$top)*0.5)) 400
Start-Sleep -Milliseconds 1500
$rows = ((& $Adb shell run-as kr.documentstudio cat files/documents/index.json) -join '') | ConvertFrom-Json
$id = $rows[0].id
$before = ((& $Adb shell run-as kr.documentstudio cat "files/documents/$id/state.json") -join '') | ConvertFrom-Json
if ($before.edits.text -ne 'RecoverySmoke123' -or $before.edits.strokes.Count -ne 1) { throw 'Initial recovery checkpoint mismatch' }
Invoke-Adb shell am force-stop kr.documentstudio
Invoke-Adb shell am start -W -n kr.documentstudio/.MainActivity | Out-Null
Tap-Node '새 문서.txt'
Tap-Node '텍스트 편집'
$tree = Read-Ui
$body = $tree.SelectNodes('//node') | Where-Object { $_.'content-desc' -eq '문서 본문' } | Select-Object -First 1
if ($body.text -ne 'RecoverySmoke123') { throw 'Text not restored after force-stop' }
$after = ((& $Adb shell run-as kr.documentstudio cat "files/documents/$id/state.json") -join '') | ConvertFrom-Json
if ($after.edits.strokes.Count -ne 1) { throw 'Ink not restored after force-stop' }
Write-Output 'PASS: force-stop -> reopen -> text and ink recovery'

