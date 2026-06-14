# Folia 清理压力回归证据

- 测试时间：2026-06-15 03:09-03:37
- 测试服务端：`E:\server_work\folia1.21.8`
- 测试产物：`dist/BLWorldTrashCan-universal.jar`
- 最终 SHA256：`A84D2EC08402500505D9BE4F0EDFD404AF86A861AC0DC98F8C86FC53832E7CCC`
- 服务端：Folia 1.21.8-6，Java 25.0.3

## 有效结论

1. 基线清理通过：147 个已加载 chunk 下，`timeout-seconds: 1`、默认批量配置可以 `timedOut=false` 完成，并且 `clear-every-cleanups: -1` 输出“公共垃圾桶不会自动刷新”。
2. 实体 canary 通过：约 5946 个 `ai_wtc_pressure` armor_stand 被 Folia 清理移除，立即第二次 `/blwtc clear` 命中“上一轮仍在运行”保护，服务端未崩溃。
3. 调度压力通过：`chunk-batch-size: 1`、`chunk-batch-delay-ticks: 2`、`timeout-seconds: 1` 下，清理稳定触发 `timedOut=true`，日志包含 `chunksSeen=147`、`chunksScheduled=10`、`pendingTasks=1`。
4. 超时释放通过：第一次 `timedOut=true` 后再次执行 `/blwtc clear`，RCON 返回“已启动 Folia region-safe 清理”，随后出现第二条 `timedOut=true` 汇总，证明 `cleanupRunning` 已释放。
5. 超时提示通过：最终复测中 `timedOut=true` 输出 `Folia 清理超时 | 已释放清理状态，下一轮会继续处理剩余 chunk`，不再误报“清理成功”。

## 失败尝试说明

- `run-folia-pressure.ps1`：PowerShell stdin runner 退出后 Java 进程脱离控制，只用于定位脚本问题，不计入通过。
- `run-folia-pressure-rcon.py`：首次因 UTF-8 BOM 路径失败，已修正为 `utf-8-sig`。
- 逐条 RCON 生成 32000 实体太慢，约 5000 后中止，不计入最终压力结论。
- 指数生成 32768 实体在第 15 轮超过该测试服稳定 RCON 边界，不计入通过。

## 关键文件

- `server-console-dispatch-timeout.log`：最终有效 console 输出。
- `latest-dispatch-timeout.log`：最终有效服务端 latest.log。
- `summary-dispatch-timeout.json`：脚本摘要，注意 `retryStarted=false` 是脚本只查 console 文本导致；RCON 响应和第二条 `timedOut=true` 已证明重试启动。
- `commands-dispatch-timeout.log`：RCON 命令和返回。
- `summary-final.json`：本 README 对应的机器可读结论。
- `cleanup-dispatch-timeout-after-message.yml`：最终压力配置。
- `cleanup-restored-default.yml`、`trash-restored-default.yml`：测试结束后恢复到整包默认配置的副本。
