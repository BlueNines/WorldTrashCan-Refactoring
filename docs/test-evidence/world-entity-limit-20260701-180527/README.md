# F-070/F-072 世界实体上限专项验收

- 被测插件: `dist/BLWorldTrashCan-universal.jar`
- SHA256: `0b8fe41981a5933058983d644c14fb80de11f5825e9ce02d2ce12faacf19df84`
- 验收方式: 真实服务端启动 + 低占用索引等待 + 临时 Bukkit 夹具走正式实体生成路径
- 通过标准: 先关闭实体限制铺底 COW=2，再开启上限等待缓存建立，第 3 只被拦截；同一世界写入 `ignored-worlds` 后第 3 只被放行
- 结论: PASS

## paper1122

- 服务端: `Paper 1.12.2`
- 平台: `legacy-1.12`
- RCON 记录: `paper1122/logs/rcon-commands.log`
- 服务端日志: `paper1122/logs/latest.log`、`paper1122/logs/server-stdout.log`
- 配置证据: `paper1122/config/entity-limits-seed-disabled.yml`、`paper1122/config/entity-limits-active.yml`、`paper1122/config/entity-limits-ignored.yml`、`paper1122/config/entity-limits-after-test.yml`
- 索引实体数: `6`
- ignored-worlds 扫描状态: `(0, 0)`

## spigot2612

- 服务端: `Spigot 26.1.2`
- 平台: `paper-1.16-1.20`
- RCON 记录: `spigot2612/logs/rcon-commands.log`
- 服务端日志: `spigot2612/logs/latest.log`、`spigot2612/logs/server-stdout.log`
- 配置证据: `spigot2612/config/entity-limits-seed-disabled.yml`、`spigot2612/config/entity-limits-active.yml`、`spigot2612/config/entity-limits-ignored.yml`、`spigot2612/config/entity-limits-after-test.yml`
- 索引实体数: `2`
- ignored-worlds 扫描状态: `(0, 0)`
