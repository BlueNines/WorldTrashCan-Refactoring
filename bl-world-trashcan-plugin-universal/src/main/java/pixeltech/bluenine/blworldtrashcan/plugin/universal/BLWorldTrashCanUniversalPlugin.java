package pixeltech.bluenine.blworldtrashcan.plugin.universal;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import pixeltech.bluenine.blworldtrashcan.bukkit.bstats.BStatsMetricsService;
import pixeltech.bluenine.blworldtrashcan.bukkit.bstats.Metrics;
import pixeltech.bluenine.blworldtrashcan.bukkit.config.BukkitConfigurationSource;
import pixeltech.bluenine.blworldtrashcan.bukkit.config.BukkitLegacyConfigMigrator;
import pixeltech.bluenine.blworldtrashcan.bukkit.feature.BanGuiFeature;
import pixeltech.bluenine.blworldtrashcan.bukkit.feature.CleanupFeature;
import pixeltech.bluenine.blworldtrashcan.bukkit.feature.EntityLimitFeature;
import pixeltech.bluenine.blworldtrashcan.bukkit.feature.Feature;
import pixeltech.bluenine.blworldtrashcan.bukkit.feature.FeatureRegistry;
import pixeltech.bluenine.blworldtrashcan.bukkit.feature.ProtectionFeature;
import pixeltech.bluenine.blworldtrashcan.bukkit.feature.TrashFeature;
import pixeltech.bluenine.blworldtrashcan.bukkit.message.BukkitRgbDebugSender;
import pixeltech.bluenine.blworldtrashcan.bukkit.message.BukkitMessageService;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ServerPlatform;
import pixeltech.bluenine.blworldtrashcan.bukkit.storage.BukkitYamlWorldTrashStorage;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.DropOwnerTracker;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.GlobalTrashService;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.NoPaymentService;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.PaymentService;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.PersonalTrashService;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.WorldTrashRouter;
import pixeltech.bluenine.blworldtrashcan.config.ConfigBundle;
import pixeltech.bluenine.blworldtrashcan.config.ConfigBundleLoader;
import pixeltech.bluenine.blworldtrashcan.core.capability.Capability;
import pixeltech.bluenine.blworldtrashcan.core.trash.TrashRoute;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.logging.Level;

/** 跨版本通用总包入口，运行时选择最合适的平台实现。 */
public final class BLWorldTrashCanUniversalPlugin extends JavaPlugin {
    private static final String LEGACY_PLATFORM = "pixeltech.bluenine.blworldtrashcan.platform.legacy.LegacyPlatform";
    private static final String BUKKIT_PLATFORM = "pixeltech.bluenine.blworldtrashcan.platform.bukkit.BukkitPlatform";
    private static final String PAPER_PLATFORM = "pixeltech.bluenine.blworldtrashcan.platform.paper.PaperPlatform";
    private static final String FOLIA_PLATFORM = "pixeltech.bluenine.blworldtrashcan.platform.folia.FoliaPlatform";
    private static final String FOLIA_CLEANUP = "pixeltech.bluenine.blworldtrashcan.platform.folia.FoliaRegionCleanupFeature";
    private static final String FOLIA_ENTITY_LIMIT = "pixeltech.bluenine.blworldtrashcan.platform.folia.FoliaEntityLimitFeature";

    private FeatureRegistry featureRegistry;
    private ServerPlatform platform;
    private RuntimeKind runtimeKind;
    private ConfigBundle configBundle;
    private Feature cleanupFeature;
    private TrashFeature trashFeature;
    private ProtectionFeature protectionFeature;
    private BanGuiFeature banGuiFeature;
    private Feature entityLimitFeature;
    private WorldTrashRouter trashRouter;
    private GlobalTrashService globalTrashService;
    private PersonalTrashService personalTrashService;
    private UniversalPlaceholderExpansion expansion;
    private BukkitMessageService messageService;
    private DropOwnerTracker dropOwnerTracker;
    private Metrics metrics;

