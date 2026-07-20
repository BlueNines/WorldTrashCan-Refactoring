# 多语言、缺节点回退与公开品牌真实客户端专项

- 被测产物：`dist/WorldListTrashCan-universal.jar`
- SHA256：`d821feef1a5e9158f027c530c048527195183d4954e41af13701ec8373b9ea24`
- 服务端与客户端：Paper 1.12.2、Spigot 26.1.2、Folia 1.21.8
- 结论：三端全部 PASS

每个服务端均由真实客户端执行英文帮助切换、中文外部语言缺少 `command.help` 时的 jar 内默认节点回退，并截图确认玩家可见前缀为 `WorldListTrashCan`。`language-contact-sheet.png` 是截图总览，`summary.json` 保存机器断言，各端子目录保留真实客户端截图、客户端日志和服务端日志截图。
