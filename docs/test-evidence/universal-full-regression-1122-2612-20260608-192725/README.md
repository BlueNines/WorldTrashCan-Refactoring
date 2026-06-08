# BLWorldTrashCan universal 整包完整回归证据

本轮只部署同一个交付整包，测试服 `plugins` 目录中的文件名也保持为 `BLWorldTrashCan-universal.jar`：

```text
dist/BLWorldTrashCan-universal.jar
size: 432233
sha256: da30aecf3b4b5976ac95778bdb4301f08e72fd33f1edda16e3617e5045e85117
plugin.yml version: 7.0.0
main: pixeltech.bluenine.blworldtrashcan.plugin.universal.BLWorldTrashCanUniversalPlugin
```

测试脚本部署前会清理目标测试服 `plugins` 目录中的其它 `BLWorldTrashCan*.jar`，避免轻量特供 jar 和 universal 整包同时存在。本轮没有使用任何特供版本 jar 作为通过依据。

## 覆盖范围

| 测试端 | 客户端 | 服务端目录 | 结果 | 运行证据 |
| --- | --- | --- | --- | --- |
| Paper 1.12.2 managed | 1.12.2 | `E:\server_work\paper-1.12.2-universal-test-server` | PASS | `runs/20260608-191743/summary.json` |
| Paper 26.1.2 managed | 26.1.2 | `E:\server_work\paper-26.1.2-test-server` | PASS | `runs/20260608-192053/summary.json` |
| Spigot 26.1.2 managed | 26.1.2 | `E:\server_work\spigot-26.1.2-test-server` | PASS | `runs/20260608-192412/summary.json` |

## 执行命令

```powershell
.\build\tools\apache-maven-3.9.9\bin\mvn.cmd -q test package

py -3 tools\rgb-visual-matrix\run_rgb_external_server_matrix.py --case managed_paper1122 --universal --channels-only --basic-checks --full-checks
py -3 tools\rgb-visual-matrix\run_rgb_external_server_matrix.py --case external_paper2612 --universal --channels-only --basic-checks --full-checks
py -3 tools\rgb-visual-matrix\run_rgb_external_server_matrix.py --case external_spigot2612 --universal --channels-only --basic-checks --full-checks
```

## 检查项

三端都执行了真实客户端进服、RGB 三通道截图、基础功能和完整功能矩阵。测试世界名按 runId 隔离，例如 `rgb_visual_universal_paper2612_20260608_192053`，避免历史世界垃圾桶位置影响当前结论。

基础功能覆盖：

- `reload`
- 世界垃圾桶创建
- 公共、个人、世界三类路由
- 损坏回收
- owner 掉落物
- 手动清理
- debug summary
- 公共垃圾桶 GUI 打开
- 个人垃圾桶 GUI 打开

完整功能覆盖：

- `help`
- `wtc platform`
- 旧长命令 `WorldListTrashCan platform`
- `stats`
- `add <world> <amount>`，并读取 `data/worlds.yml` 验证 `max-count` 从 `3` 增加到 `5`
- `debugstock`
- `debugplayer dropmode/look/ban/globalban`
- 真实客户端执行 `/blwtc global`
- 真实客户端执行 `/blwtc personal`
- 真实客户端执行 `/blwtc dropmode`
- 真实客户端执行 `/blwtc look`
- 真实客户端执行 `/blwtc ban`
- 真实客户端执行 `/blwtc globalban`
- 真实客户端执行 `/wtc stats`
- PAPI `%Wtc_ClearTime%`
- 删除 `messages/message_es.yml` 后 `reload` 自愈
- `data/worlds.yml` 与 bStats 配置文件检查

## 截图索引

每个 run 目录都保留了原始 F2 截图和总览图：

- `runs/20260608-191743/rgb-external-server-contact-sheet.png`
- `runs/20260608-192053/rgb-external-server-contact-sheet.png`
- `runs/20260608-192412/rgb-external-server-contact-sheet.png`

核心 RGB 三通道截图：

- `runs/20260608-191743/universal_managed_paper1122/screenshots/universal_managed_paper1122-rgb-channels-f2.png`
- `runs/20260608-192053/universal_paper2612/screenshots/universal_paper2612-rgb-channels-f2.png`
- `runs/20260608-192412/universal_spigot2612/screenshots/universal_spigot2612-rgb-channels-f2.png`

## 本轮修复结论

Paper 26.1.2 首轮 full checks 暴露旧长命令 `WorldListTrashCan platform` 在现代 Brigadier 命令解析中无法执行。本轮将 `plugin.yml` 主命令改为小写 `worldlisttrashcan`，并保留 `WorldListTrashCan` 与 `wtc` 作为别名；五个平台入口同时注册小写命令，最终 Paper 26.1.2 和 Spigot 26.1.2 的旧长命令均通过。

测试脚本同时修正两类夹具问题：

- 部署到目标服时保留 `BLWorldTrashCan-universal.jar` 文件名，不再改名为临时测试 jar。
- managed 测试服按 runId 写入独立 `level-name`，避免复用旧世界时历史世界垃圾桶位置造成假失败。
- `add-world-limit` 不再只匹配日志里的世界名，而是读取 `data/worlds.yml` 断言 `max-count` 真实增加。

## 保留文件

本目录整体保留：

- `summary.json`
- `server-console.log`
- `client-stdout.log`
- `client-stderr.log`
- `console-commands.log`
- `basic-checks.json`
- `full-checks.json`
- 客户端 F2 原始 PNG
- 配置备份与 reload 自愈备份

测试过程中未删除测试服 `logs`、`world*`、`cache`、`assets`。
