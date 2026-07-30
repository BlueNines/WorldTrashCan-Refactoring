# 强制直删世界真实客户端专项验收

- 被测产物：`dist/WorldListTrashCan-universal.jar`
- 配置：`cleanup.yml -> direct-remove-worlds`
- 直删轮：三类垃圾桶全部启用且当前世界存在世界垃圾桶，要求回收四项计数均为 0、删除 7。
- 对照轮：通过 `/wtc reload` 移出世界后，同位置 5 个物品要求进入世界垃圾桶、删除为 0。
- 联系表：`direct-remove-world-contact-sheet.png`

## paper-1.12.2-managed-universal

- 结果：`PASS`
- 客户端与服务端原始证据：`universal_managed_paper1122/`

## folia1.21.8

- 结果：`PASS`
- 客户端与服务端原始证据：`universal_folia1218/`
