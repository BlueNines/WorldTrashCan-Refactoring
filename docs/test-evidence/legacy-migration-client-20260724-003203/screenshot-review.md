# 客户端截图人工复核

- 复核时间: 2026-07-24 00:38:11 +08:00
- 结论: PASS
- 客户端: 真实 Forge 1.12.2 客户端，玩家 AIClientAlpha
- 服务端: Paper 1.12.2
- 插件产物: WorldListTrashCan-universal.jar
- Jar SHA-256: a42e1c4077f9f4250f89cb88ffbe22d2fe7be88e07efa31226e02786e1649a72

## 业务证据

1. `runner_sequence_1.png`
   - 游戏内聊天清楚显示“公共垃圾桶页数: 7”。
   - 证明旧 `Set.GlobalTrash.MaxPage: 7` 已迁移并被运行时读取。
2. `runner_sequence_2.png`
   - 游戏内聊天清楚显示 `LEGACY_MIGRATION_CHAT_OK pages=7`。
   - 证明旧 `Set.ChatMessageForCount` 已迁移，且 `/wtc debugnotify 0` 能从客户端收到对应消息。
3. `runner_expected_screen_GuiChest_step_3_tick_20.png` 至 `tick_160.png`
   - 四个稳定帧都显示原版 `GuiChest`，标题为“公共垃圾桶 1/7”。
   - 底行显示迁移后的 `STAINED_GLASS_PANE` 背景与 `STICK` 下一页按钮。
   - `tick_160` 帧悬停在 `STICK` 上，物品提示明确显示“下一页”。
4. `runner_sequence_connected.png`、`runner_sequence_3.png`、`runner_sequence_final.png`
   - 证明客户端在命令序列前、中、后都处于真实世界画面，非离线截图或空白画面。

## 非业务帧

- `runner_position_recovery_1_stage_0.png` 显示“加载地形中”，只记录 Runner 的位置恢复过程，不用于证明迁移成功。

## 重复帧

- 5 个 `instance-server-chat-*` 文件分别与对应的 `runner_*` 文件 SHA-256 相同。
- 因此 15 张原始 PNG 对应 10 个非重复画面；10 个非重复画面均已逐张打开检查。

## 联合结论

截图本身证明迁移后的页数、聊天文案和 GUI 布局已进入真实客户端。配合 `client-chat.log`、玩家命令断言、迁移后配置、旧文件 SHA-256 备份断言和迁移完成标记，可以判定本轮客户端旧配置兼容验收通过。
