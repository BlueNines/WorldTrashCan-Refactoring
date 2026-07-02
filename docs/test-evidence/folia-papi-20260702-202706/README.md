# Folia PAPI 变量验收

- 被测插件: `dist/BLWorldTrashCan-universal.jar`
- 服务端: `E:\server_work\folia1.21.8`
- 前置: `[PAPI]PlaceholderAPI-2.11.7-DEV-null (1).jar`
- 验收命令: `papi parse --null %Wtc_ClearTime%`
- 结论: `FAIL`
- 插件 SHA256: `18d92a709d48fc291dd77c74a3be7d543f5bb20fa0fc01c4c70ab00eb33773d4`

## 关键文件

- `summary.json`: 机器可读结果。
- `logs/server-console.log`: 完整服务端输出。
- `logs/console-commands.log`: 本轮发送的命令。
- `backup/BLWorldTrashCan-universal.before.jar`: 测试前旧 jar 备份。
