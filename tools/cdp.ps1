# Minimal CDP client: drive headless Chrome with Runtime.evaluate to collect real rendered DOM evidence.
#
# Usage:
#   . .\tools\cdp.ps1
#   Start-Chrome -Port 9335 | Out-Null
#   $tab = Start-Tab -Port 9335 -Url 'http://127.0.0.1:5174'
#   (Eval-Js -Port 9335 -Tab $tab -Expr "document.title").value
#   Stop-MyChrome -Port 9335      # closes ONLY the debug instance started here
#
# ==========================================================================
# DO NOT run `Get-Process chrome | Stop-Process` or `taskkill /IM chrome.exe`.
# That kills the USER'S OWN browser too (their tabs and logins are lost).
# Start-Chrome already launches with its own --user-data-dir and its own debug
# port, so it never touches the user's profile. To clean up, use
# Stop-MyChrome -Port <port>: it finds the PID listening on that debug port and
# closes only that process tree.
# Keep this file ASCII-only: non-ASCII bytes in comments broke PowerShell's
# parser once (stray quote/brace interpretation) and broke the whole script.
# ==========================================================================
param()

$script:ChromeExe = 'C:\Program Files\Google\Chrome\Application\chrome.exe'
$script:StartedByMe = @{}

function Start-Chrome {
    param([int]$Port = 9335, [string]$Profile = "$env:TEMP\chrome-cdp-fuse", [int]$W = 1440, [int]$H = 900)
    if (-not (Test-Path $script:ChromeExe)) { throw "chrome.exe not found: $($script:ChromeExe)" }
    $alive = $false
    try { Invoke-WebRequest "http://127.0.0.1:$Port/json/version" -TimeoutSec 2 -UseBasicParsing | Out-Null; $alive = $true } catch { }
    if (-not $alive) {
        # Own user-data-dir and own debug port: the user's browser is untouched.
        $p = Start-Process -FilePath $script:ChromeExe -ArgumentList @(
            '--headless=new', '--disable-gpu', '--no-first-run', '--disable-http-cache',
            "--user-data-dir=$Profile", "--remote-debugging-port=$Port", "--window-size=$W,$H", 'about:blank'
        ) -WindowStyle Hidden -PassThru
        $script:StartedByMe[$Port] = $p.Id
        for ($i = 0; $i -lt 40; $i++) {
            Start-Sleep -Milliseconds 500
            try { Invoke-WebRequest "http://127.0.0.1:$Port/json/version" -TimeoutSec 2 -UseBasicParsing | Out-Null; $alive = $true; break } catch { }
        }
    }
    if (-not $alive) { throw "chrome debug port $Port not ready" }
    return $true
}

# Close ONLY the instance listening on this debug port. If nothing listens, do
# nothing. Matching by port (not by image name) is what keeps the user's
# browser out of harm's way.
function Stop-MyChrome {
    param([int]$Port = 9335)
    $target = $null
    try {
        $target = (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop | Select-Object -First 1).OwningProcess
    } catch { }
    if (-not $target) {
        Write-Host "  [cdp] no debug instance on port $Port"
        return
    }
    $pname = (Get-Process -Id $target -ErrorAction SilentlyContinue).ProcessName
    if ($pname -ne 'chrome') {
        Write-Host "  [cdp] port $Port belongs to '$pname', not a chrome instance from this script -- leaving it alone"
        return
    }
    # /T kills this tree (renderer/GPU children) and nothing else.
    Start-Process -FilePath 'taskkill.exe' -ArgumentList @('/F', '/T', '/PID', "$target") -WindowStyle Hidden -Wait | Out-Null
    Write-Host "  [cdp] closed debug instance pid=$target (port $Port)"
}

function Start-Tab {
    param([int]$Port = 9335, [string]$Url = 'about:blank')
    $enc = [uri]::EscapeDataString($Url)
    $r = Invoke-WebRequest "http://127.0.0.1:$Port/json/new?$enc" -Method Put -TimeoutSec 15 -UseBasicParsing
    return ([System.Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray()) | ConvertFrom-Json)
}

function Close-Tab {
    param([int]$Port = 9335, [string]$Id)
    try { Invoke-WebRequest "http://127.0.0.1:$Port/json/close/$Id" -TimeoutSec 10 -UseBasicParsing | Out-Null } catch { }
}

function Eval-Js {
    param([int]$Port = 9335, [object]$Tab, [string]$Expr, [int]$TimeoutMs = 60000)
    $ws = [System.Net.WebSockets.ClientWebSocket]::new()
    $cts = [System.Threading.CancellationTokenSource]::new($TimeoutMs)
    $ws.ConnectAsync([uri]$Tab.webSocketDebuggerUrl, $cts.Token).GetAwaiter().GetResult()
    $payload = @{ id = 1; method = 'Runtime.evaluate'; params = @{
        expression = $Expr; awaitPromise = $true; returnByValue = $true; userGesture = $true
    } } | ConvertTo-Json -Depth 10 -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($payload)
    $ws.SendAsync([System.ArraySegment[byte]]::new($bytes), [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $cts.Token).GetAwaiter().GetResult()

    $buf = New-Object byte[] 1048576
    $seg = [System.ArraySegment[byte]]::new($buf)
    $sb = [System.Text.StringBuilder]::new()
    while ($true) {
        $res = $ws.ReceiveAsync($seg, $cts.Token).GetAwaiter().GetResult()
        [void]$sb.Append([System.Text.Encoding]::UTF8.GetString($buf, 0, $res.Count))
        if ($res.EndOfMessage) { break }
    }
    $ws.CloseAsync([System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure, 'ok', [System.Threading.CancellationToken]::None).GetAwaiter().GetResult()
    $msg = $sb.ToString() | ConvertFrom-Json
    if ($msg.result.exceptionDetails) {
        return @{ ok = $false; error = $msg.result.exceptionDetails.text }
    }
    return @{ ok = $true; value = $msg.result.result.value }
}

function Sleep-Until {
    param([int]$Port = 9335, [object]$Tab, [string]$Expr, [int]$TimeoutMs = 30000)
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while ($sw.ElapsedMilliseconds -lt $TimeoutMs) {
        $r = Eval-Js -Port $Port -Tab $Tab -Expr $Expr -TimeoutMs 20000
        if ($r.ok -and $r.value) { return $true }
        Start-Sleep -Milliseconds 700
    }
    return $false
}
