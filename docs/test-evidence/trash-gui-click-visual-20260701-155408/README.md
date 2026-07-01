# GUI 真实点击专项证据

本目录是 2026-07-01 的通过证据，不能与同日前一次失败目录混用。

## 被测对象

- 插件：`dist/BLWorldTrashCan-universal.jar`
- SHA256：`9caa1bf4319235bc4a9543abdcfdcfb8af3a27787922284a97b8a2c440b3f05a`
- `plugin.yml`：`version: 7.0.0`
- 服务端：`E:\server_work\spigot-26.1.2-test-server`
- 客户端：真实 26.1.2 客户端
- 脚本：`tools/rgb-visual-matrix/run_trash_gui_click_visual_matrix.py --case external_spigot2612`

## 通过范围

`summary.json` 中 `allPassed` 为 `true`。本轮覆盖：

- F-024 公共垃圾桶分页和翻页按钮
- F-026 玩家从公共垃圾桶取出物品
- F-027 玩家向公共垃圾桶放入物品
- F-028 公共垃圾桶取出冷却
- F-029 公共垃圾桶操作日志
- F-030 公共黑名单 GUI 关闭保存并立即生效
- F-034 玩家从个人垃圾桶取出物品
- F-035 玩家向个人垃圾桶放入物品
- F-036 个人垃圾桶满时自动清空旧内容

## 关键证据

- `trash-gui-click-contact-sheet.png`：本轮截图总览。
- `universal_spigot2612/screenshots/`：真实客户端 F2 截图。
- `universal_spigot2612/server-screenshots/`：公共操作日志与黑名单路由负向截图。
- `universal_spigot2612/logs/trash-after-globalban.yml`：公共黑名单保存后的运行时配置快照，包含 `STONE`。
- `universal_spigot2612/result.json`：单端详细检查结果。

Vault 扣费 F-037 没有在本轮安装 Economy 前置，不属于本目录通过范围。
