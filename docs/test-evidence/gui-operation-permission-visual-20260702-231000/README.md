# GUI 取放权限真实客户端专项

- 被测 jar: `dist/BLWorldTrashCan-universal.jar`
- SHA256: `18d92a709d48fc291dd77c74a3be7d543f5bb20fa0fc01c4c70ab00eb33773d4`
- 验收方式: paper-1.12.2-managed-universal + 真实 1.12.2 客户端、spigot-26.1.2-managed + 真实 26.1.2 客户端、folia1.21.8 + 真实 1.21.8 客户端 + 临时 PermissionDenyFixture。
- 覆盖: 公共取出 deny、公共放入 deny、个人取出 deny、个人放入 deny。
- 通过标准: 真实点击后取出权限必须出现客户端无权限提示；放入权限静默拒绝但垃圾桶库存和公共操作日志不能变化。
- 结论: PASS

| 服务端 | 版本 | 状态 | 玩家 |
| --- | --- | --- | --- |
| paper-1.12.2-managed-universal | 1.12.2 | PASS | RGBaper1122 |
| spigot-26.1.2-managed | 26.1.2 | PASS | RGBigot2612 |
| folia1.21.8 | 1.21.8 | PASS | RGBolia1218 |

## 证据

- `summary.json`: 机器可读结果。
- `*/result.json`: 单端详细断言。
- `*/screenshots/*after-click-f2.png`: 真实客户端点击后的 F2 截图。
- `*/logs/*client-stdout.log`: 客户端聊天和系统日志。
- `*/logs/*server-console.log`、`*/logs/latest.log`: 服务端运行日志。
- `gui-operation-permission-contact-sheet.png`: 点击截图总览。
