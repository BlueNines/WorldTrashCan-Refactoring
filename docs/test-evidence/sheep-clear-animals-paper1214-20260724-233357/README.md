# Paper 1.21.4 羊清理专项

- 被测产物: `WorldListTrashCan-universal.jar`
- SHA-256: `ee58dd0e1d4834087916b0920bf23e237a9bc5cfad6a74129ad8b1e658481635`
- 服务端: `Paper 1.21.4 build 232`
- 验收方式: 同一只无自定义名 Sheep，依次执行 `clear-animals: false` 和 `true` 的正式 `/wtc clear true` 对照。
- 存活计数: 生成后 `1`，false 清理后 `1`，true 清理后 `0`。
- 结论: `PASS`，`clear-animals: true` 会清理羊；false 时同一只羊保留。

证据文件：

- `logs/rcon-commands.log`：完整命令、reload、clear 和 scoreboard 响应。
- `logs/latest.log`：插件启动与两轮 `[Cleanup]` 摘要。
- `logs/server-stdout.log`、`logs/server-stderr.log`：完整服务端输出。
- `config/cleanup-default.yml`、`cleanup-clear-animals-false.yml`、`cleanup-clear-animals-true.yml`：三阶段配置。
- `summary.json`：机器可读断言和实体计数。
