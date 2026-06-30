# 清理通知真实客户端专项矩阵

本轮用于验收 `F-057`、`F-059`、`F-060`、`F-061`、`F-062`、`F-063` 的玩家可见和服务端执行证据。`F-058` 聊天可点击命令没有执行真实点击，仍保留为未验。

## 产物

```text
dist/BLWorldTrashCan-universal.jar
size: 522622
sha256: 9691aff181a60413cdb2ebfdcade97e68fbb74b3a862757de5f7c5d46aabd5fd
plugin.yml version: 7.0.0
```

## 覆盖服务端

| 服务端 | 客户端 | 结果 |
| --- | --- | --- |
| Paper 1.12.2 managed | 1.12.2 | PASS |
| Spigot 26.1.2 managed | 26.1.2 | PASS |
| Folia 1.21.8 | 1.21.8 | PASS |

## 证据口径

- 每端临时开启 `notify.chat/actionbar/bossbar/title/sound/command`。
- 每端触发 `/blwtc debugnotify 0` 和 `/blwtc debugnotify -5`。
- 客户端 F2 截图可见 Chat、ActionBar、BossBar、Title。
- Sound 以客户端字幕 `Experience gained` 作为可见辅助证据。
- Command 以服务端日志 `AI_WTC_NOTIFY_COMMAND_*` 作为执行证据。
- Folia 端证明本插件通知分派链路可用，但服主自定义 Command 仍可能触发其它插件的非 region-safe 行为。

## 关键文件

```text
summary.json
cleanup-notify-contact-sheet.png
*/screenshots/*-notify-count0-f2.png
*/screenshots/*-notify-minus5-f2.png
*/server-screenshots/*-server-log.png
*/logs/*-server-console.log
*/logs/*-console-commands.log
*/logs/*-client-stdout.log
*/logs/config-after-patch/cleanup.yml
```
