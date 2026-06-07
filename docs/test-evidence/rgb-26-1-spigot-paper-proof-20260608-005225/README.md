# BLWorldTrashCan 26.1.2 Paper/Spigot RGB 与基础功能证据

本目录归档 `BLWorldTrashCan-universal.jar` 在 Minecraft 26.1.2 系列 Paper 与 Spigot 上的真实客户端验收结果。最终判定以原版客户端 F2 截图、summary JSON、服务端 console 日志和基础功能检查 JSON 共同为准，不用服务端日志单独替代玩家可见 RGB 结论。

## 结论

```text
Paper  26.1.2 build 69       BLWorldTrashCan-universal.jar  RGB PASS  基础功能 11/11 PASS
Spigot 26.1.2 BuildTools     BLWorldTrashCan-universal.jar  RGB PASS  基础功能 11/11 PASS
```

两个服务端均使用 Java 25 启动。Paper 测试服位于 `E:\server_work\paper-26.1.2-test-server`，Spigot 测试服位于 `E:\server_work\spigot-26.1.2-test-server`。

## 截图证据

- `rgb-26-1-spigot-paper-contact-sheet.png`：Paper + Spigot 合并总览，包含 RGB 三通道、debug RGB、公共垃圾桶 GUI、个人垃圾桶 GUI。
- `paper-rgb-contact-sheet.png`：Paper 单端 RGB 总览。
- `spigot-rgb-contact-sheet.png`：Spigot 单端 RGB 总览。
- `screenshots/universal_paper2612-rgb-channels-f2.png`：Paper 聊天框、ActionBar、Title/Subtitle RGB 截图。
- `screenshots/universal_spigot2612-rgb-channels-f2.png`：Spigot 聊天框、ActionBar、Title/Subtitle RGB 截图。
- `screenshots/*-global-gui-open-f2.png`：公共垃圾桶 GUI 打开截图。
- `screenshots/*-personal-gui-open-f2.png`：个人垃圾桶 GUI 打开截图。

## 功能覆盖

每端均执行以下 11 项基础功能检查，全部 `PASS`：

```text
reload
world-trash-create
global-route
personal-route
world-route
damage-recovery
owner-drop
manual-clear
summary
global-gui-open
personal-gui-open
```

## 原始记录

- `paper-summary.json`：Paper 26.1.2 本轮 run 摘要。
- `spigot-summary.json`：Spigot 26.1.2 本轮 run 摘要。
- `logs/universal_paper2612/`：Paper 客户端启动、客户端 stdout/stderr、服务端 console、命令日志、基础功能 JSON。
- `logs/universal_spigot2612/`：Spigot 客户端启动、客户端 stdout/stderr、服务端 console、命令日志、基础功能 JSON。
- `logs/buildtools-26.1.2-jdk25-resume.stdout.log`：Spigot 26.1.2 BuildTools 构建 stdout。
- `logs/buildtools-26.1.2-jdk25-resume.stderr.log`：Spigot 26.1.2 BuildTools 构建 stderr。
- `artifact-summary.json`：归档文件大小、SHA256、PNG magic 和图片尺寸摘要。

## 复跑命令

```powershell
py -3 tools\rgb-visual-matrix\run_rgb_external_server_matrix.py --case external_paper2612 --universal --channels-only --basic-checks
py -3 tools\rgb-visual-matrix\run_rgb_external_server_matrix.py --case external_spigot2612 --universal --channels-only --basic-checks
```
