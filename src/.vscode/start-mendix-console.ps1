$ErrorActionPreference = 'Stop'

$consolePath = "C:/Program Files/Mendix/10.21.0.64362/modeler/MendixConsoleLog.exe"

if (!(Test-Path $consolePath)) {
    throw "MendixConsoleLog.exe not found at $consolePath"
}

if (-not (Get-Process -Name "MendixConsoleLog" -ErrorAction SilentlyContinue)) {
    Start-Process -FilePath $consolePath
}
