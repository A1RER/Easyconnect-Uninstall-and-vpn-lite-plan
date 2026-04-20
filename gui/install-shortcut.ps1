# Creates a desktop shortcut that silently launches IntranetVpn.jar via IntranetVpn.vbs.
$guiDir  = Split-Path -Parent $MyInvocation.MyCommand.Path
$vbs     = Join-Path $guiDir 'IntranetVpn.vbs'
$desktop = [Environment]::GetFolderPath('Desktop')
$lnk     = Join-Path $desktop ([char]0x5185 + [char]0x7F51 + ' VPN.lnk')

if (-not (Test-Path $vbs)) {
    Write-Error "Missing $vbs. Run build.bat first, then re-run this script."
    exit 1
}

$ws = New-Object -ComObject WScript.Shell
$sc = $ws.CreateShortcut($lnk)
$sc.TargetPath       = 'wscript.exe'
$sc.Arguments        = '"' + $vbs + '"'
$sc.WorkingDirectory = $guiDir
$edgeExe = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
if (Test-Path $edgeExe) { $sc.IconLocation = "$edgeExe,0" }
$sc.Save()
Write-Host "Created: $lnk"