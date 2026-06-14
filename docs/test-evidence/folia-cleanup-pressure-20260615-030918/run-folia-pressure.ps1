$ErrorActionPreference = 'Stop'
$server = 'E:\server_work\folia1.21.8'
$repo = 'C:\Users\pc\Desktop\ai开发插件\待重构插件\WorldListTrashCan重构\refactor-workspace'
$evidence = Get-Content -LiteralPath (Join-Path $repo 'build\last-folia-pressure-evidence.txt') -Encoding UTF8
$java = Join-Path $repo 'build\tools\microsoft-jdk-25.0.3\bin\java.exe'
if (!(Test-Path $java)) { $java = 'java.exe' }
$consoleLog = Join-Path $evidence 'server-console.log'
$commandsLog = Join-Path $evidence 'commands.log'
$summaryPath = Join-Path $evidence 'summary.json'
$ErrorActionPreference = 'Stop'
Set-Content -LiteralPath $consoleLog -Value '' -Encoding UTF8
Set-Content -LiteralPath $commandsLog -Value '' -Encoding UTF8
$startInfo = [System.Diagnostics.ProcessStartInfo]::new()
$startInfo.FileName = $java
$startInfo.WorkingDirectory = $server
$startInfo.Arguments = '-Xms1024M -Xmx4096M --add-opens java.base/java.net=ALL-UNNAMED -XX:+UnlockDiagnosticVMOptions -XX:-UseAESCTRIntrinsics -jar folia-1.21.8-6.jar nogui'
$startInfo.UseShellExecute = $false
$startInfo.RedirectStandardInput = $true
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true
$startInfo.StandardOutputEncoding = [System.Text.Encoding]::UTF8
$startInfo.StandardErrorEncoding = [System.Text.Encoding]::UTF8
$process = [System.Diagnostics.Process]::new()
$process.StartInfo = $startInfo
$script:lines = New-Object System.Collections.Generic.List[string]
$lock = New-Object object
$handler = [System.Diagnostics.DataReceivedEventHandler]{
    param($sender, $eventArgs)
    if ($null -ne $eventArgs.Data) {
        $line = $eventArgs.Data
        [System.Threading.Monitor]::Enter($lock)
        try { $script:lines.Add($line) } finally { [System.Threading.Monitor]::Exit($lock) }
        Add-Content -LiteralPath $consoleLog -Value $line -Encoding UTF8
    }
}
$process.add_OutputDataReceived($handler)
$process.add_ErrorDataReceived($handler)
$null = $process.Start()
$process.BeginOutputReadLine()
$process.BeginErrorReadLine()
function Add-CommandLog([string]$message) {
    Add-Content -LiteralPath $commandsLog -Value ('[{0}] {1}' -f (Get-Date -Format 'HH:mm:ss.fff'), $message) -Encoding UTF8
}
function Send-ServerCommand([string]$command) {
    Add-CommandLog ('> ' + $command)
    $process.StandardInput.WriteLine($command)
    $process.StandardInput.Flush()
}
function Get-LogText() {
    if (!(Test-Path $consoleLog)) { return '' }
    return [IO.File]::ReadAllText($consoleLog, [Text.Encoding]::UTF8)
}
function Count-Log([string]$pattern) {
    $text = Get-LogText
    return ([regex]::Matches($text, $pattern)).Count
}
function Wait-LogCount([string]$name, [string]$pattern, [int]$baseline, [int]$targetIncrease, [int]$timeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    do {
        if ($process.HasExited) { throw "Server exited while waiting for $name" }
        $count = Count-Log $pattern
        if ($count -ge ($baseline + $targetIncrease)) {
            Add-CommandLog ("PASS wait $name count=$count baseline=$baseline targetIncrease=$targetIncrease")
            return $true
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    $count = Count-Log $pattern
    Add-CommandLog ("FAIL wait $name count=$count baseline=$baseline targetIncrease=$targetIncrease")
    return $false
}
function Wait-LogPattern([string]$name, [string]$pattern, [int]$timeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    do {
        if ($process.HasExited) { throw "Server exited while waiting for $name" }
        if ((Get-LogText) -match $pattern) {
            Add-CommandLog ("PASS wait $name")
            return $true
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    Add-CommandLog ("FAIL wait $name")
    return $false
}
$checks = [ordered]@{}
$spawnCount = 32000
try {
    $checks.ready = Wait-LogPattern 'server-ready' 'Done \(' 180
    Send-ServerCommand 'gamerule doMobSpawning false'
    Send-ServerCommand 'gamerule sendCommandFeedback true'
    Send-ServerCommand 'gamerule logAdminCommands false'
    Send-ServerCommand 'kill @e[tag=ai_wtc_pressure]'
    Start-Sleep -Seconds 2
    Send-ServerCommand 'blwtc platform'
    Send-ServerCommand 'blwtc reload'
    Start-Sleep -Seconds 2
    $beforeNoRefresh = Count-Log '公共垃圾桶不会自动刷新'
    $beforeNormal = Count-Log '\[FoliaCleanup\].*timedOut=false'
    Send-ServerCommand 'say AI_WTC_BASELINE_CLEAR_START'
    Send-ServerCommand 'blwtc clear'
    $checks.baselineNoTimeout = Wait-LogCount 'baseline timedOut=false' '\[FoliaCleanup\].*timedOut=false' $beforeNormal 1 40
    $checks.noRefreshText = Wait-LogCount 'public no refresh text' '公共垃圾桶不会自动刷新' $beforeNoRefresh 1 20
    Send-ServerCommand 'scoreboard objectives add ai_wtc dummy'
    Send-ServerCommand 'scoreboard players set #pressure ai_wtc 0'
    Send-ServerCommand 'gamerule sendCommandFeedback false'
    Add-CommandLog "spawning armor stands count=$spawnCount tag=ai_wtc_pressure"
    for ($i = 1; $i -le $spawnCount; $i++) {
        $process.StandardInput.WriteLine('execute in minecraft:overworld run summon minecraft:armor_stand 0 80 0 {NoGravity:1b,Invisible:1b,Invulnerable:1b,Tags:["ai_wtc_pressure"]}')
        if (($i % 50) -eq 0) {
            $process.StandardInput.Flush()
            Start-Sleep -Milliseconds 20
        }
        if (($i % 5000) -eq 0) {
            Add-CommandLog "spawn-progress $i/$spawnCount"
        }
    }
    $process.StandardInput.Flush()
    Send-ServerCommand 'gamerule sendCommandFeedback true'
    Send-ServerCommand 'say AI_WTC_PRESSURE_SPAWN_DONE_32000'
    $checks.spawnMarker = Wait-LogPattern 'spawn marker' 'AI_WTC_PRESSURE_SPAWN_DONE_32000' 300
    Send-ServerCommand 'scoreboard players set #pressure ai_wtc 0'
    Send-ServerCommand 'execute as @e[tag=ai_wtc_pressure,type=minecraft:armor_stand] run scoreboard players add #pressure ai_wtc 1'
    Send-ServerCommand 'scoreboard players get #pressure ai_wtc'
    Start-Sleep -Seconds 5
    $beforeTimeout = Count-Log '\[FoliaCleanup\].*timedOut=true'
    $beforeRunning = Count-Log '上一轮 region-safe 清理仍在运行'
    Send-ServerCommand 'say AI_WTC_PRESSURE_CLEAR_START'
    Send-ServerCommand 'blwtc clear'
    Start-Sleep -Milliseconds 200
    Send-ServerCommand 'blwtc clear'
    $checks.runningGuard = Wait-LogCount 'running guard' '上一轮 region-safe 清理仍在运行' $beforeRunning 1 20
    $checks.firstTimeout = Wait-LogCount 'first pressure timeout' '\[FoliaCleanup\].*timedOut=true' $beforeTimeout 1 90
    $beforeTimeout2 = Count-Log '\[FoliaCleanup\].*timedOut=true'
    $beforeStarted = Count-Log '已启动 Folia region-safe 清理'
    Send-ServerCommand 'say AI_WTC_AFTER_TIMEOUT_RETRY'
    Send-ServerCommand 'blwtc clear'
    $checks.retryStarted = Wait-LogCount 'retry clear started' '已启动 Folia region-safe 清理' $beforeStarted 1 20
    $checks.secondTimeout = Wait-LogCount 'second pressure timeout' '\[FoliaCleanup\].*timedOut=true' $beforeTimeout2 1 90
    Send-ServerCommand 'scoreboard players set #pressure ai_wtc 0'
    Send-ServerCommand 'execute as @e[tag=ai_wtc_pressure,type=minecraft:armor_stand] run scoreboard players add #pressure ai_wtc 1'
    Send-ServerCommand 'scoreboard players get #pressure ai_wtc'
    Start-Sleep -Seconds 3
    Send-ServerCommand 'kill @e[tag=ai_wtc_pressure]'
    Start-Sleep -Seconds 3
} finally {
    if (!$process.HasExited) {
        Send-ServerCommand 'stop'
        if (!$process.WaitForExit(45000)) {
            Add-CommandLog 'server did not stop in 45s; killing process'
            $process.Kill()
            $process.WaitForExit(10000) | Out-Null
        }
    }
    Start-Sleep -Seconds 1
    if (Test-Path (Join-Path $server 'logs\latest.log')) {
        Copy-Item -LiteralPath (Join-Path $server 'logs\latest.log') -Destination (Join-Path $evidence 'latest.log') -Force
    }
    $summary = [ordered]@{
        timestamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
        server = $server
        jar = 'BLWorldTrashCan-universal.jar'
        spawnCount = $spawnCount
        checks = $checks
        timedOutTrueCount = Count-Log '\[FoliaCleanup\].*timedOut=true'
        timedOutFalseCount = Count-Log '\[FoliaCleanup\].*timedOut=false'
        runningGuardCount = Count-Log '上一轮 region-safe 清理仍在运行'
        noRefreshTextCount = Count-Log '公共垃圾桶不会自动刷新'
        processExitCode = $process.ExitCode
    }
    ($summary | ConvertTo-Json -Depth 5) | Set-Content -LiteralPath $summaryPath -Encoding UTF8
}
