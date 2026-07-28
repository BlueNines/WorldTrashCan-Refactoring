# 鞍与 Bukkit Tameable 主人保护专项验收

- 被测插件：`dist/WorldListTrashCan-universal.jar`
- SHA256：`f907073ca8580e8faddfc3a1a012040e37e9ef741813de0dedc3959cd5ed44bf`
- 真实客户端：1.12.2 与 1.21.8，各保留清理前、清理后、客户端断言截图。
- 主人边界：四个平台映射器只读取 Bukkit `Tameable.getOwner()`。
- 负向边界：Projectile shooter、TNT source、Item owner、Mythic 风格 Metadata、NBT 背书 PDC/scoreboard owner 均不能触发实体主人保护。
- 模组私有 NBT 说明：Bukkit 无通用 API 可读取模组实体私有 NBT；源码契约禁止读取 NBT，现代端再用 NBT 背书的 PDC owner 做运行态负向验证。
- 总结论：PASS

## paper-1.12.2-managed-universal

- 结果：`PASS`
- 客户端截图：`universal_managed_paper1122/screenshots/`
- 服务端截图：`universal_managed_paper1122/server-screenshots/`
- 完整日志：`universal_managed_paper1122/logs/`
- 机器结果：`universal_managed_paper1122/result.json`

## server_1.21.8_0

- 结果：`PASS`
- 客户端截图：`universal_paper1218/screenshots/`
- 服务端截图：`universal_paper1218/server-screenshots/`
- 完整日志：`universal_paper1218/logs/`
- 机器结果：`universal_paper1218/result.json`

## 判定语义

- 有鞍猪、马，以及现代端炽足兽、骆驼必须存活。
- 有 Bukkit 主人的狼、猫、无鞍马必须存活。
- 无鞍猪、无主人狼/马，以及五类非 Tameable owner 来源必须被正常清理。
- 每个实体按 UUID 断言，不以名称数量或肉眼截图单独代替机器结果。
