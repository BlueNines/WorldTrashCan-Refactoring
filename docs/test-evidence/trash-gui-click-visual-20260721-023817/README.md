# GUI 正向点击真实客户端专项

- 被测 jar: `dist/WorldListTrashCan-universal.jar`
- SHA256: `d821feef1a5e9158f027c530c048527195183d4954e41af13701ec8373b9ea24`
- 验收方式: spigot-26.1.2-managed + 真实 26.1.2 客户端、paper-1.12.2-managed-universal + 真实 1.12.2 客户端、folia1.21.8 + 真实 1.21.8 客户端。
- 覆盖: 公共垃圾桶放入/取出/冷却/分页/操作日志，个人垃圾桶放入/取出/满桶自动清空，公共黑名单 GUI 保存并立即影响路由。
- 通过标准: GUI 必须由真实客户端打开并截图，关键槽位必须由真实客户端点击，库存摘要、公共日志和路由结果必须匹配预期。
- 结论: PASS

| 服务端 | 版本 | 状态 | 玩家 |
| --- | --- | --- | --- |
| spigot-26.1.2-managed | 26.1.2 | PASS | RGBigot2612 |
| paper-1.12.2-managed-universal | 1.12.2 | PASS | RGBaper1122 |
| folia1.21.8 | 1.21.8 | PASS | RGBolia1218 |

## 证据

- `summary.json`: 机器可读总结果。
- `*/result.json`: 单端详细断言。
- `*/screenshots/*-f2.png`: 真实客户端 GUI 打开与点击后的 F2 截图。
- `*/server-screenshots/*.png`: 库存摘要、公共日志和路由拒绝等服务端可视化证据。
- `*/logs/*server-console.log`、`*/logs/*console-commands.log`、`*/logs/latest.log`: 服务端运行日志和命令记录。
- `trash-gui-click-contact-sheet.png`: 截图总览。
