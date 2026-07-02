# 权限负向真实客户端专项

- 目标: 收敛 F-016 和 README 中保留的真实玩家负向权限缺口。
- 被测 jar: `dist/BLWorldTrashCan-universal.jar`
- 验收: 真实客户端非 OP 执行 `/blwtc reload` 必须收到无权限提示；通过临时权限夹具显式 deny `global.open`/`personal.open` 后，`/blwtc global` 和 `/blwtc personal` 必须收到无权限提示；RCON `op` 后 `/blwtc reload` 必须成功。
- 证据: 每端保留 F2 截图、客户端 stdout、服务端 console、命令日志、`latest.log` 和 `summary.json`。

| 服务端 | 版本 | 状态 | 玩家 |
| --- | --- | --- | --- |
| paper-1.12.2-managed-universal | 1.12.2 | PASS | RGBaper1122 |
| spigot-26.1.2-managed | 26.1.2 | PASS | RGBigot2612 |
| folia1.21.8 | 1.21.8 | PASS | RGBolia1218 |

## 结论

本专项只验证权限边界，不改变权限设计。失败时不能用源码或字节码替代真实玩家负向权限证据。
