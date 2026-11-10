[CmdletBinding()]
param([switch]$Create, [switch]$SelfCheck)
$ErrorActionPreference = 'Stop'
$sender = Join-Path $PSScriptRoot 'Send-DemoNotification.ps1'
if (-not (Test-Path -LiteralPath $sender -PathType Leaf)) { throw 'Falta el script de envío.' }
$target = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$arguments = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -Send' -f $sender
if ($SelfCheck) {
    & $sender -SelfCheck
    if ($arguments -notmatch '-Send$') { throw 'El acceso directo no apunta al envío explícito.' }
    Write-Output 'Self-check del acceso directo correcto; no se creó ningún archivo.'
    return
}
if (-not $Create) {
    Write-Output "DRY RUN: Solventa - Enviar aviso.lnk -> $target $arguments"
    Write-Output 'Usa -Create para crear el acceso directo en el escritorio.'
    return
}
$desktop = [Environment]::GetFolderPath('Desktop')
$path = Join-Path $desktop 'Solventa - Enviar aviso.lnk'
if (Test-Path -LiteralPath $path) { throw "Ya existe el acceso directo: $path" }
$shell = New-Object -ComObject WScript.Shell
try {
    $shortcut = $shell.CreateShortcut($path)
    $shortcut.TargetPath = $target
    $shortcut.Arguments = $arguments
    $shortcut.WorkingDirectory = Split-Path $PSScriptRoot -Parent
    $shortcut.Description = 'Enviar un aviso a Solventa Flujos'
    $shortcut.Save()
    if (-not (Test-Path -LiteralPath $path)) { throw 'No se pudo guardar el acceso directo.' }
    Write-Output $path
} finally {
    [void][Runtime.InteropServices.Marshal]::ReleaseComObject($shell)
}
