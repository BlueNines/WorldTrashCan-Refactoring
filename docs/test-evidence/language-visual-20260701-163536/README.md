# 多语言切换与缺节点回退验收

- 被测插件: `dist/BLWorldTrashCan-universal.jar`
- 验收方式: 真实服务端 + 真实客户端执行 `/blwtc help`、`/blwtc reload`
- 覆盖端: Paper 1.12.2 managed、Spigot 26.1.2 managed、Folia 1.21.8
- 覆盖场景: 切换到 `message_en.yml` 后输出英文帮助；删除外部 `message_zh.yml` 的正式节点后 reload，插件回退 jar 内默认节点而不是退回旧语言或空白
- 结论: PASS

## 关键文件

- `summary.json`: 三端机器可读验收结果。
- `*/logs/runtime-english/`: 切换英文时的运行期配置快照。
- `*/logs/runtime-missing-node/`: 删除外部正式节点后的运行期配置快照。
- `*/screenshots/*language-english-help-f2.png`: 英文帮助截图。
- `*/screenshots/*language-fallback-help-f2.png`: 缺节点回退截图。
- `*/logs/*-client-stdout.log`: 客户端聊天与截图日志。
- `*/logs/*-server-console.log`: 服务端 reload 和消息加载日志。
