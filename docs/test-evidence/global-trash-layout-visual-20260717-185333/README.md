# 公共垃圾桶字符布局真实客户端专项

- 被测产物: `dist/BlWorldTrashCan-universal.jar`
- SHA256: `5031a1bfdebb6f4b7e985a659ce303e3cfdc47cdab7af66993a6011c34d84347`
- 客户端: 真实 1.12.2 与真实 1.21.8 客户端。
- 覆盖: 两行字符布局、材质候选降级、RGB/传统颜色名称与 Lore、页码占位符、真实翻页、缩容零丢失、临时溢出页不接收新物品、正常页释放容量后恢复写入、七行非法布局回退六行。
- 结论: PASS

| 服务端 | 客户端 | 状态 |
| --- | --- | --- |
| paper-1.12.2-managed-universal | 1.12.2 | PASS |
| folia1.21.8 | 1.21.8 | PASS |

## 证据说明

- `*/screenshots/*layout*.png`: 真实客户端 F2 截图。
- `*/server-screenshots/*layout-assertions.png`: 服务端布局日志、库存和路由断言。
- `*/logs/*server-console.log`: 本轮独立服务端控制台日志。
- `*/logs/*client*.log`: 本轮真实客户端日志。
- `*/result.json` 与 `summary.json`: 机器可读断言。
- `trash-gui-click-contact-sheet.png`: 截图总览。
