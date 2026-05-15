#requires -Version 5
<#
.SYNOPSIS
  Dev-loop helper for the Windows Goals app: kill + deploy + (optionally
  click an element) + screenshot. Wraps the winapp CLI + Process cmdlets so
  the agent can run a full cycle with a single permission rule.

.PARAMETER Action
  cycle      Kill running app, redeploy from bin\$Config, return PID.
  screenshot Screenshot the current main window into windows\screenshot.png.
  inspect    Dump the UIA tree.
  click      Invoke a UIA element by selector (passed via -Selector).
  kill       Stop any running Goals.Windows process.

.PARAMETER Selector
  UIA selector for "click" / "inspect" actions.

.PARAMETER Config
  Build configuration to deploy. DEV (default) hits localhost; PROD hits EC2.

.EXAMPLE
  pwsh -File windows\scripts\dev.ps1 cycle
  pwsh -File windows\scripts\dev.ps1 cycle -Config PROD
  pwsh -File windows\scripts\dev.ps1 click -Selector TabGoals
  pwsh -File windows\scripts\dev.ps1 screenshot
#>
param(
    [Parameter(Position = 0, Mandatory = $true)]
    [ValidateSet('cycle', 'screenshot', 'inspect', 'click', 'invoke', 'search', 'kill')]
    [string]$Action,
    [string]$Selector = 'main',
    [ValidateSet('DEV', 'PROD')]
    [string]$Config = 'DEV'
)

$ErrorActionPreference = 'Stop'

$WinApp = 'C:\Users\Dima\.nuget\packages\microsoft.windows.sdk.buildtools.winapp\0.3.1\tools\win-x64\winapp.exe'
$AppXFolder = "D:\Projects\goals\windows\bin\$Config\net10.0-windows10.0.26100.0\win-x64"

function Stop-Goals {
    Get-Process Goals.Windows -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 600
}

function Get-Hwnd {
    $output = & $WinApp ui list-windows --app Goals.Windows 2>&1 | Out-String
    $m = [regex]::Match($output, 'HWND\s+(\d+)')
    if ($m.Success) { return $m.Groups[1].Value }
    return $null
}

switch ($Action) {
    'kill' {
        Stop-Goals
        Write-Output 'killed'
    }
    'cycle' {
        Stop-Goals
        # DEV is unpackaged (WindowsPackageType=None) — `winapp run` expects an
        # MSIX, so just launch the .exe directly. PROD stays packaged and uses
        # the MSIX register-and-launch path.
        if ($Config -eq 'DEV') {
            Start-Process "$AppXFolder\Goals.Windows.exe"
        } else {
            & $WinApp run $AppXFolder --detach 2>&1 | Select-Object -Last 1
        }
        Start-Sleep -Seconds 5
    }
    'screenshot' {
        $hwnd = Get-Hwnd
        if (-not $hwnd) {
            Write-Error 'No Goals.Windows window found'
            exit 1
        }
        & $WinApp ui screenshot --app Goals.Windows -w $hwnd 2>&1 | Select-Object -Last 2
    }
    'inspect' {
        & $WinApp ui inspect $Selector --app Goals.Windows --depth 10 2>&1
    }
    'click' {
        # InvokePattern (treats the element like a button press). Same as 'invoke'.
        & $WinApp ui invoke $Selector --app Goals.Windows 2>&1 | Select-Object -Last 1
        Start-Sleep -Milliseconds 600
    }
    'invoke' {
        & $WinApp ui invoke $Selector --app Goals.Windows 2>&1 | Select-Object -Last 1
        Start-Sleep -Milliseconds 600
    }
    'search' {
        & $WinApp ui search $Selector --app Goals.Windows 2>&1
    }
}
