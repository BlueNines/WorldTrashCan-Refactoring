# 公共垃圾桶 actions 与 PAPI 真实客户端专项

- universal Jar SHA-256: `ee58dd0e1d4834087916b0920bf23e237a9bc5cfad6a74129ad8b1e658481635`
- audit Jar SHA-256: `3867a27edafca1269366779cae0792bc6fa61b56ec6c9057cfb54c1ad62e4e1b`
- 全部通过: `true`
- 1.12.2 用例临时暂停 PAPI，结束后原样恢复；Folia 1.21.8 使用真实 PAPI。
- 查水表附属菜单未修改布局，公共垃圾桶没有预置查水表跳转。

| 用例 | 客户端 | PAPI | 结果 |
| --- | --- | --- | --- |
| paper-1.12.2-managed-universal | 1.12.2 | 未安装 | PASS |
| folia1.21.8 | 1.21.8 | 启用 | PASS |

每个用例保留按钮打开、PAPI Tooltip、动作结果客户端截图，以及服务端动作断言截图和完整日志。