    /** 启动通用总包并按当前服务端选择平台实现。 */
    @Override
    public void onEnable() {
        try {
            startPlugin();
        } catch (Throwable throwable) {
            getLogger().log(Level.SEVERE, "[Universal] 启动失败，已禁用插件: " + throwable.getMessage(), throwable);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    /** 禁用插件并按顺序释放功能模块。 */
    @Override
    public void onDisable() {
        if (featureRegistry != null) {
            featureRegistry.disableAll();
        }
        if (metrics != null) {
            metrics.shutdown();
            metrics = null;
        }
    }

    /** 重载插件配置和功能模块。 */
    public void reloadPlugin() {
        saveDefaultConfigs();
        reloadConfig();
        this.configBundle = loadConfigBundle();
        if (messageService != null) {
            messageService.reload(configBundle.getLanguageFile());
        }
        if (featureRegistry != null) {
            featureRegistry.reloadAll();
        }
    }

    /** 返回当前平台实现。 */
    public ServerPlatform getPlatform() {
        return platform;
    }

    /** 返回消息服务。 */
    public BukkitMessageService messages() {
        return messageService;
    }

    /** 返回当前是否运行在 Folia 分支。 */
    public boolean isFoliaRuntime() {
        return runtimeKind == RuntimeKind.FOLIA;
    }

    /** 立即执行一次普通平台清理，默认遵守定时扫地门禁。 */
    public CleanupFeature.CleanupStats runCleanupNow() {
        return runCleanupNow(false);
    }

    /** 立即执行一次普通平台清理，可由命令入口决定是否忽略 guards。 */
    public CleanupFeature.CleanupStats runCleanupNow(boolean ignoreGuards) {
        if (cleanupFeature instanceof CleanupFeature) {
            return ((CleanupFeature) cleanupFeature).runNow(ignoreGuards);
        }
        return invokeCleanupStats("runNow", ignoreGuards);
    }

    /** 立即提交一次 Folia region-safe 清理，默认遵守定时扫地门禁。 */
    public boolean startCleanupNow() {
        return startCleanupNow(false);
    }

    /** 立即提交一次 Folia region-safe 清理，可由命令入口决定是否忽略 guards。 */
    public boolean startCleanupNow(boolean ignoreGuards) {
        if (!isFoliaRuntime()) {
            return false;
        }
        return invokeBoolean(cleanupFeature, "startNow", ignoreGuards, false);
    }

    /** 判断 Folia 清理是否仍在运行。 */
    public boolean isCleanupRunning() {
        return isFoliaRuntime() && invokeBoolean(cleanupFeature, "isRunning", false);
    }

    /** 判断当前平台是否支持世界扫描清理。 */
    public boolean isCleanupScanSupported() {
        if (cleanupFeature instanceof CleanupFeature) {
            return ((CleanupFeature) cleanupFeature).isWorldScanSupported();
        }
        return invokeBoolean(cleanupFeature, "isWorldScanSupported", false);
    }

    /** 返回下一次清理剩余秒数。 */
    public long getRemainingClearSeconds() {
        if (cleanupFeature instanceof CleanupFeature) {
            return ((CleanupFeature) cleanupFeature).getRemainingSeconds();
        }
        Object value = invokeNoArg(cleanupFeature, "getRemainingSeconds", Long.valueOf(0L));
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    /** 返回最近一次清理统计。 */
    public CleanupFeature.CleanupStats getLastCleanupStats() {
        if (cleanupFeature instanceof CleanupFeature) {
            return ((CleanupFeature) cleanupFeature).getLastStats();
        }
        return invokeCleanupStats("getLastStats");
    }

    /** 测试用：触发清理通知指定编号，复用正式通知链路。 */
    public boolean debugCleanupNotify(int count) {
        if (cleanupFeature instanceof CleanupFeature) {
            return ((CleanupFeature) cleanupFeature).debugNotify(count);
        }
        Object value = invokeIntArg(cleanupFeature, "debugNotify", count, Boolean.FALSE);
        return value instanceof Boolean && ((Boolean) value).booleanValue();
    }

    /** 返回公共垃圾桶页数。 */
    public int getGlobalTrashPageCount() {
        return trashFeature == null ? 0 : trashFeature.getGlobalPageCount();
    }

    /** 返回公共垃圾桶当前物品总数量。 */
    public int getGlobalTrashStoredItemAmount() {
        return trashFeature == null ? 0 : trashFeature.getGlobalStoredItemAmount();
    }

    /** 返回公共垃圾桶当前堆叠数量。 */
    public int getGlobalTrashStoredStackCount() {
        return trashFeature == null ? 0 : trashFeature.getGlobalStoredStackCount();
    }

    /** 返回已加载的个人垃圾桶数量。 */
    public int getPersonalTrashInventoryCount() {
        return trashFeature == null ? 0 : trashFeature.getPersonalInventoryCount();
    }

    /** 打开公共垃圾桶。 */
    public void openGlobalTrash(Player player) {
        trashFeature.openGlobal(player);
    }

    /** 打开个人垃圾桶。 */
    public void openPersonalTrash(Player player) {
        trashFeature.openPersonal(player);
    }

    /** 测试用：向玩家发送所有 RGB 可见通道。 */
    public boolean debugRgb(final Player player) {
        return runForPlayerRegion(player, new Runnable() {
            /** 在玩家所在上下文发送 RGB 调试消息。 */
            @Override
            public void run() {
                BukkitRgbDebugSender.send(BLWorldTrashCanUniversalPlugin.this, player);
            }
        });
    }

    /** 测试用：只向玩家发送聊天、ActionBar 和 Title RGB 可见通道。 */
    public boolean debugRgbChannels(final Player player) {
        return runForPlayerRegion(player, new Runnable() {
            /** 在玩家所在上下文发送不带 GUI 的 RGB 调试消息。 */
            @Override
            public void run() {
                BukkitRgbDebugSender.sendChatActionTitle(player);
                getLogger().info("[DebugRGB] channels player=" + player.getName());
            }
        });
    }

    /** 切换玩家防丢弃模式。 */
    public boolean toggleDropProtection(Player player) {
        return protectionFeature != null && protectionFeature.toggleDropProtection(player);
    }

    /** 让玩家进入实体和手持物查询模式。 */
    public void armLook(Player player) {
        if (protectionFeature != null) {
            protectionFeature.armLook(player);
        }
    }

    /** 打开当前世界物品黑名单 GUI。 */
    public void openWorldBan(Player player) {
        if (banGuiFeature != null) {
            banGuiFeature.openWorldBan(player);
        }
    }

    /** 打开公共垃圾桶物品黑名单 GUI。 */
    public void openGlobalBan(Player player) {
        if (banGuiFeature != null) {
            banGuiFeature.openGlobalBan(player);
        }
    }

    /** 调整当前世界垃圾桶数量上限。 */
    public int addWorldTrashMax(World world, int delta) {
        return trashRouter.addMaxCount(world, delta, configBundle.getTrashConfig().getWorldTrash().getDefaultMaxCount());
    }

    /** 测试用：在玩家附近创建并登记一个世界垃圾桶箱子。 */
    public boolean debugCreateWorldTrash(final Player player) {
        return runForPlayerRegion(player, new Runnable() {
            /** 在玩家所在上下文创建测试世界垃圾桶。 */
            @Override
            public void run() {
                Block block = findDebugChestBlock(player);
                if (block == null) {
                    getLogger().warning("[Debug] 未找到可放置测试箱子的位置: " + player.getName());
                    return;
                }
                if (block.getType() != Material.CHEST) {
                    block.setType(Material.CHEST);
                }
                boolean saved = trashRouter.addWorldTrash(block, configBundle.getTrashConfig().getWorldTrash().getDefaultMaxCount());
                getLogger().info("[Debug] debugWorldTrash player=" + player.getName()
                        + ", world=" + block.getWorld().getName()
                        + ", x=" + block.getX()
                        + ", y=" + block.getY()
                        + ", z=" + block.getZ()
                        + ", saved=" + saved);
            }
        });
    }

    /** 测试用：直接走垃圾桶路由服务投递物品。 */
    public boolean debugRoute(final Player player, final TrashRoute route, final Material material, final int amount) {
        return runForPlayerRegion(player, new Runnable() {
            /** 在玩家所在上下文执行路由测试。 */
            @Override
            public void run() {
                ItemStack itemStack = new ItemStack(material, amount);
                boolean routed = trashRouter.route(player.getWorld(), player.getUniqueId(), itemStack, route);
                getLogger().info("[Debug] debugRoute player=" + player.getName()
                        + ", route=" + route
                        + ", material=" + material.name()
                        + ", amount=" + amount
                        + ", routed=" + routed);
            }
        });
    }

    /** 测试用：在玩家位置生成一个掉落物。 */
    public boolean debugDrop(final Player player, final Material material, final int amount, final boolean markOwner) {
        return runForPlayerRegion(player, new Runnable() {
            /** 在玩家所在上下文生成测试掉落物。 */
            @Override
            public void run() {
                ItemStack itemStack = new ItemStack(material, amount);
                Item item = player.getWorld().dropItemNaturally(player.getLocation(), itemStack);
                item.setPickupDelay(200);
                if (markOwner) {
                    platform.itemSnapshotMapper().markOwner(item, player);
                    trashFeature.trackDebugDrop(item, player);
                }
                getLogger().info("[Debug] debugDrop player=" + player.getName()
                        + ", material=" + material.name()
                        + ", amount=" + amount
                        + ", markOwner=" + markOwner);
            }
        });
    }

    /** 测试用：通过正式损坏事件验证玩家掉落物短期回收。 */
    public boolean debugDamageRecovery(final Player player, final Material material, final int amount) {
        return runForPlayerRegion(player, new Runnable() {
            /** 在玩家所在上下文执行损坏回收测试。 */
            @Override
            public void run() {
                boolean recovered = trashFeature.debugDamageRecovery(player, material, amount);
                getLogger().info("[Debug] debugDamageRecovery player=" + player.getName()
                        + ", material=" + material.name()
                        + ", amount=" + amount
                        + ", recovered=" + recovered);
            }
        });
    }

    /** 测试用：生成当前玩家关联的路由状态摘要。 */
    public List<String> debugSummary(Player player) {
        List<String> lines = new ArrayList<>();
        lines.add("§aBLWorldTrashCan debug summary:");
        lines.add("§7- §f平台: §a" + platform.id() + " §7(universal)");
        lines.add("§7- §f世界: §a" + player.getWorld().getName());
        lines.add("§7- §f世界垃圾桶数量: §a" + trashRouter.getWorldTrashCount(player.getWorld()));
        if (isFoliaRuntime()) {
            lines.add("§7- §f世界垃圾桶物品: §eFolia 下跳过同步方块库存读取");
        } else {
            lines.add("§7- §f世界垃圾桶物品: §a" + trashRouter.getWorldTrashStoredItemAmount(player.getWorld())
                    + " §7(堆叠 " + trashRouter.getWorldTrashStoredStackCount(player.getWorld()) + ")");
        }
        lines.add("§7- §f跳过未加载区块: §a" + trashRouter.getSkippedUnloadedChunkAccesses());
        lines.add("§7- §f公共垃圾桶物品: §a" + trashFeature.getGlobalStoredItemAmount()
                + " §7(堆叠 " + trashFeature.getGlobalStoredStackCount() + ")");
        lines.add("§7- §f个人垃圾桶物品: §a" + trashFeature.getPersonalStoredItemAmount(player.getUniqueId())
                + " §7(堆叠 " + trashFeature.getPersonalStoredStackCount(player.getUniqueId()) + ")");
        return lines;
    }

    /** 测试用：输出实体密度扫描统计。 */
    public List<String> debugEntityLimits() {
        if (entityLimitFeature instanceof EntityLimitFeature) {
            return ((EntityLimitFeature) entityLimitFeature).debugStats();
        }
        Object value = invokeNoArg(entityLimitFeature, "debugStats", null);
        if (value instanceof List) {
            List<String> lines = new ArrayList<>();
            for (Object item : (List<?>) value) {
                lines.add(String.valueOf(item));
            }
            return lines;
        }
        List<String> lines = new ArrayList<>();
        lines.add("§e实体限制功能尚未初始化。");
        return lines;
    }

    /** 完成插件启动流程。 */
    private void startPlugin() {
        saveDefaultConfigs();
        new BukkitLegacyConfigMigrator(this).migrateIfNeeded();
        this.configBundle = loadConfigBundle();
        this.messageService = new BukkitMessageService(this);
        this.messageService.reload(configBundle.getLanguageFile());
        this.runtimeKind = detectRuntimeKind();
        this.platform = createPlatform(runtimeKind);
        this.metrics = BStatsMetricsService.start(this, "universal-" + platform.id());
        this.featureRegistry = new FeatureRegistry();
        final Supplier<ConfigBundle> configSupplier = new Supplier<ConfigBundle>() {
            /** 返回最新配置集合。 */
            @Override
            public ConfigBundle get() {
                return configBundle;
            }
        };
        PaymentService paymentService = createPaymentService(runtimeKind);
        this.globalTrashService = new GlobalTrashService(this, configBundle.getTrashConfig().getGlobalTrash(), messageService, platform.itemSnapshotMapper());
        this.personalTrashService = new PersonalTrashService(this, configBundle.getTrashConfig().getPersonalTrash(), paymentService, messageService, platform.itemSnapshotMapper(), platform);
        this.dropOwnerTracker = new DropOwnerTracker(platform);
        this.trashRouter = new WorldTrashRouter(
                this,
                new BukkitYamlWorldTrashStorage(new File(getDataFolder(), "data/worlds.yml")),
                globalTrashService,
                personalTrashService,
                configBundle.getTrashConfig(),
                platform.itemSnapshotMapper()
        );
        this.trashFeature = new TrashFeature(this, platform, configSupplier, trashRouter, globalTrashService, personalTrashService, messageService, dropOwnerTracker);
        this.cleanupFeature = createCleanupFeature(configSupplier);
        this.protectionFeature = new ProtectionFeature(this, platform, configSupplier, messageService);
        this.banGuiFeature = new BanGuiFeature(this, configSupplier, trashRouter, messageService, new Runnable() {
            /** 刷新公共黑名单等运行期配置。 */
            @Override
            public void run() {
                reloadPlugin();
            }
        });
        featureRegistry.register(trashFeature);
        featureRegistry.register(cleanupFeature);
        featureRegistry.register(protectionFeature);
        featureRegistry.register(banGuiFeature);
        this.entityLimitFeature = createEntityLimitFeature(configSupplier);
        featureRegistry.register(entityLimitFeature);
        registerCommands();
        registerPlaceholderApi();
        logCapabilities();
        featureRegistry.enableAll();
    }

    /** 创建当前运行环境的扣费服务。 */
    private PaymentService createPaymentService(RuntimeKind kind) {
        if (kind == RuntimeKind.LEGACY) {
            return new NoPaymentService();
        }
        return UniversalVaultPaymentService.create(this);
    }

    /** 创建当前运行环境的清理功能。 */
    private Feature createCleanupFeature(Supplier<ConfigBundle> configSupplier) {
        if (runtimeKind == RuntimeKind.FOLIA) {
            return createFoliaFeature(FOLIA_CLEANUP, new Class<?>[]{
                    Plugin.class,
                    ServerPlatform.class,
                    Supplier.class,
                    WorldTrashRouter.class,
                    GlobalTrashService.class,
                    PersonalTrashService.class,
                    DropOwnerTracker.class
            }, new Object[]{
                    this,
                    platform,
                    configSupplier,
                    trashRouter,
                    globalTrashService,
                    personalTrashService,
                    dropOwnerTracker
            });
        }
        return new CleanupFeature(this, platform, configSupplier, trashRouter, globalTrashService, personalTrashService, dropOwnerTracker);
    }

    /** 创建当前运行环境的实体限制功能。 */
    private Feature createEntityLimitFeature(Supplier<ConfigBundle> configSupplier) {
        if (runtimeKind == RuntimeKind.FOLIA) {
            return createFoliaFeature(FOLIA_ENTITY_LIMIT, new Class<?>[]{
                    Plugin.class,
                    Supplier.class,
                    BukkitMessageService.class
            }, new Object[]{
                    this,
                    configSupplier,
                    messageService
            });
        }
        return new EntityLimitFeature(this, configSupplier, messageService);
    }

    /** 通过反射创建 Folia 专用功能，避免旧端提前加载 Java 17 类。 */
    private Feature createFoliaFeature(String className, Class<?>[] parameterTypes, Object[] args) {
        try {
            Constructor<?> constructor = Class.forName(className).getConstructor(parameterTypes);
            return (Feature) constructor.newInstance(args);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("无法创建 Folia 功能: " + className, unwrap(exception));
        }
    }

    /** 检测并返回当前运行环境。 */
    private RuntimeKind detectRuntimeKind() {
        if (isFoliaServer()) {
            return RuntimeKind.FOLIA;
        }
        int minor = detectMinecraftMinorVersion();
        if (minor <= 12) {
            return RuntimeKind.LEGACY;
        }
        if (minor <= 15) {
            return RuntimeKind.BUKKIT;
        }
        return RuntimeKind.PAPER;
    }

    /** 创建当前运行环境的平台实现。 */
    private ServerPlatform createPlatform(RuntimeKind kind) {
        if (kind == RuntimeKind.FOLIA) {
            return instantiatePlatform(FOLIA_PLATFORM);
        }
        if (kind == RuntimeKind.PAPER) {
            return instantiatePlatform(PAPER_PLATFORM);
        }
        if (kind == RuntimeKind.BUKKIT) {
            return instantiatePlatform(BUKKIT_PLATFORM);
        }
        return instantiatePlatform(LEGACY_PLATFORM);
    }

    /** 通过反射创建平台实现，隔离高版本类加载。 */
    private ServerPlatform instantiatePlatform(String className) {
        try {
            Constructor<?> constructor = Class.forName(className).getConstructor(Plugin.class);
            return (ServerPlatform) constructor.newInstance(this);
        } catch (Throwable throwable) {
            throw new IllegalStateException("无法创建平台实现: " + className, unwrap(throwable));
        }
    }

    /** 判断当前服务端是否需要使用 region-threaded 分支。 */
    private boolean isFoliaServer() {
        return containsRegionThreadedMarker(Bukkit.getName()) || containsRegionThreadedMarker(Bukkit.getVersion());
    }

    /** 解析当前 Minecraft 小版本号。 */
    private int detectMinecraftMinorVersion() {
        String raw = Bukkit.getBukkitVersion();
        String[] parts = raw == null ? new String[0] : raw.split("[.-]");
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return parseLeadingNumber(parts[1], 20);
        }
        return 20;
    }

    /** 解析字符串开头的数字。 */
    private int parseLeadingNumber(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch < '0' || ch > '9') {
                break;
            }
            builder.append(ch);
        }
        if (builder.length() == 0) {
            return fallback;
        }
        try {
            return Integer.parseInt(builder.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /** 判断文本里是否明确标识 Folia/Luminol 这类 region-threaded 服务端。 */
    private boolean containsRegionThreadedMarker(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("folia") || normalized.contains("luminol");
    }

    /** 保存新架构默认配置文件。 */
    private void saveDefaultConfigs() {
        saveDefaultConfig();
        saveResourceIfMissing("platform.yml");
        saveResourceIfMissing("cleanup.yml");
        saveResourceIfMissing("trash.yml");
        saveResourceIfMissing("entity-limits.yml");
        saveResourceIfMissing("protections.yml");
        saveResourceIfMissing("messages/message_zh.yml");
        saveResourceIfMissing("messages/message_zh_TW.yml");
        saveResourceIfMissing("messages/message_en.yml");
        saveResourceIfMissing("messages/message_es.yml");
        saveResourceIfMissing("data/worlds.yml");
    }

    /** 仅在文件不存在时保存资源。 */
    private void saveResourceIfMissing(String path) {
        if (!getDataFolder().toPath().resolve(path).toFile().exists()) {
            saveResource(path, false);
        }
    }

    /** 读取拆分后的配置文件。 */
    private ConfigBundle loadConfigBundle() {
        ConfigBundleLoader loader = new ConfigBundleLoader();
        return loader.load(
                new BukkitConfigurationSource(getConfig()),
                new BukkitConfigurationSource(loadYaml("cleanup.yml")),
                new BukkitConfigurationSource(loadYaml("trash.yml")),
                new BukkitConfigurationSource(loadYaml("protections.yml")),
                new BukkitConfigurationSource(loadYaml("entity-limits.yml"))
        );
    }

    /** 从插件数据目录读取 YAML。 */
    private YamlConfiguration loadYaml(String path) {
        return YamlConfiguration.loadConfiguration(new File(getDataFolder(), path));
    }

    /** 注册主命令和旧命令别名。 */
    private void registerCommands() {
        UniversalCommand executor = new UniversalCommand(this);
        registerCommand("blworldtrashcan", executor);
        registerCommand("blwtc", executor);
        registerCommand("worldlisttrashcan", executor);
        registerCommand("WorldListTrashCan", executor);
        registerCommand("WTC", executor);
        registerCommand("wtc", executor);
    }

    /** 注册 PlaceholderAPI 变量。 */
    private void registerPlaceholderApi() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().info("[PlaceholderAPI] 未检测到 PlaceholderAPI，跳过变量注册。");
            return;
        }
        this.expansion = new UniversalPlaceholderExpansion(this);
        if (expansion.register()) {
            getLogger().info("[PlaceholderAPI] 已注册变量: %Wtc_ClearTime%");
        }
    }

    /** 注册单个命令执行器。 */
    private void registerCommand(String name, UniversalCommand executor) {
        PluginCommand command = getCommand(name);
        if (command != null) {
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
    }

    /** 输出当前产物能力报告。 */
    private void logCapabilities() {
        getLogger().info("Universal runtime: " + runtimeKind.name().toLowerCase(Locale.ROOT));
        getLogger().info("Platform: " + platform.id());
        for (Capability capability : Capability.values()) {
            String state = platform.capabilities().has(capability) ? "enabled" : "disabled";
            getLogger().info("Capability " + capability.name().toLowerCase(Locale.ROOT).replace('_', '-') + ": " + state);
        }
    }

    /** 查找测试箱子可使用的位置。 */
    private Block findDebugChestBlock(Player player) {
        Location base = player.getLocation();
        int[] yOffsets = new int[]{0, 1, -1, 2};
        for (int yOffset : yOffsets) {
            for (int radius = 1; radius <= 3; radius++) {
                Block block = findDebugChestBlockAtRadius(base, radius, yOffset);
                if (block != null) {
                    return block;
                }
            }
        }
        return null;
    }

    /** 在指定半径边缘查找测试箱子可用方块。 */
    private Block findDebugChestBlockAtRadius(Location base, int radius, int yOffset) {
        World world = base.getWorld();
        if (world == null) {
            return null;
        }
        for (int xOffset = -radius; xOffset <= radius; xOffset++) {
            for (int zOffset = -radius; zOffset <= radius; zOffset++) {
                if (Math.abs(xOffset) != radius && Math.abs(zOffset) != radius) {
                    continue;
                }
                Block block = world.getBlockAt(base.getBlockX() + xOffset, base.getBlockY() + yOffset, base.getBlockZ() + zOffset);
                if (block.getType() == Material.AIR || block.getType() == Material.CHEST) {
                    return block;
                }
            }
        }
        return null;
    }

    /** 在普通端直接运行，在 Folia 端提交到玩家实体上下文。 */
    private boolean runForPlayerRegion(final Player player, final Runnable runnable) {
        if (player == null || runnable == null) {
            return false;
        }
        if (!isFoliaRuntime()) {
            runnable.run();
            return true;
        }
        try {
            Object scheduler = player.getClass().getMethod("getScheduler").invoke(player);
            Runnable retired = new Runnable() {
                /** 玩家实体不可用时放弃本次测试任务。 */
                @Override
                public void run() {
                    getLogger().warning("[Debug] 玩家实体调度失败，已放弃测试任务: " + player.getName());
                }
            };
            Method execute = scheduler.getClass().getMethod("execute", Plugin.class, Runnable.class, Runnable.class, long.class);
            Object result = execute.invoke(scheduler, this, runnable, retired, Long.valueOf(1L));
            return !(result instanceof Boolean) || ((Boolean) result).booleanValue();
        } catch (ReflectiveOperationException exception) {
            getLogger().warning("[Debug] Folia 玩家实体调度不可用: " + exception.getMessage());
            return false;
        }
    }

    /** 调用清理功能并返回统计结果。 */
    private CleanupFeature.CleanupStats invokeCleanupStats(String methodName) {
        Object value = invokeNoArg(cleanupFeature, methodName, CleanupFeature.CleanupStats.empty());
        return value instanceof CleanupFeature.CleanupStats ? (CleanupFeature.CleanupStats) value : CleanupFeature.CleanupStats.empty();
    }

    /** 调用带一个 boolean 参数的清理功能并返回统计结果。 */
    private CleanupFeature.CleanupStats invokeCleanupStats(String methodName, boolean argument) {
        Object value = invokeBooleanArg(cleanupFeature, methodName, argument, CleanupFeature.CleanupStats.empty());
        return value instanceof CleanupFeature.CleanupStats ? (CleanupFeature.CleanupStats) value : CleanupFeature.CleanupStats.empty();
    }

    /** 调用无参布尔方法。 */
    private boolean invokeBoolean(Object target, String methodName, boolean fallback) {
        Object value = invokeNoArg(target, methodName, Boolean.valueOf(fallback));
        return value instanceof Boolean ? ((Boolean) value).booleanValue() : fallback;
    }

    /** 调用带一个 boolean 参数的布尔方法。 */
    private boolean invokeBoolean(Object target, String methodName, boolean argument, boolean fallback) {
        Object value = invokeBooleanArg(target, methodName, argument, Boolean.valueOf(fallback));
        return value instanceof Boolean ? ((Boolean) value).booleanValue() : fallback;
    }

    /** 调用带一个 boolean 参数的方法并在失败时返回默认值。 */
    private Object invokeBooleanArg(Object target, String methodName, boolean argument, Object fallback) {
        if (target == null) {
            return fallback;
        }
        try {
            return target.getClass().getMethod(methodName, boolean.class).invoke(target, Boolean.valueOf(argument));
        } catch (ReflectiveOperationException exception) {
            getLogger().warning("[Universal] 调用运行时方法失败: " + methodName + "(boolean), " + exception.getMessage());
            return fallback;
        }
    }

    /** 调用带一个 int 参数的方法并在失败时返回默认值。 */
    private Object invokeIntArg(Object target, String methodName, int argument, Object fallback) {
        if (target == null) {
            return fallback;
        }
        try {
            return target.getClass().getMethod(methodName, int.class).invoke(target, Integer.valueOf(argument));
        } catch (ReflectiveOperationException exception) {
            getLogger().warning("[Universal] 调用运行时方法失败: " + methodName + "(int), " + exception.getMessage());
            return fallback;
        }
    }

    /** 调用无参方法并在失败时返回默认值。 */
    private Object invokeNoArg(Object target, String methodName, Object fallback) {
        if (target == null) {
            return fallback;
        }
        try {
            return target.getClass().getMethod(methodName).invoke(target);
        } catch (ReflectiveOperationException exception) {
            getLogger().warning("[Universal] 调用运行时方法失败: " + methodName + ", " + exception.getMessage());
            return fallback;
        }
    }

    /** 拆开反射异常里的真实原因。 */
    private Throwable unwrap(Throwable throwable) {
        if (throwable instanceof InvocationTargetException && ((InvocationTargetException) throwable).getTargetException() != null) {
            return ((InvocationTargetException) throwable).getTargetException();
        }
        return throwable;
    }

    /** universal 运行时分支。 */
    private enum RuntimeKind {
        LEGACY,
        BUKKIT,
        PAPER,
        FOLIA
    }
}
