param(
  [string]$ProviderCommand = "",
  [string[]]$HostArg = @()
)

$ErrorActionPreference = "Stop"
# Windows is an explicit native boundary, not a silent macOS fallback.  The
# host process owns only transport; Kotoba owns provider semantics.
if ($ProviderCommand -and ($HostArg.Count -eq 0)) {
  Write-Error "windows provider command requires -HostArg"
  exit 64
}

switch ($ProviderCommand) {
  'clipboard/read-text' {
    $text = Get-Clipboard -Raw
    [ordered]@{ event = 'provider/result'; provider = $ProviderCommand; text = $text } | ConvertTo-Json -Compress
    exit 0
  }
  'clipboard/write-text' {
    Set-Clipboard -Value ($HostArg -join ' ')
    [ordered]@{ event = 'provider/result'; provider = $ProviderCommand; ok = $true } | ConvertTo-Json -Compress
    exit 0
  }
  'fs/read-text' {
    if ($HostArg.Count -lt 1) { Write-Error 'fs/read-text requires a path'; exit 64 }
    $text = Get-Content -Raw -LiteralPath $HostArg[0]
    [ordered]@{ event = 'provider/result'; provider = $ProviderCommand; text = $text } | ConvertTo-Json -Compress
    exit 0
  }
  'fs/write-text' {
    if ($HostArg.Count -lt 2) { Write-Error 'fs/write-text requires path and text'; exit 64 }
    Set-Content -LiteralPath $HostArg[0] -Value ($HostArg[1..($HostArg.Count - 1)] -join ' ') -NoNewline
    [ordered]@{ event = 'provider/result'; provider = $ProviderCommand; ok = $true } | ConvertTo-Json -Compress
    exit 0
  }
}

$payload = [ordered]@{
  event = "host/ready"
  target = "windows"
  runtime = "native-powershell"
  provider = $ProviderCommand
  args = $HostArg
} | ConvertTo-Json -Compress
Write-Output $payload
exit 0
