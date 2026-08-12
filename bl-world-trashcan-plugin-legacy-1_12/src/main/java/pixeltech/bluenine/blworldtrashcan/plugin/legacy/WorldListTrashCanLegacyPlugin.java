package pixeltech.bluenine.blworldtrashcan.plugin.legacy;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import pixeltech.bluenine.blworldtrashcan.bukkit.bstats.BStatsMetricsService;
import pixeltech.bluenine.blworldtrashcan.bukkit.bstats.Metrics;
import pixeltech.bluenine.blworldtrashcan.bukkit.config.BukkitConfigurationSource;
import pixeltech.bluenine.blworldtrashcan.bukkit.config.BukkitLegacyConfigMigrator;
import pixeltech.bluenine.blworldtrashcan.bukkit.feature.BanGuiFeature;
import pixeltech.bluenine.blworldtrashcan.bukkit.api.WorldListTrashCanApiHost;
import pixeltech.bluenine.blworldtrashcan.bukkit.feature.CleanupFeature;
import pixeltech.bluenine.blworldtrashcan.bukkit.feature.EntityLimitFeature;
import pixeltech.bluenine.blworldtrashcan.bukkit.feature.FeatureRegistry;
import pixeltech.bluenine.blworldtrashcan.bukkit.feature.ProtectionFeature;
import pixeltech.bluenine.blworldtrashcan.bukkit.feature.TrashFeature;
import pixeltech.bluenine.blworldtrashcan.bukkit.message.BukkitRgbDebugSender;
import pixeltech.bluenine.blworldtrashcan.bukkit.message.BukkitMessageService;
import pixeltech.bluenine.blworldtrashcan.bukkit.platform.ServerPlatform;
import pixeltech.bluenine.blworldtrashcan.bukkit.storage.BukkitYamlWorldTrashStorage;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.DropOwnerTracker;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.GlobalTrashService;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.CustomModelDataSupport;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.NoPaymentService;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.PersonalTrashService;
import pixeltech.bluenine.blworldtrashcan.bukkit.trash.WorldTrashRouter;
import pixeltech.bluenine.blworldtrashcan.config.ConfigBundle;
import pixeltech.bluenine.blworldtrashcan.config.ConfigBundleLoader;
import pixeltech.bluenine.blworldtrashcan.core.capability.Capability;
import pixeltech.bluenine.blworldtrashcan.core.trash.TrashRoute;
import pixeltech.bluenine.blworldtrashcan.platform.legacy.LegacyPlatform;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Legacy 1.12 产物入口。 */
public final class WorldListTrashCanLegacyPlugin extends JavaPlugin {
    private FeatureRegistry featureRegistry;
    private WorldListTrashCanApiHost apiHost;
    private ServerPlatform platform;
    private ConfigBundle configBundle;
    private CleanupFeature cleanupFeature;
    private TrashFeature trashFeature;
    private ProtectionFeature protectionFeature;
    private BanGuiFeature banGuiFeature;
    private WorldTrashRouter trashRouter;
    private WorldListTrashCanLegacyExpansion expansion;
    private BukkitMessageService messageService;
    private DropOwnerTracker dropOwnerTracker;
    private EntityLimitFeature entityLimitFeature;
    private Metrics metrics;
    private CustomModelDataSupport customModelDataSupport;

    /** 启动插件。 */
    @Override
    public void onEnable() {
        BukkitLegacyConfigMigrator configMigrator = new BukkitLegacyConfigMigrator(this);
        configMigrator.migrateIfNeeded();
        saveDefaultConfigs();
        configMigrator.repairCurrentRuntimeDefaults();
        this.customModelDataSupport = CustomModelDataSupport.unsupported(getLogger());
        getLogger().info("Capability custom-model-data: disabled (model-id skipped)");
        this.configBundle = loadConfigBundle();
        this.messageService = new BukkitMessageService(this);
        this.messageService.reload(configBundle.getLanguageFile());
        this.platform = new LegacyPlatform(this);
        this.apiHost = new WorldListTrashCanApiHost(this, platform);
        this.apiHost.enable();
        this.metrics = BStatsMetricsService.start(this, platform.id());
        this.featureRegistry = new FeatureRegistry();
        Supplier<ConfigBundle> configSupplier = new Supplier<ConfigBundle>() {
            /** 返回最新配置集合。 */
            @Override
            public ConfigBundle get() {
                return configBundle;
            }
        };
        GlobalTrashService globalTrashService = new GlobalTrashService(this,
                configBundle.getTrashConfig().getGlobalTrash(), messageService,
                platform.itemSnapshotMapper(), platform, apiHost.auditBridge(), customModelDataSupport);
        PersonalTrashService personalTrashService = new PersonalTrashService(this,
                configBundle.getTrashConfig().getPersonalTrash(), new NoPaymentService(), messageService,
                platform.itemSnapshotMapper(), platform, apiHost.auditBridge(),
                globalTrashService.getIdentityProvider(), customModelDataSupport);
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
        this.cleanupFeature = new CleanupFeature(this, platform, configSupplier, trashRouter, globalTrashService,
                personalTrashService, dropOwnerTracker, apiHost.auditBridge());
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
        this.entityLimitFeature = new EntityLimitFeature(this, configSupplier, messageService);
        featureRegistry.register(entityLimitFeature);
        registerCommands();
        registerPlaceholderApi();
        logCapabilities();
        featureRegistry.enableAll();
    }

