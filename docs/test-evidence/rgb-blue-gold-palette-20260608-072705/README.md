# 蓝黄灰黑 RGB 色板验证

本目录归档 2026-06-08 对 BLWorldTrashCan 默认 RGB 色板的真实客户端截图验证。

## 色板

- 冷蓝：`#38BDF8`、`#2563EB`
- 鎏金：`#F5B82E`、`#E7C873`
- 雾灰：`#D5DEE9`、`#AAB6C5`、`#64748B`
- 深墨：`#111827`、`#0F172A`

## 验证结论

```text
测试端: Paper 1.20.4
客户端: Minecraft 1.20.4
插件产物: dist/BLWorldTrashCan-paper-1.16-1.20.jar
触发命令: wtc reload, blwtc platform, blwtc debugrgbchannels RGBaper1204
结果: PASS
```

截图证明：

- `screenshots/paper1204-blue-gold-rgb-f2.png`：真实客户端 F2 截图，展示聊天栏、Title、Subtitle 的蓝黄灰黑 RGB 色板。
- `screenshots/rgb-visual-contact-sheet.png`：本轮截图总览图。

日志证明：

- `logs/paper1204-rcon.log`：RCON 命令与返回，包含 `/wtc reload` 和 `debugrgbchannels`。
- `logs/paper1204-server-console.log`：服务端加载 `BLWorldTrashCan v7.0.0`、RGB capability 启用、玩家进服和测试过程日志。
