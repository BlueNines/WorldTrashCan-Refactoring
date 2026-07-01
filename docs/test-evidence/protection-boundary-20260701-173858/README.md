# F-068/F-069 保护边界专项验收

- 被测插件: `dist/BLWorldTrashCan-universal.jar`
- SHA256: `18b2f29229dba529098a94748db6abf8b729c81a0c3ab749a461d28d8d14f55b`
- 验收方式: 真实服务端启动 + 临时 Bukkit 夹具触发正式 ProjectileHitEvent、EntityShootBowEvent、EntityInteractEvent、PlayerInteractEvent
- 通过标准: 不可拾取箭矢和追踪箭矢命中后被移除；实体和玩家踩踏农田事件均被取消
- 结论: FAIL

## paper1122

- 服务端: `Paper 1.12.2`
- 平台: `legacy-1.12`
- RCON 记录: `paper1122/logs/rcon-commands.log`
- 服务端日志: `paper1122/logs/latest.log`、`paper1122/logs/server-stdout.log`
- 配置证据: `paper1122/config/protections-after-patch.yml`
