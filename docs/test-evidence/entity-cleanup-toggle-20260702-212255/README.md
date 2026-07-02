# 实体清理总开关专项验收

- 被测插件: `dist/BLWorldTrashCan-universal.jar`
- SHA256: `18d92a709d48fc291dd77c74a3be7d543f5bb20fa0fc01c4c70ab00eb33773d4`
- 验收方式: 真实服务端启动 + 临时 Bukkit 夹具生成牛/僵尸/箭/经验球/黑名单命名实体 + 正式 `/blwtc clear true`
- 通过标准: `entities.enabled=false` 时 5 个实体全部保留；切回 `entities.enabled=true` 后同一批实体全部被正式清理
- 结论: PASS

## paper1122

- 服务端: `Paper 1.12.2`
- 平台: `legacy-1.12`
- RCON 记录: `paper1122/logs/rcon-commands.log`
- 服务端日志: `paper1122/logs/latest.log`、`paper1122/logs/server-stdout.log`
- 关闭配置证据: `paper1122/config/cleanup-disabled-after-patch.yml`
- 开启配置证据: `paper1122/config/cleanup-enabled-after-patch.yml`
- 准备后断言: `AI_ENTITY_TOGGLE_DISABLED_RESULT passed=true alive=5 expected=5 names=cow:true,zombie:true,arrow:true,experience_orb:true,blacklist_named:true`
- 关闭后断言: `AI_ENTITY_TOGGLE_DISABLED_RESULT passed=true alive=5 expected=5 names=cow:true,zombie:true,arrow:true,experience_orb:true,blacklist_named:true`
- 开启后断言: `AI_ENTITY_TOGGLE_ENABLED_RESULT passed=true alive=0 expected=0 names=cow:false,zombie:false,arrow:false,experience_orb:false,blacklist_named:false`

## spigot2612

- 服务端: `Spigot 26.1.2`
- 平台: `paper-1.16-1.20`
- RCON 记录: `spigot2612/logs/rcon-commands.log`
- 服务端日志: `spigot2612/logs/latest.log`、`spigot2612/logs/server-stdout.log`
- 关闭配置证据: `spigot2612/config/cleanup-disabled-after-patch.yml`
- 开启配置证据: `spigot2612/config/cleanup-enabled-after-patch.yml`
- 准备后断言: `AI_ENTITY_TOGGLE_DISABLED_RESULT passed=true alive=5 expected=5 names=cow:true,zombie:true,arrow:true,experience_orb:true,blacklist_named:true`
- 关闭后断言: `AI_ENTITY_TOGGLE_DISABLED_RESULT passed=true alive=5 expected=5 names=cow:true,zombie:true,arrow:true,experience_orb:true,blacklist_named:true`
- 开启后断言: `AI_ENTITY_TOGGLE_ENABLED_RESULT passed=true alive=0 expected=0 names=cow:false,zombie:false,arrow:false,experience_orb:false,blacklist_named:false`
