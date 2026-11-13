<#
.SYNOPSIS
    车机调试保活 —— 防止 scrcpy 连上一会儿车机就息屏 / 深睡。

.DESCRIPTION
    分三层，按需组合：

    1) svc power stayon true
       挡住 Android 的息屏超时（连 adb 时保持唤醒）。最轻，一般够用。
    2) -WakeLock
       root 写 /sys/power/wake_lock，挡住内核 suspend。比 stayon 强，
       能扛住车机的车库模式（GarageMode）深睡。⚠️ 会持续耗车机电瓶，用完记得 -Restore。
    3) 循环巡检
       每隔 $IntervalSec 秒查一次，发现已经 Asleep 就直接 keyevent 唤醒，
       并把 stayon 重新打开（车机重连 / 重启后需要重设）。

.EXAMPLE
    .\keep-awake.ps1
        只开 stayon + 每 60 秒巡检。

.EXAMPLE
    .\keep-awake.ps1 -WakeLock
        stayon + 内核 wake_lock + 巡检（最稳，长期停放调试用这个）。

.EXAMPLE
    .\keep-awake.ps1 -Restore
        收工：stayon 关掉、wake_lock 释放、screen_off_timeout 还原成 0。
#>
param(
    [string]$Device = "10.0.0.1:5555",
    [int]$IntervalSec = 60,
    [switch]$WakeLock,
    [switch]$Restore
)

$ErrorActionPreference = "Continue"
$adb = "D:\ARM\Android\Sdk\platform-tools\adb.exe"
$LockName = "p145-debug-keepawake"

function Adb-Sh([string]$cmd, [int]$timeoutMs = 15000) {
    return (& $adb -s $Device shell $cmd 2>&1) -join "`n"
}

function Ensure-Device {
    $state = ((& $adb -s $Device get-state 2>&1) -join "").Trim()
    if ($state -ne "device") {
        Write-Host "[连接] adb 未就绪，重连 $Device …" -ForegroundColor Yellow
        & $adb connect $Device 2>&1 | Out-Null
        Start-Sleep 2
        $state = ((& $adb -s $Device get-state 2>&1) -join "").Trim()
        if ($state -ne "device") {
            Write-Host "[连接] 仍然连不上，等下一轮" -ForegroundColor Red
            return $false
        }
    }
    return $true
}

function Get-Power([string]$key) {
    $p = Adb-Sh "dumpsys power | grep -E '$key'"
    return $p.Trim()
}

# ---------------------------------------------------------------- Restore
if ($Restore) {
    if (-not (Ensure-Device)) { exit 1 }
    Write-Host "[还原] 关闭 stayon" -ForegroundColor Cyan
    Adb-Sh "svc power stayon false" | Out-Null
    Write-Host "[还原] 释放 wake_lock" -ForegroundColor Cyan
    Adb-Sh "echo $LockName > /sys/power/wake_unlock" | Out-Null
    Write-Host "[还原] screen_off_timeout 恢复 0（车机原值）" -ForegroundColor Cyan
    Adb-Sh "settings put system screen_off_timeout 0" | Out-Null
    Write-Host ""
    Write-Host (Get-Power "mStayOn=|mWakefulness=")
    Write-Host (Adb-Sh "cat /sys/power/wake_lock")
    exit 0
}

# ---------------------------------------------------------------- 初始化
Write-Host "=== 车机保活 (Ctrl+C 退出，退出后用 -Restore 收工) ===" -ForegroundColor Green
Write-Host "设备: $Device  巡检间隔: ${IntervalSec}s  内核锁: $(if($WakeLock){'开'}else{'关'})"
Start-Sleep 1
if (-not (Ensure-Device)) { exit 1 }

Adb-Sh "svc power stayon true" | Out-Null
Write-Host "[初始化] svc power stayon true"

if ($WakeLock) {
    Adb-Sh "echo $LockName > /sys/power/wake_lock" | Out-Null
    Write-Host "[初始化] wake_lock: $LockName"
    $locks = Adb-Sh "cat /sys/power/wake_lock"
    Write-Host ("        当前锁: {0}" -f $locks.Trim())
}

$round = 0
try {
    while ($true) {
        $round++
        if (-not (Ensure-Device)) { Start-Sleep $IntervalSec; continue }

        $wake = Get-Power "mWakefulness="
        $stay = Get-Power "mStayOn="
        $state = if ($wake -match "mWakefulness=(\w+)") { $matches[1] } else { "?" }
        $stayOn = if ($stay -match "mStayOn=(\w+)") { $matches[1] } else { "?" }
        $ts = Get-Date -Format "HH:mm:ss"

        if ($state -ne "Awake") {
            Write-Host ("[{0}] 检测到 {1} → 唤醒" -f $ts, $state) -ForegroundColor Yellow
            Adb-Sh "input keyevent KEYCODE_WAKEUP" | Out-Null
            Start-Sleep 1
        }
        if ($stayOn -ne "true") {
            Write-Host ("[{0}] stayon 丢了 → 重设" -f $ts) -ForegroundColor Yellow
            Adb-Sh "svc power stayon true" | Out-Null
        }
        if ($WakeLock) {
            $locks = Adb-Sh "cat /sys/power/wake_lock"
            if ($locks -notmatch [regex]::Escape($LockName)) {
                Write-Host ("[{0}] wake_lock 丢了 → 重新持有" -f $ts) -ForegroundColor Yellow
                Adb-Sh "echo $LockName > /sys/power/wake_lock" | Out-Null
            }
        }

        Write-Host ("[{0}] #{1} {2} | stayon={3}" -f $ts, $round, $state, $stayOn) -ForegroundColor DarkGray
        Start-Sleep $IntervalSec
    }
}
finally {
    Write-Host ""
    Write-Host "已退出。清理请执行: .\keep-awake.ps1 -Restore" -ForegroundColor Cyan
}
