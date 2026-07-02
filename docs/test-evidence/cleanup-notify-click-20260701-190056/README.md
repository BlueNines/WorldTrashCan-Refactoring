# F-058 清理通知 Chat 点击验收

- 被测插件: `dist/BLWorldTrashCan-universal.jar`
- 验收方式: 真实服务端 + 真实客户端收到清理通知后点击聊天组件
- 覆盖端: Paper 1.12.2 managed、Spigot 26.1.2 managed、Folia 1.21.8
- 覆盖场景: 清理通知进入客户端聊天栏；真实点击 `AI_CLICK_NOTIFY_0` 后触发 `/blwtc stats`；客户端聊天日志出现清理统计和公共垃圾桶信息
- 结论: PASS

## 关键文件

- `summary.json`: 三端机器可读验收结果。
- `universal_managed_paper1122/screenshots/`: Paper 1.12.2 客户端通知、点击前聊天框和点击后统计截图。
- `universal_spigot2612/screenshots/`: Spigot 26.1.2 客户端通知、点击前聊天框和点击后统计截图。
- `universal_folia1218/screenshots/`: Folia 1.21.8 客户端通知、点击前聊天框和点击后统计截图。
- `*/logs/*-console-commands.log`: 测试期间执行的控制台命令。
- `*/logs/*-server-console.log`: 服务端启动和插件输出日志。
- `*/logs/*-client-stdout.log`: 客户端侧聊天与截图日志。