    /** 禁用插件。 */
    @Override
    public void onDisable() {
        if (apiHost != null) {
            apiHost.disable();
        }
        if (featureRegistry != null) {
            featureRegistry.disableAll();
        }
        if (metrics != null) {
            metrics.shutdown();
            metrics = null;
        }
    }

    /** 重载插件。 */
    public void reloadPlugin() {
        saveDefaultConfigs();
        new BukkitLegacyConfigMigrator(this).repairCurrentRuntimeDefaults();
        reloadConfig();
        this.configBundle = loadConfigBundle();
        if (messageService != null) {
            messageService.reload(configBundle.getLanguageFile());
        }
        if (featureRegistry != null) {
            featureRegistry.reloadAll();
        }
    }

    /** 返回平台实现。 */
    public ServerPlatform getPlatform() {
        return platform;
    }

    /** 立即执行清理，默认遵守定时扫地门禁。 */
    public CleanupFeature.CleanupStats runCleanupNow() {
        return runCleanupNow(false);
    }

    /** 立即执行清理，可由命令入口决定是否忽略 guards。 */
    public CleanupFeature.CleanupStats runCleanupNow(boolean ignoreGuards) {
        return cleanupFeature.runNow(ignoreGuards);
    }

    /** 返回消息服务。 */
    public BukkitMessageService messages() {
        return messageService;
    }

    /** 返回最近一次清理统计。 */
    public CleanupFeature.CleanupStats getLastCleanupStats() {
        return cleanupFeature == null ? CleanupFeature.CleanupStats.empty() : cleanupFeature.getLastStats();
    }

    /** 测试用：触发清理通知指定编号，复用正式通知链路。 */
    public boolean debugCleanupNotify(int count) {
        return cleanupFeature != null && cleanupFeature.debugNotify(count);
    }

    /** 打开公共垃圾桶。 */
    public void openGlobalTrash(Player player) {
        trashFeature.openGlobal(player);
    }

    /** 打开个人垃圾桶。 */
    public void openPersonalTrash(Player player) {
        trashFeature.openPersonal(player);
    }

    /** 测试用：向玩家发送所有 RGB 或降级色可见通道。 */
    public void debugRgb(Player player) {
        BukkitRgbDebugSender.send(this, player);
    }

