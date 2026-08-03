# 扫地装有物品潜影盒保护真实客户端专项验收

- 被测产物：`dist/WorldListTrashCan-universal.jar`。
- 测试范围：只验证扫地阶段的装有物品潜影盒保护，不混入事件监听或其它物品保护。
- 默认关闭轮：装有物品潜影盒应正常进入世界垃圾桶。
- 开启保护轮：装有物品潜影盒应保持在地面，路由、删除均为 0。
- 空潜影盒轮：保护开启时仍应按正常规则进入世界垃圾桶。
- 范围边界：只检查掉落物实体携带的 ItemStack，不读取世界中放置的潜影盒方块。
- 联系表：`filled-shulker-contact-sheet.png`。

## paper-1.12.2-managed-universal

- 结果：`PASS`。
- 客户端、服务端和完整日志：`universal_managed_paper1122/`。

## folia1.21.8

- 结果：`PASS`。
- 客户端、服务端和完整日志：`universal_folia1218/`。
