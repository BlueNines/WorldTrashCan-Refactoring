# Universal 整包外部端高辨识度 RGB 三通道截图证据

- 测试日期：2026-06-07
- 结论：6 个外部服务端全部使用同一个 `BLWorldTrashCan-universal.jar`，真实客户端 F2 截图 PASS。
- 本轮原因：上一轮 `TITLE` 颜色接近传统 `&a`、`&6` 的观感，人工不容易判断是否为真实 RGB，因此改为高辨识度多段 RGB 文案后重测。
- RGB 校验范围：聊天框、ActionBar、Title/Subtitle；本轮不使用箱子 GUI、物品名或 lore 作为 RGB 通过证据。
- Title 文案：`RGB TITLE FF1493`，分别使用 `#FF1493`、`#00E5FF`、`#BAFF00` 三段 RGB。
- Subtitle 文案：`SUBTITLE FF4F00`，使用 `#7B2CFF` 与 `#FF4F00` 两段 RGB。
- Chat/ActionBar 也带 `RGB-FF1493`、`RGB-00E5FF`、`RGB-BAFF00`、`RGB-7B2CFF`、`RGB-FF4F00` 文本标记。
- 每个外部端额外执行 11 项基础功能检查并全部 PASS；临时测试配置已备份到 `logs/<case>/config-backup/` 并在停服后恢复。

## 覆盖范围

- `universal_paper1218` `1.21.8` `PASS`；截图 `screenshots/universal_paper1218-highcontrast-chat-actionbar-title-f2.png`；日志 `logs/universal_paper1218`；基础功能 `11` 项。
- `universal_cat1122` `1.12.2` `PASS`；截图 `screenshots/universal_cat1122-highcontrast-chat-actionbar-title-f2.png`；日志 `logs/universal_cat1122`；基础功能 `11` 项。
- `universal_folia1218` `1.21.8` `PASS`；截图 `screenshots/universal_folia1218-highcontrast-chat-actionbar-title-f2.png`；日志 `logs/universal_folia1218`；基础功能 `11` 项。
- `universal_paper12111` `1.21.11` `PASS`；截图 `screenshots/universal_paper12111-highcontrast-chat-actionbar-title-f2.png`；日志 `logs/universal_paper12111`；基础功能 `11` 项。
- `universal_arclight1211` `1.21.1-arclight-neoforge` `PASS`；截图 `screenshots/universal_arclight1211-highcontrast-chat-actionbar-title-f2.png`；日志 `logs/universal_arclight1211`；基础功能 `11` 项。
- `universal_banner1201` `1.20.1` `PASS`；截图 `screenshots/universal_banner1201-highcontrast-chat-actionbar-title-f2.png`；日志 `logs/universal_banner1201`；基础功能 `11` 项。

## 基础功能项

eload、world-trash-create、global-route、personal-route、world-route、damage-recovery、owner-drop、manual-clear、summary、global-gui-open、personal-gui-open。
