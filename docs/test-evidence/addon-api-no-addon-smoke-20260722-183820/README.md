# 附属插件 API 无附属插件退化回归

- 被测产物: `dist/WorldListTrashCan-universal.jar`
- SHA256: `9442f3808f9ce8e4a376c220bcc4d4db7882f4cbf4c88475318123acdab1bac2`
- 附属插件: 未安装
- 结论: PASS

## 覆盖

- Paper 1.12.2
- Spigot 26.1.2
- Folia 1.21.8
- 主插件启动、平台分支、`/wtc help` 无 `audit`、`/wtc clear true`、无审计目录和数据库运行迹象

每个服务端目录保留 `server-stdout.log`、`server-stderr.log`、`latest.log` 和 `rcon-commands.log`。
隔离测试服及其 `world*`、`cache`、`libraries` 保留在仓库 `build/addon-api-no-addon-smoke/`。
