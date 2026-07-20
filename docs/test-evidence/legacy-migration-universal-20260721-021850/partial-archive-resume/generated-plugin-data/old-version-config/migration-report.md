# WorldListTrashCan 旧配置迁移报告

- 迁移时间: 2026-07-21 02:19:14
- 来源目录: `C:\Users\pc\Desktop\ai开发插件\待重构插件\WorldListTrashCan重构\refactor-workspace\build\legacy-migration-matrix\20260721-021850\partial-archive-resume\server\plugins\WorldListTrashCan\old-version-config`
- 来源类型: old-version-config 隔离备份

## 自动迁移字段

- `Set.Lang -> language`
- `Set.Debug -> debug`
- `Set.SecondCount -> interval-seconds`
- `Set.WorldClearWhiteList -> ignored-worlds`
- `Set.ClearEntity.Flag -> entities.enabled`
- `Set.ClearEntity.ClearExpBottle -> entities.clear-experience-orbs`
- `Set.ClearEntity.ClearMonster -> entities.clear-monsters`
- `Set.ClearEntity.ClearAnimals -> entities.clear-animals`
- `Set.ClearEntity.ClearProjectile -> entities.clear-projectiles`
- `Set.ClearEntity.ClearReNameEntity -> entities.clear-named-entities`
- `Set.ClearEntity.IgnoreEntitiesInBoat -> entities.ignore-entities-in-boat`
- `Set.GlobalTrash.Flag -> global-trash.enabled`
- `Set.GlobalTrash.MaxPage -> global-trash.max-pages`
- `Set.GlobalTrash.Delay -> global-trash.take-delay-millis`
- `Set.GlobalTrash.EveryClearGlobalTrash -> global-trash.clear-every-cleanups`
- `Set.GlobalTrash.Log.Enable -> global-trash.log-enabled`
- `Set.GlobalTrash.GlobalItems.BackItem.ModelId -> global-trash.gui.layout.items.a.model-id`
- `Set.GlobalTrash.GlobalItems.NextItem.ModelId -> global-trash.gui.layout.items.c.model-id`
- `Set.GlobalTrash.GlobalItems.BackgroundItem.ModelId -> global-trash.gui.layout.items.b.model-id`
- `GlobalBanItem -> global-trash.banned-materials`
- `Set.SighCheckName -> world-trash.sign-create-text`
- `Set.SighCheckedName -> world-trash.sign-created-text`
- `Set.DefaultRashCanMax -> world-trash.default-max-count`
- `Set.BanWorldNameList -> world-trash.banned-worlds`
- `Set.PersonalTrashCan.Flag -> personal-trash.enabled`
- `Set.PersonalTrashCan.NoWorldTrashCanEnterPersonalTrashCan -> personal-trash.track-player-dropped-items`
- `Set.PersonalTrashCan.OriginalFeatureClearItemAddGlobalTrash.Model2.AutoClear -> personal-trash.auto-clear-when-full`
- `Set.PersonalTrashCan.OriginalFeatureClearItemAddGlobalTrash.Model2.Coins -> personal-trash.take-cost`
- `Set.PersonalTrashCan.OriginalFeatureClearItemAddGlobalTrash.UseModel -> personal-trash.damage-recovery.mode`
- `Set.PersonalTrashCan.OriginalFeatureClearItemAddGlobalTrash.Delay -> personal-trash.damage-recovery.delay-seconds`
- `Set.ChatFlag -> notify.chat.enabled`
- `Set.ChatConsoleLogFlag -> notify.console.enabled`
- `Set.ChatClickCommand -> notify.chat.click-command`
- `Set.ChatMessageForCount -> notify.chat.messages`
- `Set.ActionBarFlag -> notify.actionbar.enabled`
- `Set.ActionBarMessageForCount -> notify.actionbar.messages`
- `Set.CommandFlag -> notify.command.enabled`
- `Set.CommandForCount -> notify.command.commands`
- `Set.TitleFlag -> notify.title.enabled`
- `Set.TitleMessageForCount -> notify.title.messages`
- `Set.SoundFlag -> notify.sound.enabled`
- `Set.SoundForCount -> notify.sound.messages`
- `Set.BossBarFlag -> notify.bossbar.enabled`
- `Set.BossBarMessageForCount -> notify.bossbar.messages`
- `ChatSet.QuickSendMessage.Flag -> chat-rate-limit.enabled`
- `ChatSet.QuickSendMessage.Time -> chat-rate-limit.interval-seconds`
- `ChatSet.QuickSendMessage.Message -> chat-rate-limit.message`
- `ChatSet.QuickSendMessage.Command -> chat-rate-limit.command`
- `ChatSet.QuickUseCommand.Flag -> command-rate-limit.enabled`
- `ChatSet.QuickUseCommand.Time -> command-rate-limit.interval-seconds`
- `ChatSet.QuickUseCommand.Message -> command-rate-limit.message`
- `ChatSet.QuickUseCommand.Command -> command-rate-limit.command`
- `ChatSet.QuickUseCommand.WhiteList -> command-rate-limit.whitelist`
- `DropItemCheck.Flag -> drop-protection.enabled`
- `SimpleOptimize.NotPickArrow -> simple-optimize.remove-unpickable-arrow`
- `SimpleOptimize.NotTreadingFarmLand -> simple-optimize.prevent-farmland-trampling`
- `WorldEntityLimitCount.Flag -> world-limits.enabled`
- `WorldEntityLimitCount.BanWorldNameList -> world-limits.ignored-worlds`
- `WorldEntityLimitCount.DefaultCount -> world-limits.defaults`
- `GatherEntityLimitCount.Flag -> gather-limits.enabled`
- `GatherEntityLimitCount.ItemDropFlag -> gather-limits.drop-items`
- `GatherEntityLimitCount.BanWorldNameList -> gather-limits.ignored-worlds`
- `GatherEntityLimitCount.DefaultCount -> gather-limits.defaults`
- `WorldData.world.SignLocation -> worlds.world.locations`
- `WorldData.world.RashMaxCount -> worlds.world.max-count`
- `WorldData.world.BanItem -> worlds.world.banned-materials`

## 已废弃字段

- 无

## 需要人工确认字段

- 无

## 说明

- 迁移器只迁移当前新实现已经承接的旧功能字段。
- 只有 migration-complete.yml 表示迁移成功；迁移失败不会生成成功标记。
- Bukkit YAML 保存运行时配置会重写文件注释；默认带注释配置仍保留在插件 jar 内。
