$ErrorActionPreference = 'SilentlyContinue'
Add-Type -TypeDefinition @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class PoringWindowState {
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }
  [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Auto)] public struct MONITORINFO { public int cbSize; public RECT rcMonitor; public RECT rcWork; public int dwFlags; }
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern short GetAsyncKeyState(int key);
  [DllImport("user32.dll", CharSet=CharSet.Auto)] public static extern int GetClassName(IntPtr hWnd, StringBuilder className, int maxCount);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
  [DllImport("user32.dll")] public static extern bool IsZoomed(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);
  [DllImport("user32.dll")] public static extern IntPtr MonitorFromWindow(IntPtr hWnd, uint flags);
  [DllImport("user32.dll", CharSet=CharSet.Auto)] public static extern bool GetMonitorInfo(IntPtr monitor, ref MONITORINFO info);
}
'@

$monitorProcessId = [uint32]$PID
$script:cachedWifi = @{ on = $false; connected = $false; ssid = ''; signal = $null; available = @() }
$script:cachedBluetooth = @{ on = $false; known = $true; connected = @(); available = @() }
$nextWifi = [DateTime]::MinValue
$nextBluetooth = [DateTime]::MinValue
$lastToggleCombo = $false

function Read-Wifi {
  $iface = (& netsh wlan show interfaces 2>$null) -join [Environment]::NewLine
  $connected = $iface -match '(?im)^\s*State\s*:\s*connected\s*$'
  $ssidMatch = [regex]::Match($iface, '(?im)^\s*SSID\s*:\s*(.+?)\s*$')
  $signalMatch = [regex]::Match($iface, '(?im)^\s*Signal\s*:\s*(\d+)\s*%')
  $networks = (& netsh wlan show networks mode=bssid 2>$null) -join [Environment]::NewLine
  $found = @{}
  $current = ''
  foreach ($line in ($networks -split [Environment]::NewLine)) {
    if ($line -match '^\s*SSID\s+\d+\s*:\s*(.*?)\s*$') {
      $current = $Matches[1]
      if (-not $found.ContainsKey($current)) { $found[$current] = 0 }
    } elseif ($current -and $line -match '^\s*Signal\s*:\s*(\d+)\s*%') {
      $found[$current] = [Math]::Max([int]$found[$current], [int]$Matches[1])
    }
  }
  $name = if ($connected -and $ssidMatch.Success) { $ssidMatch.Groups[1].Value.Trim() } else { '' }
  $strength = if ($connected -and $signalMatch.Success) { [int]$signalMatch.Groups[1].Value } else { $null }
  $available = @($found.GetEnumerator() | Where-Object { $_.Key -and $_.Key -ne $name } | Sort-Object Value -Descending | Select-Object -First 8 | ForEach-Object { @{ name = $_.Key; signal = [int]$_.Value } })
  $radioOn = ($iface -match '(?im)^\s*Radio status.*:\s*On\s*/\s*On\s*$') -or ($iface -match '(?im)\bSoftware\s+On\b')
  $script:cachedWifi = @{ on = ([bool]$connected -or [bool]$radioOn); connected = [bool]$connected; ssid = $name; signal = $strength; available = $available }
}

function Read-Bluetooth {
  $devices = @(Get-PnpDevice -Class Bluetooth -PresentOnly | Where-Object { $_.FriendlyName -and $_.FriendlyName -notmatch 'Bluetooth (Radio|Device|Enumerator)|RFCOMM|AVRCP|LE Enumerator' })
  $connected = [System.Collections.Generic.List[string]]::new()
  $available = [System.Collections.Generic.List[string]]::new()
  foreach ($device in $devices) {
    $isConnected = $false
    $prop = Get-PnpDeviceProperty -InstanceId $device.InstanceId -KeyName 'DEVPKEY_Bluetooth_Device_IsConnected'
    if ($null -ne $prop) { $isConnected = [bool]$prop.Data }
    if ($isConnected) { $connected.Add($device.FriendlyName) }
    elseif ($device.Status -eq 'OK') { $available.Add($device.FriendlyName) }
  }
  $radio = @(Get-PnpDevice -Class Bluetooth | Where-Object { $_.FriendlyName -match 'Bluetooth (Radio|Adapter)' -and $_.Status -eq 'OK' })
  $script:cachedBluetooth = @{ on = ($radio.Count -gt 0); known = $true; connected = @($connected | Select-Object -Unique); available = @($available | Select-Object -Unique -First 12) }
}

while ($true) {
  $window = [PoringWindowState]::GetForegroundWindow()
  $rect = New-Object PoringWindowState+RECT
  $monitorInfo = New-Object PoringWindowState+MONITORINFO
  $monitorInfo.cbSize = [Runtime.InteropServices.Marshal]::SizeOf($monitorInfo)
  $monitor = [PoringWindowState]::MonitorFromWindow($window, 2)
  [void][PoringWindowState]::GetMonitorInfo($monitor, [ref]$monitorInfo)
  [void][PoringWindowState]::GetWindowRect($window, [ref]$rect)
  $foregroundPid = [uint32]0
  [void][PoringWindowState]::GetWindowThreadProcessId($window, [ref]$foregroundPid)
  $classBuffer = [System.Text.StringBuilder]::new(256)
  [void][PoringWindowState]::GetClassName($window, $classBuffer, $classBuffer.Capacity)
  $windowClass = $classBuffer.ToString()
  $combo = (([PoringWindowState]::GetAsyncKeyState(0x11) -band 0x8000) -ne 0) -and (([PoringWindowState]::GetAsyncKeyState(0x12) -band 0x8000) -ne 0)
  $toggleHotkey = $combo -and -not $lastToggleCombo
  $lastToggleCombo = $combo
  $ours = $foregroundPid -eq $monitorProcessId
  $shellWindow = $windowClass -in @('Progman','WorkerW','Shell_TrayWnd','Shell_SecondaryTrayWnd','NotifyIconOverflowWindow')
  $coversMonitor = ($rect.Left -le $monitorInfo.rcMonitor.Left + 2 -and $rect.Top -le $monitorInfo.rcMonitor.Top + 2 -and $rect.Right -ge $monitorInfo.rcMonitor.Right - 2 -and $rect.Bottom -ge $monitorInfo.rcMonitor.Bottom - 2)
  $maximized = (-not $ours) -and [PoringWindowState]::IsZoomed($window)
  $fullScreen = (-not $ours) -and (-not $shellWindow) -and (-not $maximized) -and $coversMonitor
  if ([DateTime]::Now -ge $nextWifi) { Read-Wifi; $nextWifi = [DateTime]::Now.AddSeconds(7) }
  if ([DateTime]::Now -ge $nextBluetooth) { Read-Bluetooth; $nextBluetooth = [DateTime]::Now.AddSeconds(12) }
  [pscustomobject]@{ type = 'platform'; hotkey = $toggleHotkey; fullScreen = $fullScreen; maximized = $maximized; rect = @{ x=$rect.Left; y=$rect.Top; width=($rect.Right-$rect.Left); height=($rect.Bottom-$rect.Top) }; wifi = $script:cachedWifi; bluetooth = $script:cachedBluetooth } | ConvertTo-Json -Compress -Depth 5
  Start-Sleep -Milliseconds 90
}
