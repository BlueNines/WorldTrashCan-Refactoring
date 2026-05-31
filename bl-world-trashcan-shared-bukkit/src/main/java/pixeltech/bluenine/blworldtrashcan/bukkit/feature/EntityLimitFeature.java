package pixeltech.bluenine.blworldtrashcan.bukkit.feature;

import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.Plugin;
import pixeltech.bluenine.blworldtrashcan.config.ConfigBundle;
import pixeltech.bluenine.blworldtrashcan.config.EntityLimitConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** 世界实体数量限制和密集实体清理功能。 */
public final class EntityLimitFeature implements Feature, Listener {
    private final Plugin plugin;
    private final Supplier<ConfigBundle> configSupplier;
    private boolean registered;

    /** 创建实体限制功能。 */
    public EntityLimitFeature(Plugin plugin, Supplier<ConfigBundle> configSupplier) {
        this.plugin = plugin;
        this.configSupplier = configSupplier;
    }

    /** 返回功能 ID。 */
    @Override
    public String id() {
        return "entity-limits";
    }

    /** 注册监听器。 */
    @Override
    public void enable() {
        if (!registered) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            registered = true;
        }
    }

    /** 实体限制无需特殊重载状态。 */
    @Override
    public void reload() {
    }

    /** 取消注册监听器。 */
    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        registered = false;
    }

    /** 在实体生成时检查数量和密集限制。 */
    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        EntityLimitConfig config = configSupplier.get().getEntityLimitConfig();
        if (handleWorldLimit(event, config.getWorldLimit())) {
            return;
        }
        handleGatherLimit(event, config.getGatherLimit());
    }

    /** 处理单世界实体上限。 */
    private boolean handleWorldLimit(CreatureSpawnEvent event, EntityLimitConfig.WorldLimitConfig config) {
        World world = event.getLocation().getWorld();
        EntityType type = event.getEntityType();
        if (!config.isEnabled() || world == null || config.isIgnoredWorld(world.getName())) {
            return false;
        }
        int maxCount = config.getMaxCount(type.name());
        if (maxCount <= 0 || countEntity(world, type) < maxCount) {
            return false;
        }
        event.setCancelled(true);
        event.getEntity().remove();
        return true;
    }

    /** 处理密集实体限制。 */
    private void handleGatherLimit(CreatureSpawnEvent event, EntityLimitConfig.GatherLimitConfig config) {
        World world = event.getLocation().getWorld();
        EntityType type = event.getEntityType();
        if (!config.isEnabled() || world == null || config.isIgnoredWorld(world.getName())) {
            return;
        }
        EntityLimitConfig.GatherRule rule = config.getRule(type.name());
        if (rule == null) {
            return;
        }
        List<Entity> sameType = new ArrayList<>();
        for (Entity entity : event.getEntity().getNearbyEntities(rule.getRadius(), rule.getRadius(), rule.getRadius())) {
            if (entity.getType() == type) {
                sameType.add(entity);
            }
        }
        if (sameType.size() + 1 <= rule.getMaxCount()) {
            return;
        }
        int removed = 0;
        for (Entity entity : sameType) {
            removeEntity(entity, config.isDropItems());
            removed++;
            if (removed >= rule.getRemoveCount()) {
                break;
            }
        }
        plugin.getLogger().info(ChatColor.stripColor("[EntityLimit] 清理密集实体 type=" + type.name() + ", removed=" + removed));
    }

    /** 统计指定世界里的实体数量。 */
    private int countEntity(World world, EntityType type) {
        int count = 0;
        for (Entity entity : world.getEntities()) {
            if (entity.getType() == type) {
                count++;
            }
        }
        return count;
    }

    /** 按配置移除实体。 */
    private void removeEntity(Entity entity, boolean dropItems) {
        if (dropItems && entity instanceof LivingEntity) {
            ((LivingEntity) entity).setHealth(0D);
            return;
        }
        entity.remove();
    }
}
