# 实体清理总开关专项失败对照

- 结论: FAIL，不作为最终通过证据。
- 被测插件: `dist/BLWorldTrashCan-universal.jar`
- 失败原因: 首版夹具把 Spigot 26.1.2 生成实体后同一 tick 内 UUID 立即可见当成前提，并且没有固定箭实体的速度和重力。实际插件日志显示 `entities.enabled=false` 时本轮清理 `entitiesSkipped=5`、`entitiesRemoved=0`，业务分支已正确跳过，但夹具断言因生成可见性和箭实体稳定性失败。
- 修正结果: 后续脚本改为准备后等待一拍再做 ready 断言，并把箭设置为零速度、无重力；最终 PASS 证据为 `../entity-cleanup-toggle-20260702-212255/`。

## 关键证据

- Spigot 26.1.2 RCON: `spigot2612/logs/rcon-commands.log`
- Spigot 26.1.2 stdout: `spigot2612/logs/server-stdout.log`
- Paper 1.12.2 RCON: `paper1122/logs/rcon-commands.log`
