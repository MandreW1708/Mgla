# Deploy stealth path pool + relay.py + decoy to DE1 / DE2 / FI.
# Passwords via env (never commit secrets):
#   $env:MGLA_DE1_PW=...; $env:MGLA_DE2_PW=...; $env:MGLA_FI_PW=...
# Optional host keys: MGLA_FI_HOSTKEY=SHA256:...
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Pscp = "C:\Program Files\PuTTY\pscp.exe"
$Plink = "C:\Program Files\PuTTY\plink.exe"

function Require-Env([string]$Name) {
  $v = [Environment]::GetEnvironmentVariable($Name)
  if ([string]::IsNullOrWhiteSpace($v)) {
    throw "Set env $Name before deploy (root password / secret)."
  }
  return $v
}

$targets = @(
  @{ Name = "DE1"; Host = "31.77.218.160"; PwEnv = "MGLA_DE1_PW"; Site = "/etc/nginx/sites-enabled/mglabot"; HostKey = $env:MGLA_DE1_HOSTKEY; Sni = "mglabot.mooo.com" },
  @{ Name = "DE2"; Host = "194.58.33.22";  PwEnv = "MGLA_DE2_PW"; Site = "/etc/nginx/sites-enabled/mglahub"; HostKey = $env:MGLA_DE2_HOSTKEY; Sni = "mglahub.mooo.com" },
  @{ Name = "FI";  Host = "62.60.255.83";  PwEnv = "MGLA_FI_PW";  Site = ""; HostKey = $(if ($env:MGLA_FI_HOSTKEY) { $env:MGLA_FI_HOSTKEY } else { "SHA256:vCElve+S7QySkufZtIYiZD6/mQIK/Hgua2Nt2TLdvbw" }); Sni = "fin.mgla.mooo.com" }
)

function Invoke-Remote($HostAddr, $Pw, $HostKey, $Cmd) {
  $args = @("-ssh", "-batch", "-pw", $Pw)
  if ($HostKey) { $args += @("-hostkey", $HostKey) }
  $args += @("root@$HostAddr", $Cmd)
  & $Plink @args
  if ($LASTEXITCODE -ne 0) { throw "plink failed on $HostAddr exit=$LASTEXITCODE" }
}

foreach ($t in $targets) {
  $pw = Require-Env $t.PwEnv
  Write-Host "==== $($t.Name) $($t.Host) ====" -ForegroundColor Cyan

  $hkArgs = @()
  if ($t.HostKey) { $hkArgs = @("-hostkey", $t.HostKey) }

  Invoke-Remote $t.Host $pw $t.HostKey "rm -rf /tmp/mgla-stealth-deploy; mkdir -p /tmp/mgla-stealth-deploy/decoy-tgweb"

  $pscpArgs = @("-batch", "-pw", $pw) + $hkArgs + @(
    "$Root\relay.py", "$Root\deploy-stealth-paths.sh", "root@$($t.Host):/tmp/mgla-stealth-deploy/"
  )
  & $Pscp @pscpArgs
  if ($LASTEXITCODE -ne 0) { throw "pscp core failed $($t.Name)" }

  $pscpDecoy = @("-batch", "-pw", $pw) + $hkArgs + @(
    "$Root\decoy-tgweb\index.html", "root@$($t.Host):/tmp/mgla-stealth-deploy/decoy-tgweb/"
  )
  & $Pscp @pscpDecoy
  if ($LASTEXITCODE -ne 0) { throw "pscp decoy failed $($t.Name)" }

  $siteArg = if ($t.Site) { $t.Site } else { "" }
  # Keep remote command single-line (PowerShell CRLF breaks bash `set -o pipefail`).
  if ($siteArg) {
    $remoteCmd = "cd /tmp/mgla-stealth-deploy; sed -i 's/\r`$//' deploy-stealth-paths.sh relay.py; chmod +x deploy-stealth-paths.sh; bash deploy-stealth-paths.sh $siteArg; systemctl is-active mgla-ws-relay; echo DONE_$($t.Name)"
  } else {
    $remoteCmd = "cd /tmp/mgla-stealth-deploy; sed -i 's/\r`$//' deploy-stealth-paths.sh relay.py; chmod +x deploy-stealth-paths.sh; bash deploy-stealth-paths.sh; systemctl is-active mgla-ws-relay; echo DONE_$($t.Name)"
  }
  Invoke-Remote $t.Host $pw $t.HostKey $remoteCmd
  Write-Host "==== $($t.Name) OK ====" -ForegroundColor Green
}
Write-Host "ALL STEALTH DEPLOYS DONE" -ForegroundColor Green