    /** 测试用：只向玩家发送聊天、ActionBar 和 Title RGB 或降级色可见通道。 */
    public void debugRgbChannels(Player player) {
        BukkitRgbDebugSender.sendChatActionTitle(player);
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

    /** 返回下一次清理剩余秒数。 */
    public long getRemainingClearSeconds() {
        return cleanupFeature == null ? 0L : cleanupFeature.getRemainingSeconds();
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

    /** 返回已加载个人垃圾桶数量。 */
    public int getPersonalTrashInventoryCount() {
        return trashFeature == null ? 0 : trashFeature.getPersonalInventoryCount();
    }

    /** 调整当前世界垃圾桶上限。 */
    public int addWorldTrashMax(World world, int delta) {
        return trashRouter.addMaxCount(world, delta, configBundle.getTrashConfig().getWorldTrash().getDefaultMaxCount());
    }

    /** 测试用：在玩家附近创建并登记一个世界垃圾桶箱子。 */
    public boolean debugCreateWorldTrash(Player player) {
        Block block = findDebugChestBlock(player);
        if (block == null) {
            getLogger().warning("[Debug] 未找到可放置测试箱子的位置: " + player.getName());
            return false;
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
        return saved;
    }

    /** 测试用：直接走垃圾桶路由服务投递物品。 */
    public boolean debugRoute(Player player, TrashRoute route, Material material, int amount) {
        ItemStack itemStack = new ItemStack(material, amount);
        boolean routed = trashRouter.route(player.getWorld(), player.getUniqueId(), itemStack, route);
        getLogger().info("[Debug] debugRoute player=" + player.getName()
                + ", route=" + route
                + ", material=" + material.name()
                + ", amount=" + amount
                + ", routed=" + routed);
        return routed;
    }

    /** 测试用：在玩家位置生成一个掉落物。 */
    public boolean debugDrop(Player player, Material material, int amount, boolean markOwner) {
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
        return true;
    }

    /** 测试用：通过正式损坏事件验证玩家掉落物短期回收。 */
    public boolean debugDamageRecovery(Player player, Material material, int amount) {
        boolean recovered = trashFeature.debugDamageRecovery(player, material, amount);
        getLogger().info("[Debug] debugDamageRecovery player=" + player.getName()
                + ", material=" + material.name()
                + ", amount=" + amount
                + ", recovered=" + recovered);
        return recovered;
    }

    /** 测试用：生成当前玩家关联的路由状态摘要。 */
    public List<String> debugSummary(Player player) {
        List<String> lines = new ArrayList<>();
        lines.add("§aWorldListTrashCan debug summary:");
        lines.add("§7- §f平台: §a" + platform.id());
        lines.add("§7- §f世界: §a" + player.getWorld().getName());
        lines.add("§7- §f世界垃圾桶数量: §a" + trashRouter.getWorldTrashCount(player.getWorld()));
        lines.add("§7- §f世界垃圾桶物品: §a" + trashRouter.getWorldTrashStoredItemAmount(player.getWorld())
                + " §7(堆叠 " + trashRouter.getWorldTrashStoredStackCount(player.getWorld()) + ")");
        lines.add("§7- §f跳过未加载区块: §a" + trashRouter.getSkippedUnloadedChunkAccesses());
        lines.add("§7- §f公共垃圾桶物品: §a" + trashFeature.getGlobalStoredItemAmount()
                + " §7(堆叠 " + trashFeature.getGlobalStoredStackCount() + ")");
        lines.add("§7- §f个人垃圾桶物品: §a" + trashFeature.getPersonalStoredItemAmount(player.getUniqueId())
                + " §7(堆叠 " + trashFeature.getPersonalStoredStackCount(player.getUniqueId()) + ")");
        return lines;
    }

    /** 测试用：输出实体密度扫描统计。 */
    public List<String> debugEntityLimits() {
        if (entityLimitFeature == null) {
            List<String> lines = new ArrayList<>();
            lines.add("§e实体限制功能尚未初始化。");
            return lines;
        }
        return entityLimitFeature.debugStats();
    }

    /** 查找测试箱子可使用的位置。 */
    private Block findDebugChestBlock(Player player) {
        Location base = player.getLocation();
        int[][] offsets = new int[][]{
                {1, 0, 0},
                {-1, 0, 0},
                {0, 0, 1},
                {0, 0, -1},
                {2, 0, 0},
                {0, 0, 2}
        };
        for (int[] offset : offsets) {
            Block block = base.getWorld().getBlockAt(base.getBlockX() + offset[0], base.getBlockY() + offset[1], base.getBlockZ() + offset[2]);
            if (block.getType() == Material.AIR || block.getType() == Material.CHEST) {
                return block;
            }
        }
        return null;
    }

    /** 保存默认配置文件。 */
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

    /** 读取配置集合。 */
    private ConfigBundle loadConfigBundle() {
        ConfigBundleLoader loader = new ConfigBundleLoader();
        return loader.load(
                new BukkitConfigurationSource(getConfig()),
                new BukkitConfigurationSource(loadYaml("cleanup.yml")),
                new BukkitConfigurationSource(loadYaml("trash.yml")),
                new BukkitConfigurationSource(loadYaml("protections.yml")),
                new BukkitConfigurationSource(loadYaml("entity-limits.yml")),
                false
        );
    }

    /** 读取 YAML 文件。 */
    private YamlConfiguration loadYaml(String path) {
        return YamlConfiguration.loadConfiguration(new File(getDataFolder(), path));
    }

    /** 注册命令。 */
    private void registerCommands() {
        WorldListTrashCanLegacyCommand executor = new WorldListTrashCanLegacyCommand(this, apiHost.commandRegistry());
        registerCommand("worldlisttrashcan", executor);
    }

    /** 注册 PlaceholderAPI 变量。 */
    private void registerPlaceholderApi() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().info("[PlaceholderAPI] 未检测到 PlaceholderAPI，跳过变量注册。");
            return;
        }
        this.expansion = new WorldListTrashCanLegacyExpansion(this);
        if (expansion.register()) {
            getLogger().info("[PlaceholderAPI] 已注册变量: %Wtc_ClearTime%");
        }
    }

    /** 注册单个命令。 */
    private void registerCommand(String name, WorldListTrashCanLegacyCommand executor) {
        PluginCommand command = getCommand(name);
        if (command != null) {
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
    }

    /** 输出平台能力。 */
    private void logCapabilities() {
        getLogger().info("Platform: " + platform.id());
        for (Capability capability : Capability.values()) {
            String state = platform.capabilities().has(capability) ? "enabled" : "disabled";
            getLogger().info("Capability " + capability.name().toLowerCase().replace('_', '-') + ": " + state);
        }
    }
}
