# 扫地跳过移动物品真实客户端专项验收

- 被测产物：`dist/WorldListTrashCan-universal.jar`
- 测试范围：只验证扫地时的移动物品保护，不混入持续监听、移动历史表或其他业务功能。
- 默认关闭移动轮：物品设置为移动状态，要求仍进入世界垃圾桶。
- 开启移动轮：物品保持移动，要求本轮回收/删除均为 0，并由服务端标记确认物品仍存在。
- 开启静止轮：物品停止移动，要求正常进入世界垃圾桶。
- 联系表：`moving-items-contact-sheet.png`

## paper-1.12.2-managed-universal

- 结果：`PASS`
- 客户端与服务端原始证据：`universal_managed_paper1122/`

## folia1.21.8

- 结果：`PASS`
- 客户端与服务端原始证据：`universal_folia1218/`
