# 旧配置迁移真实客户端验收

- 自动断言: `PASS`
- universal Jar SHA-256: `a42e1c4077f9f4250f89cb88ffbe22d2fe7be88e07efa31226e02786e1649a72`
- 旧配置可辨识值: `MaxPage=7`、`FEATHER/STICK/STAINED_GLASS_PANE`、`LEGACY_MIGRATION_CHAT_OK`
- Runner 原始 run: `runner-run/`
- 迁移后服务端目录: `server-plugin-data-after-client/`
- 人工截图复核: `PASS`。已逐一打开 10 个非重复帧，另外 5 张经 SHA-256 确认为相同画面的归档副本。
- 客户端可见结果: 聊天显示“公共垃圾桶页数: 7”和 `LEGACY_MIGRATION_CHAT_OK pages=7`；GUI 标题显示“公共垃圾桶 1/7”，底行显示迁移后的玻璃背景与 `STICK` 下一页按钮。
- 复核明细: `screenshot-review.md`。
