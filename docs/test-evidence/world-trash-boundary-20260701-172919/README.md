# F-019 至 F-022 世界垃圾桶边界专项验收

- 被测插件: `dist/BLWorldTrashCan-universal.jar`
- SHA256: `18b2f29229dba529098a94748db6abf8b729c81a0c3ab749a461d28d8d14f55b`
- 验收方式: 真实服务端启动 + 临时 Bukkit 夹具触发正式事件/RCON 正式清理
- 覆盖功能: F-019 禁止世界普通玩家创建、F-020 破坏登记移除、F-021 世界物品黑名单降级、F-022 未加载区块降级
- 结论: PASS

## paper1122

- 服务端: `Paper 1.12.2`
- 平台: `legacy-1.12`
- RCON 记录: `paper1122/logs/rcon-commands.log`
- 服务端日志: `paper1122/logs/latest.log`、`paper1122/logs/server-stdout.log`
- 配置证据: `paper1122/config/cleanup-after-patch.yml`、`paper1122/config/trash-after-patch.yml`
- 数据证据: `paper1122/data/worlds.yml`

## spigot2612

- 服务端: `Spigot 26.1.2`
- 平台: `paper-1.16-1.20`
- RCON 记录: `spigot2612/logs/rcon-commands.log`
- 服务端日志: `spigot2612/logs/latest.log`、`spigot2612/logs/server-stdout.log`
- 配置证据: `spigot2612/config/cleanup-after-patch.yml`、`spigot2612/config/trash-after-patch.yml`
- 数据证据: `spigot2612/data/worlds.yml`
