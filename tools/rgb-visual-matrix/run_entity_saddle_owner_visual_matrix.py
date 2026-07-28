import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import time
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

import run_legacy_migration_matrix as legacy
import run_rgb_external_server_matrix as external
import run_rgb_visual_matrix as base


EVIDENCE_ROOT = base.REPO / "docs" / "test-evidence"
BUILD_ROOT = base.REPO / "build" / "entity-saddle-owner-visual-matrix"
UNIVERSAL_JAR = base.REPO / "dist" / "WorldListTrashCan-universal.jar"
JAVAC17 = base.REPO / "build" / "tools" / "jdk-17.0.19+10" / "bin" / "javac.exe"
JAR17 = base.REPO / "build" / "tools" / "jdk-17.0.19+10" / "bin" / "jar.exe"
BUKKIT_API_JAR = Path.home() / ".m2" / "repository" / "org" / "spigotmc" / "spigot-api" / "1.12.2-R0.1-SNAPSHOT" / "spigot-api-1.12.2-R0.1-SNAPSHOT.jar"
FIXTURE_NAME = "WtcEntitySaddleOwnerFixture"
FIXTURE_JAR_NAME = FIXTURE_NAME + ".jar"
TARGET_CASE_IDS = ["managed_paper1122", "external_paper1218"]


FIXTURE_SOURCE = r'''
package ai.wtc.fixture;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Wolf;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** WorldListTrashCan 鞍与 Bukkit Tameable 主人保护验收夹具。 */
public final class EntitySaddleOwnerFixturePlugin extends JavaPlugin implements CommandExecutor {
    private final Map<String, UUID> protectedEntities = new LinkedHashMap<String, UUID>();
    private final Map<String, UUID> removableEntities = new LinkedHashMap<String, UUID>();
    private final List<BlockState> platformStates = new ArrayList<BlockState>();
    private boolean striderSupported;
    private boolean camelSupported;
    private boolean catSupported;
    private boolean itemOwnerSupported;
    private boolean pdcOwnerSupported;
    private Location observerLocation;

    /** 注册专项验收命令。 */
    @Override
    public void onEnable() {
        getCommand("entityguardfixture").setExecutor(this);
    }

    /** 分派夹具准备、断言与清理命令。 */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            emit(sender, "AI_ENTITY_GUARD_USAGE prepare|focus|assert|cleanup");
            return true;
        }
        if ("prepare".equalsIgnoreCase(args[0])) {
            prepare(sender);
            return true;
        }
        if ("assert".equalsIgnoreCase(args[0])) {
            assertState(sender);
            return true;
        }
        if ("focus".equalsIgnoreCase(args[0])) {
            focus(sender);
            return true;
        }
        if ("cleanup".equalsIgnoreCase(args[0])) {
            cleanup();
            emit(sender, "AI_ENTITY_GUARD_CLEANUP done=true");
            return true;
        }
        emit(sender, "AI_ENTITY_GUARD_USAGE prepare|focus|assert|cleanup");
        return true;
    }

    /** 创建必须保留和必须清理的正反向实体。 */
    private void prepare(CommandSender sender) {
        cleanup();
        Player player = resolvePlayer(sender);
        if (player == null) {
            emit(sender, "AI_ENTITY_GUARD_PREPARED success=false reason=no-player");
            return;
        }
        Location base = player.getLocation().clone();

        Pig saddledPig = (Pig) spawn(base, 0, "PIG", "AI_GUARD_PROTECT_SADDLED_PIG");
        saddledPig.setSaddle(true);
        trackProtected("saddled_pig", saddledPig);

        Horse saddledHorse = (Horse) spawn(base, 1, "HORSE", "AI_GUARD_PROTECT_SADDLED_HORSE");
        equipHorseSaddle(saddledHorse);
        trackProtected("saddled_horse", saddledHorse);

        Wolf ownedWolf = (Wolf) spawn(base, 2, "WOLF", "AI_GUARD_PROTECT_OWNED_WOLF");
        tame(ownedWolf, player);
        trackProtected("owned_wolf", ownedWolf);

        Horse ownedHorse = (Horse) spawn(base, 3, "HORSE", "AI_GUARD_PROTECT_OWNED_HORSE");
        tame(ownedHorse, player);
        trackProtected("owned_horse", ownedHorse);

        Entity ownedCat = spawnOwnedCat(base, 4, player);
        catSupported = ownedCat != null;
        if (ownedCat != null) {
            trackProtected("owned_cat", ownedCat);
        }

        Entity strider = spawnOptional(base, 5, "STRIDER", "AI_GUARD_PROTECT_SADDLED_STRIDER");
        striderSupported = strider != null && setBooleanMethod(strider, "setSaddle", true);
        if (striderSupported) {
            trackProtected("saddled_strider", strider);
        } else {
            removeNow(strider);
        }

        Entity camel = spawnOptional(base, 6, "CAMEL", "AI_GUARD_PROTECT_SADDLED_CAMEL");
        camelSupported = camel instanceof AbstractHorse && equipHorseSaddle((AbstractHorse) camel);
        if (camelSupported) {
            trackProtected("saddled_camel", camel);
        } else {
            removeNow(camel);
        }

        trackRemovable("unsaddled_pig", spawn(base, 7, "PIG", "AI_GUARD_REMOVE_UNSADDLED_PIG"));
        trackRemovable("unowned_horse", spawn(base, 8, "HORSE", "AI_GUARD_REMOVE_UNOWNED_HORSE"));
        trackRemovable("unowned_wolf", spawn(base, 9, "WOLF", "AI_GUARD_REMOVE_UNOWNED_WOLF"));

        Arrow arrow = base.getWorld().spawnArrow(slot(base, 10), new Vector(0.0, 0.0, 0.01), 0.1F, 0.0F);
        arrow.setShooter(player);
        nameEntity(arrow, "AI_GUARD_REMOVE_ARROW_WITH_SHOOTER");
        trackRemovable("arrow_with_shooter", arrow);

        TNTPrimed tnt = (TNTPrimed) spawn(base, 11, "PRIMED_TNT", "AI_GUARD_REMOVE_TNT_WITH_SOURCE");
        tnt.setFuseTicks(32000);
        boolean tntSourceSupported = setSingleArgumentMethod(tnt, "setSource", player);
        trackRemovable("tnt_with_source", tnt);

        Item item = base.getWorld().dropItem(slot(base, 12), new ItemStack(Material.STONE, 7));
        nameEntity(item, "AI_GUARD_REMOVE_ITEM_WITH_OWNER");
        item.setPickupDelay(32000);
        itemOwnerSupported = setSingleArgumentMethod(item, "setOwner", player.getUniqueId());
        trackRemovable("item_with_owner", item);

        ArmorStand mythicMetadata = (ArmorStand) spawn(base, 13, "ARMOR_STAND", "AI_GUARD_REMOVE_MYTHIC_METADATA_OWNER");
        mythicMetadata.setMetadata("owner", new FixedMetadataValue(this, player.getUniqueId().toString()));
        mythicMetadata.setMetadata("MythicOwner", new FixedMetadataValue(this, player.getUniqueId().toString()));
        mythicMetadata.setMetadata("MythicMobType", new FixedMetadataValue(this, "AI_TEST_PET"));
        trackRemovable("mythic_metadata_owner", mythicMetadata);

        ArmorStand nbtOwner = (ArmorStand) spawn(base, 14, "ARMOR_STAND", "AI_GUARD_REMOVE_NBT_PDC_OWNER");
        pdcOwnerSupported = writePdcOwner(nbtOwner, player.getUniqueId());
        addScoreboardOwnerTag(nbtOwner, player.getUniqueId());
        trackRemovable("nbt_pdc_owner", nbtOwner);

        focusPlayer(player, base);

        emit(sender, "AI_ENTITY_GUARD_PREPARED success=true protected=" + protectedEntities.size()
                + " removable=" + removableEntities.size()
                + " striderSupported=" + striderSupported
                + " camelSupported=" + camelSupported
                + " catSupported=" + catSupported
                + " itemOwnerSupported=" + itemOwnerSupported
                + " pdcOwnerSupported=" + pdcOwnerSupported
                + " tntSourceSupported=" + tntSourceSupported);
        sender.sendMessage("实体保护夹具已生成：前排为应保留坐骑/宠物，后排为应清理的伪主人来源。");
    }

    /** 输出正式清理后的逐 UUID 存活结果。 */
    private void assertState(CommandSender sender) {
        int protectedAlive = countMatching(protectedEntities, true);
        int removableGone = countMatching(removableEntities, false);
        boolean allPassed = protectedAlive == protectedEntities.size()
                && removableGone == removableEntities.size()
                && !protectedEntities.isEmpty() && !removableEntities.isEmpty();
        emit(sender, "AI_ENTITY_GUARD_PROTECTED " + stateText(protectedEntities, true));
        emit(sender, "AI_ENTITY_GUARD_NEGATIVE " + stateText(removableEntities, false));
        emit(sender, "AI_ENTITY_GUARD_RESULT allPassed=" + allPassed
                + " protectedAlive=" + protectedAlive + "/" + protectedEntities.size()
                + " removableGone=" + removableGone + "/" + removableEntities.size());
        sender.sendMessage(allPassed
                ? "实体保护验收通过：仅有鞍实体和 Bukkit Tameable 主人被保护。"
                : "实体保护验收失败：请查看 AI_ENTITY_GUARD_* 明细。");
    }

    /** 恢复适合观察全部测试实体的玩家视角。 */
    private void focus(CommandSender sender) {
        Player player = resolvePlayer(sender);
        if (player == null || observerLocation == null) {
            emit(sender, "AI_ENTITY_GUARD_FOCUS success=false");
            return;
        }
        player.teleport(observerLocation);
        emit(sender, "AI_ENTITY_GUARD_FOCUS success=true");
    }

    /** 清理夹具生成的实体并恢复临时平台方块。 */
    private void cleanup() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : new ArrayList<Entity>(world.getEntities())) {
                String customName = entity.getCustomName();
                if (customName != null && customName.startsWith("AI_GUARD_")) {
                    entity.remove();
                }
            }
        }
        for (int index = platformStates.size() - 1; index >= 0; index--) {
            platformStates.get(index).update(true, false);
        }
        platformStates.clear();
        protectedEntities.clear();
        removableEntities.clear();
        striderSupported = false;
        camelSupported = false;
        catSupported = false;
        itemOwnerSupported = false;
        pdcOwnerSupported = false;
        observerLocation = null;
    }

    /** 返回执行命令的玩家，控制台调用时退化为第一个在线玩家。 */
    private Player resolvePlayer(CommandSender sender) {
        if (sender instanceof Player) {
            return (Player) sender;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            return player;
        }
        return null;
    }

    /** 在玩家视野前方的固定网格槽位生成实体。 */
    private Entity spawn(Location base, int index, String typeName, String customName) {
        EntityType type = EntityType.valueOf(typeName);
        Entity entity = base.getWorld().spawnEntity(slot(base, index), type);
        nameEntity(entity, customName);
        freeze(entity);
        return entity;
    }

    /** 尝试生成当前版本才存在的实体类型。 */
    private Entity spawnOptional(Location base, int index, String typeName, String customName) {
        try {
            return spawn(base, index, typeName, customName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 生成当前版本可识别的已驯服猫或豹猫。 */
    private Entity spawnOwnedCat(Location base, int index, Player owner) {
        for (String typeName : new String[]{"CAT", "OCELOT"}) {
            Entity entity = spawnOptional(base, index, typeName, "AI_GUARD_PROTECT_OWNED_CAT");
            if (entity instanceof Tameable) {
                tame((Tameable) entity, owner);
                return entity;
            }
            removeNow(entity);
        }
        return null;
    }

    /** 计算玩家视野前方网格槽位并铺设临时玻璃平台。 */
    private Location slot(Location base, int index) {
        Location location = position(base, index);
        Location floor = location.clone().add(0.0, -1.0, 0.0);
        platformStates.add(floor.getBlock().getState());
        floor.getBlock().setType(Material.GLASS);
        return location;
    }

    /** 计算玩家视野前方固定网格槽位，但不修改方块。 */
    private Location position(Location base, int index) {
        Vector forward = base.getDirection().setY(0.0);
        if (forward.lengthSquared() < 0.01) {
            forward = new Vector(0.0, 0.0, 1.0);
        } else {
            forward.normalize();
        }
        Vector right = new Vector(-forward.getZ(), 0.0, forward.getX());
        int column = index % 5;
        int row = index / 5;
        Location location = base.clone()
                .add(forward.clone().multiply(5.0 + row * 3.0))
                .add(right.multiply((column - 2) * 2.0));
        location.setY(Math.floor(base.getY()));
        return location;
    }

    /** 保存并应用朝向测试网格中心的观察机位。 */
    private void focusPlayer(Player player, Location base) {
        Location observer = base.clone();
        Location target = position(base, 7).add(0.0, 1.0, 0.0);
        Vector eye = observer.toVector().add(new Vector(0.0, 1.62, 0.0));
        observer.setDirection(target.toVector().subtract(eye));
        observer.setPitch(Bukkit.getBukkitVersion().startsWith("1.12") ? 40.0F : 10.0F);
        observerLocation = observer.clone();
        player.teleport(observerLocation);
    }

    /** 设置可见测试名，便于真实客户端截图识别。 */
    private void nameEntity(Entity entity, String customName) {
        if (entity == null) {
            return;
        }
        entity.setCustomName(customName);
        entity.setCustomNameVisible(true);
    }

    /** 尽量关闭生物 AI，保持截图位置稳定。 */
    private void freeze(Entity entity) {
        if (!(entity instanceof LivingEntity)) {
            return;
        }
        try {
            Method method = entity.getClass().getMethod("setAI", boolean.class);
            method.invoke(entity, Boolean.FALSE);
        } catch (Throwable ignored) {
            entity.setVelocity(new Vector(0.0, 0.0, 0.0));
        }
    }

    /** 给 Bukkit 马类实际装备鞍。 */
    private boolean equipHorseSaddle(AbstractHorse horse) {
        if (horse == null) {
            return false;
        }
        horse.getInventory().setSaddle(new ItemStack(Material.SADDLE, 1));
        ItemStack saddle = horse.getInventory().getSaddle();
        return saddle != null && saddle.getType() == Material.SADDLE;
    }

    /** 把实体设置为有 Bukkit Tameable 主人的状态。 */
    private void tame(Tameable tameable, Player owner) {
        tameable.setTamed(true);
        tameable.setOwner(owner);
    }

    /** 反射调用一个 boolean 参数方法。 */
    private boolean setBooleanMethod(Object target, String methodName, boolean value) {
        if (target == null) {
            return false;
        }
        try {
            Method method = target.getClass().getMethod(methodName, boolean.class);
            method.invoke(target, Boolean.valueOf(value));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 反射调用一个与实参类型兼容的单参数方法。 */
    private boolean setSingleArgumentMethod(Object target, String methodName, Object argument) {
        if (target == null || argument == null) {
            return false;
        }
        for (Method method : target.getClass().getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!methodName.equals(method.getName()) || types.length != 1
                    || !types[0].isAssignableFrom(argument.getClass())) {
                continue;
            }
            try {
                method.invoke(target, argument);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    /** 写入 NBT 背书的 Bukkit PDC owner 字段，模拟非 Tameable 私有主人数据。 */
    private boolean writePdcOwner(Entity entity, UUID ownerId) {
        try {
            Class<?> keyType = Class.forName("org.bukkit.NamespacedKey");
            Constructor<?> keyConstructor = keyType.getConstructor(org.bukkit.plugin.Plugin.class, String.class);
            Object key = keyConstructor.newInstance(this, "owner");
            Class<?> dataType = Class.forName("org.bukkit.persistence.PersistentDataType");
            Field stringField = dataType.getField("STRING");
            Object stringType = stringField.get(null);
            Object container = entity.getClass().getMethod("getPersistentDataContainer").invoke(entity);
            for (Method method : container.getClass().getMethods()) {
                if ("set".equals(method.getName()) && method.getParameterTypes().length == 3) {
                    method.invoke(container, key, stringType, ownerId.toString());
                    return true;
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    /** 写入类似模组私有 owner 的 scoreboard tag，确保它不进入主人判断。 */
    private void addScoreboardOwnerTag(Entity entity, UUID ownerId) {
        try {
            Method method = entity.getClass().getMethod("addScoreboardTag", String.class);
            method.invoke(entity, "mod_owner:" + ownerId.toString());
        } catch (Throwable ignored) {
            // 旧 Bukkit 不支持 scoreboard tag，不影响该版本的 Tameable 边界断言。
        }
    }

    /** 记录必须在清理后存活的实体。 */
    private void trackProtected(String key, Entity entity) {
        if (entity != null) {
            protectedEntities.put(key, entity.getUniqueId());
        }
    }

    /** 记录必须在清理后消失的实体。 */
    private void trackRemovable(String key, Entity entity) {
        if (entity != null) {
            removableEntities.put(key, entity.getUniqueId());
        }
    }

    /** 统计实体状态与预期一致的数量。 */
    private int countMatching(Map<String, UUID> entities, boolean expectedExists) {
        int matched = 0;
        for (UUID uniqueId : entities.values()) {
            if (exists(uniqueId) == expectedExists) {
                matched++;
            }
        }
        return matched;
    }

    /** 生成逐实体的存活或移除断言文本。 */
    private String stateText(Map<String, UUID> entities, boolean expectedExists) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, UUID> entry : entities.entrySet()) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            boolean actualExists = exists(entry.getValue());
            builder.append(entry.getKey()).append('=')
                    .append(expectedExists ? actualExists : !actualExists);
        }
        return builder.toString();
    }

    /** 按 UUID 判断实体当前是否仍然存在。 */
    private boolean exists(UUID uniqueId) {
        if (uniqueId == null) {
            return false;
        }
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (uniqueId.equals(entity.getUniqueId())) {
                    return !entity.isDead();
                }
            }
        }
        return false;
    }

    /** 立即移除未被当前版本支持的可选实体。 */
    private void removeNow(Entity entity) {
        if (entity != null) {
            entity.remove();
        }
    }

    /** 同时向命令发送者和服务端日志输出稳定机器标记。 */
    private void emit(CommandSender sender, String message) {
        sender.sendMessage(message);
        getLogger().info(message);
    }
}
'''


PLUGIN_YML = """name: WtcEntitySaddleOwnerFixture
version: 1.0.0
main: ai.wtc.fixture.EntitySaddleOwnerFixturePlugin
commands:
  entityguardfixture:
    description: WorldListTrashCan saddle and Bukkit Tameable owner fixture
"""


def log(message: str) -> None:
    """输出带时间戳的专项日志。"""
    print(time.strftime("[%H:%M:%S] ") + message, flush=True)


def write_json(path: Path, data) -> None:
    """按 UTF-8 写入 JSON 文件。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(external.to_json_value(data), ensure_ascii=False, indent=2), encoding="utf-8")


def selected_cases(case_id: str | None) -> list[dict]:
    """返回 1.12.2 与现代 Paper 的 universal 整包用例。"""
    selected = []
    for wanted in TARGET_CASE_IDS:
        for case in external.EXTERNAL_MATRIX:
            if case["id"] == wanted:
                selected.append(external.universal_case(case))
                break
    if not case_id:
        return selected
    for case in selected:
        if case_id in (case["id"], case.get("sourceId", ""), case["version"], case["label"]):
            return [case]
    raise RuntimeError("未知鞍/主人保护测试用例: " + case_id)


def isolate_modern_case(case: dict, run_root: Path) -> dict:
    """把现代 Paper 用例切到本轮独立服务端，避开旧插件 remap 缓存。"""
    if str(case["version"]) == "1.12.2":
        return case
    isolated = dict(case)
    source_server_dir = Path(case["serverDir"])
    source_server_jar = source_server_dir / str(case["serverJar"])
    if not source_server_jar.is_file():
        raise RuntimeError("现代隔离测试缺少服务端核心: " + str(source_server_jar))
    isolated["serverSourceJar"] = source_server_jar
    isolated["serverDir"] = run_root / ("isolated-server-" + str(case["id"]))
    isolated["managedConfig"] = True
    isolated["extraPlugins"] = []
    isolated["readyTimeout"] = max(360, int(case.get("readyTimeout", 150)))
    isolated["joinTimeout"] = max(150, int(case.get("joinTimeout", 120)))
    return isolated


def ensure_inputs() -> None:
    """检查插件、编译器和 Bukkit API 输入。"""
    missing = [path for path in (UNIVERSAL_JAR, JAVAC17, JAR17, BUKKIT_API_JAR) if not path.is_file()]
    if missing:
        raise RuntimeError("缺少鞍/主人保护专项输入: " + "; ".join(str(path) for path in missing))


def audit_mapper_contract() -> dict:
    """静态确认四个平台只用 Bukkit Tameable 判断主人。"""
    mapper_paths = [
        base.REPO / "bl-world-trashcan-platform-legacy-1_12" / "src" / "main" / "java" / "pixeltech" / "bluenine" / "blworldtrashcan" / "platform" / "legacy" / "LegacyEntitySnapshotMapper.java",
        base.REPO / "bl-world-trashcan-platform-bukkit-1_13_1_15" / "src" / "main" / "java" / "pixeltech" / "bluenine" / "blworldtrashcan" / "platform" / "bukkit" / "BukkitEntitySnapshotMapper.java",
        base.REPO / "bl-world-trashcan-platform-paper-1_16_1_20" / "src" / "main" / "java" / "pixeltech" / "bluenine" / "blworldtrashcan" / "platform" / "paper" / "PaperEntitySnapshotMapper.java",
        base.REPO / "bl-world-trashcan-platform-folia-1_20" / "src" / "main" / "java" / "pixeltech" / "bluenine" / "blworldtrashcan" / "platform" / "folia" / "FoliaEntitySnapshotMapper.java",
    ]
    forbidden = ["getShooter(", "TNTPrimed", "getSource(", "Metadata", "PersistentData", "NBT"]
    results = []
    for path in mapper_paths:
        text = path.read_text(encoding="utf-8")
        required = [
            "entity instanceof Tameable",
            "((Tameable) entity).getOwner() != null",
        ]
        missing = [marker for marker in required if marker not in text]
        found_forbidden = [marker for marker in forbidden if marker in text]
        if text.count("getOwner()") != 1:
            missing.append("exactly-one-getOwner")
        if missing or found_forbidden:
            raise AssertionError(path.name + " 主人边界不符合契约 missing=" + str(missing)
                                 + " forbidden=" + str(found_forbidden))
        results.append({"path": str(path), "status": "PASS", "getOwnerCalls": 1})
    return {"status": "PASS", "mappers": results, "forbiddenMarkers": forbidden}


def build_fixture(run_root: Path) -> Path:
    """编译 Java 8 兼容的跨版本 Bukkit 夹具。"""
    source_dir = run_root / "fixture-src" / "ai" / "wtc" / "fixture"
    classes_dir = run_root / "fixture-classes"
    resources_dir = run_root / "fixture-resources"
    fixture_jar = run_root / FIXTURE_JAR_NAME
    source_dir.mkdir(parents=True, exist_ok=True)
    classes_dir.mkdir(parents=True, exist_ok=True)
    resources_dir.mkdir(parents=True, exist_ok=True)
    source = source_dir / "EntitySaddleOwnerFixturePlugin.java"
    source.write_text(FIXTURE_SOURCE, encoding="utf-8")
    (resources_dir / "plugin.yml").write_text(PLUGIN_YML, encoding="utf-8")
    subprocess.run([
        str(JAVAC17), "--release", "8", "-encoding", "UTF-8",
        "-cp", str(BUKKIT_API_JAR), "-d", str(classes_dir), str(source),
    ], cwd=run_root, check=True)
    subprocess.run([
        str(JAR17), "cf", str(fixture_jar),
        "-C", str(classes_dir), ".", "-C", str(resources_dir), ".",
    ], cwd=run_root, check=True)
    return fixture_jar


def backup_file(path: Path, backup_dir: Path) -> dict:
    """备份测试会修改或替换的单个文件。"""
    backup = backup_dir / (path.name + ".before")
    backup.parent.mkdir(parents=True, exist_ok=True)
    if path.is_file():
        shutil.copy2(path, backup)
        return {"target": path, "backup": backup, "existed": True}
    return {"target": path, "backup": backup, "existed": False}


def restore_backups(backups: list[dict]) -> None:
    """恢复测试前文件状态。"""
    for item in backups:
        target = Path(item["target"])
        backup = Path(item["backup"])
        if item.get("existed") and backup.is_file():
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(backup, target)
        elif not item.get("existed") and target.is_file():
            target.unlink()


def deploy_fixture(case: dict, fixture_jar: Path, backup_dir: Path) -> dict:
    """部署专项夹具并返回恢复信息。"""
    target = Path(case["serverDir"]) / "plugins" / FIXTURE_JAR_NAME
    backup = backup_file(target, backup_dir)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(fixture_jar, target)
    return backup


def insert_blacklist_entries(text: str, entries: list[str]) -> str:
    """在 entities.blacklist 列表中补入专项实体类型。"""
    lines = text.splitlines(True)
    blacklist_index = -1
    for index, line in enumerate(lines):
        if line.startswith("  blacklist:"):
            blacklist_index = index
            break
    if blacklist_index < 0:
        raise RuntimeError("cleanup.yml 缺少 entities.blacklist")
    insert_at = len(lines)
    for index in range(blacklist_index + 1, len(lines)):
        stripped = lines[index].strip()
        indent = len(lines[index]) - len(lines[index].lstrip(" "))
        if stripped and not stripped.startswith("#") and indent <= 2:
            insert_at = index
            break
    existing = {line.strip()[2:].strip().strip('"\'') for line in lines[blacklist_index + 1:insert_at]
                if line.strip().startswith("- ")}
    additions = ["    - \"" + entry + "\"\n" for entry in entries if entry not in existing]
    lines[insert_at:insert_at] = additions
    return "".join(lines)


def ensure_entity_guard_keys(text: str) -> str:
    """给旧测试配置补入本轮两个实体保护键。"""
    missing_saddle = "ignore-entities-with-saddle:" not in text
    missing_owner = "ignore-entities-with-owner:" not in text
    if not missing_saddle and not missing_owner:
        return text
    lines = text.splitlines(True)
    insert_at = -1
    for index, line in enumerate(lines):
        if line.startswith("  ignore-entities-in-boat:"):
            insert_at = index + 1
            break
    if insert_at < 0:
        raise RuntimeError("cleanup.yml 缺少 entities.ignore-entities-in-boat，无法定位保护键")
    additions = []
    if missing_saddle:
        additions.extend([
            "  # AI 专项临时补入：跳过实际装备鞍的实体。\n",
            "  ignore-entities-with-saddle: true\n",
        ])
    if missing_owner:
        additions.extend([
            "  # AI 专项临时补入：只保护拥有 Bukkit Tameable 主人的实体。\n",
            "  ignore-entities-with-owner: true\n",
        ])
    lines[insert_at:insert_at] = additions
    return "".join(lines)


def patch_cleanup_config(case: dict) -> Path:
    """开启专项清理规则和两个默认保护开关。"""
    path = Path(case["serverDir"]) / "plugins" / "WorldListTrashCan" / "cleanup.yml"
    if not path.is_file():
        raise RuntimeError("cleanup.yml 不存在: " + str(path))
    text = path.read_text(encoding="utf-8", errors="replace")
    text = ensure_entity_guard_keys(text)
    text = external.update_yaml_scalars(text, {
        "interval-seconds": "0",
        "guards.min-online-players": "0",
        "guards.min-total-entities": "0",
        "entities.enabled": "true",
        "entities.clear-animals": "true",
        "entities.clear-projectiles": "true",
        "entities.clear-named-entities": "true",
        "entities.ignore-entities-with-saddle": "true",
        "entities.ignore-entities-with-owner": "true",
    })
    text = insert_blacklist_entries(text, [
        "PIG", "HORSE", "WOLF", "OCELOT", "CAT", "STRIDER", "CAMEL",
        "PRIMED_TNT", "TNT", "ARMOR_STAND",
    ])
    path.write_text(text, encoding="utf-8")
    return path


def run_console(process, command_log: Path, command: str, wait: float = 0.3) -> None:
    """发送服务端控制台命令并等待短暂生效。"""
    external.send_console_command(process, command, command_log)
    time.sleep(wait)


def is_legacy(case: dict) -> bool:
    """判断当前用例是否为 1.12.2。"""
    return str(case["version"]) == "1.12.2"


def setup_player(case: dict, username: str, process, command_log: Path) -> None:
    """设置玩家测试位置、权限和稳定世界状态。"""
    commands = [
        "op " + username,
        "minecraft:gamerule doMobSpawning false",
        "minecraft:gamerule doDaylightCycle false",
        "minecraft:time set day",
        "minecraft:weather clear",
    ]
    if is_legacy(case):
        commands.extend([
            "minecraft:gamemode 1 " + username,
            "minecraft:tp " + username + " 0 91 -8 0 12",
        ])
    else:
        commands.extend([
            "minecraft:gamemode creative " + username,
            "minecraft:tp " + username + " 0 91 -8 0 12",
        ])
    for command in commands:
        run_console(process, command_log, command, 0.15)
    time.sleep(1.0)


def focus_game(case: dict) -> int:
    """聚焦当前版本 Minecraft 窗口。"""
    hwnd = base.find_minecraft_window(case["version"])
    rect = base.focus_window(hwnd)
    base.click_game(hwnd, rect, 0.50, 0.30)
    time.sleep(0.3)
    return hwnd


def send_client_command(case: dict, command: str) -> None:
    """通过真实客户端聊天框发送玩家命令。"""
    hwnd = focus_game(case)
    base.send_chat_line_by_window_message(hwnd, command)


def capture_named_screenshot(case: dict, game_dir: Path, run_dir: Path, name: str) -> Path:
    """保存真实客户端 F2 截图并生成稳定文件名。"""
    source = base.capture_internal_screenshot(case, game_dir, run_dir)
    target = run_dir / "screenshots" / (case["id"] + "-" + name + ".png")
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)
    return target


def wait_marker(server_log: Path, offset: int, markers: list[str], timeout: float = 20.0) -> str:
    """等待服务端日志出现全部专项标记。"""
    deadline = time.time() + timeout
    last = ""
    while time.time() < deadline:
        text = external.strip_ansi(external.read_text_since(server_log, offset))
        if text:
            last = text
        if all(marker in text for marker in markers):
            return text
        time.sleep(0.4)
    raise TimeoutError("等待专项标记超时 markers=" + str(markers) + " latest=" + last[-1600:])


def screenshot_info(path: Path) -> dict:
    """读取截图尺寸、亮度和 SHA256。"""
    image = Image.open(path).convert("RGB")
    return {
        "path": str(path),
        "size": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "dimensions": list(image.size),
        "brightness": base.image_brightness(image),
    }


def font(size: int = 18) -> ImageFont.ImageFont:
    """返回支持中文的证据图字体。"""
    for path in (Path(r"C:\Windows\Fonts\msyh.ttc"), Path(r"C:\Windows\Fonts\simsun.ttc")):
        if path.is_file():
            return ImageFont.truetype(str(path), size)
    return ImageFont.load_default()


def render_server_screenshot(text: str, target: Path, title: str) -> Path:
    """把服务端专项标记渲染成 PNG 辅助证据。"""
    useful = [line for line in external.strip_ansi(text).splitlines() if "AI_ENTITY_GUARD_" in line]
    lines = [title, ""] + useful[-18:]
    image = Image.new("RGB", (1680, max(240, 30 * (len(lines) + 2))), (15, 23, 42))
    draw = ImageDraw.Draw(image)
    used_font = font(19)
    for index, line in enumerate(lines):
        draw.text((24, 20 + index * 30), line[:190],
                  fill=(250, 204, 21) if index == 0 else (226, 232, 240), font=used_font)
    target.parent.mkdir(parents=True, exist_ok=True)
    image.save(target)
    return target


def expected_support_markers(case: dict) -> list[str]:
    """返回当前版本必须支持或不支持的动态 API 标记。"""
    if is_legacy(case):
        return [
            "striderSupported=false", "camelSupported=false", "catSupported=true",
            "itemOwnerSupported=false", "pdcOwnerSupported=false", "tntSourceSupported=false",
        ]
    return [
        "striderSupported=true", "camelSupported=true", "catSupported=true",
        "itemOwnerSupported=true", "pdcOwnerSupported=true", "tntSourceSupported=true",
    ]


def assert_config(path: Path) -> None:
    """断言运行配置确实启用了两个保护和所有负向目标。"""
    text = path.read_text(encoding="utf-8")
    markers = [
        "ignore-entities-with-saddle: true",
        "ignore-entities-with-owner: true",
        "clear-animals: true",
        "clear-projectiles: true",
        "clear-named-entities: true",
        '- "PIG"', '- "HORSE"', '- "WOLF"', '- "ARMOR_STAND"',
    ]
    missing = [marker for marker in markers if marker not in text]
    if missing:
        raise AssertionError("cleanup.yml 专项配置缺失: " + str(missing))


def run_case(case: dict, fixture_jar: Path, prepared_clients: dict, evidence_root: Path) -> dict:
    """运行单个版本的真实客户端鞍/主人保护验收。"""
    case = dict(case)
    run_dir = evidence_root / case["id"]
    run_dir.mkdir(parents=True, exist_ok=True)
    case["runId"] = evidence_root.name
    server_log = run_dir / "logs" / (case["id"] + "-server-console.log")
    command_log = run_dir / "logs" / (case["id"] + "-console-commands.log")
    backup_dir = run_dir / "logs" / "file-backup"
    process = None
    client = None
    backups = []
    result = {
        "id": case["id"],
        "label": case["label"],
        "version": case["version"],
        "status": "FAIL",
        "artifact": external.artifact_summary_for_plugin(case),
        "screenshots": [],
    }
    try:
        backups.append(deploy_fixture(case, fixture_jar, backup_dir))
        process = external.launch_server(case, run_dir)
        cleanup_path = Path(case["serverDir"]) / "plugins" / "WorldListTrashCan" / "cleanup.yml"
        backups.append(backup_file(cleanup_path, backup_dir))
        patched_cleanup = patch_cleanup_config(case)
        assert_config(patched_cleanup)
        run_console(process, command_log, "wtc reload", 1.0)

        prepared = prepared_clients[case["version"]]
        client, username, game_dir = base.launch_client(case, prepared, run_dir)
        base.ACTIVE_CLIENT_PID = client.pid
        result["username"] = username
        external.wait_player_online(case, username, server_log)
        setup_player(case, username, process, command_log)

        prepare_offset = external.log_text_offset(server_log)
        send_client_command(case, "/entityguardfixture prepare")
        prepare_text = wait_marker(server_log, prepare_offset,
                                   ["AI_ENTITY_GUARD_PREPARED success=true"] + expected_support_markers(case), 20.0)
        run_console(process, command_log, "entityguardfixture focus", 0.8)
        time.sleep(1.5)
        before = capture_named_screenshot(case, game_dir, run_dir, "01-before-clear")
        result["screenshots"].append(screenshot_info(before))

        clear_offset = external.log_text_offset(server_log)
        send_client_command(case, "/wtc clear true")
        time.sleep(3.0)
        run_console(process, command_log, "entityguardfixture focus", 0.8)
        after_clear = capture_named_screenshot(case, game_dir, run_dir, "02-after-clear")
        result["screenshots"].append(screenshot_info(after_clear))

        assert_offset = external.log_text_offset(server_log)
        send_client_command(case, "/entityguardfixture assert")
        assert_text = wait_marker(server_log, assert_offset, [
            "AI_ENTITY_GUARD_RESULT allPassed=true",
            "AI_ENTITY_GUARD_PROTECTED",
            "AI_ENTITY_GUARD_NEGATIVE",
        ], 20.0)
        time.sleep(1.2)
        assertion = capture_named_screenshot(case, game_dir, run_dir, "03-client-assertion")
        result["screenshots"].append(screenshot_info(assertion))
        server_shot = render_server_screenshot(
            prepare_text + "\n" + external.read_text_since(server_log, clear_offset) + "\n" + assert_text,
            run_dir / "server-screenshots" / (case["id"] + "-server-assertion.png"),
            case["label"] + " 鞍与 Bukkit Tameable 主人保护",
        )
        result["serverScreenshot"] = screenshot_info(server_shot)
        result["prepareExcerpt"] = prepare_text[-2400:]
        result["assertExcerpt"] = assert_text[-3200:]
        result["configSnapshot"] = str(run_dir / "logs" / "cleanup-under-test.yml")
        shutil.copy2(patched_cleanup, Path(result["configSnapshot"]))
        result["status"] = "PASS"
        send_client_command(case, "/entityguardfixture cleanup")
        time.sleep(0.5)
    except Exception as exc:
        result["error"] = repr(exc)
        log(case["id"] + " 失败: " + repr(exc))
        if "game_dir" in locals():
            try:
                failure = capture_named_screenshot(case, game_dir, run_dir, "failure")
                result["failureScreenshot"] = screenshot_info(failure)
            except Exception:
                pass
    finally:
        if client is not None:
            external.stop_process(client)
        base.ACTIVE_CLIENT_PID = None
        if process is not None:
            external.stop_process(process, "stop")
        restore_backups(backups)
    write_json(run_dir / "result.json", result)
    return result


def make_contact_sheet(results: list[dict], evidence_root: Path) -> Path:
    """生成所有客户端和服务端截图联系表。"""
    paths = []
    for result in results:
        paths.extend(Path(item["path"]) for item in result.get("screenshots", []))
        if result.get("serverScreenshot"):
            paths.append(Path(result["serverScreenshot"]["path"]))
    if not paths:
        return Path("")
    tiles = []
    for path in paths:
        image = Image.open(path).convert("RGB")
        image.thumbnail((500, 280))
        tile = Image.new("RGB", (520, 325), (15, 23, 42))
        tile.paste(image, ((520 - image.width) // 2, 8))
        ImageDraw.Draw(tile).text((12, 294), path.name[:66], fill=(226, 232, 240), font=font(17))
        tiles.append(tile)
    columns = 2
    rows = (len(tiles) + columns - 1) // columns
    sheet = Image.new("RGB", (columns * 520, rows * 325), (2, 6, 23))
    for index, tile in enumerate(tiles):
        sheet.paste(tile, ((index % columns) * 520, (index // columns) * 325))
    target = evidence_root / "entity-saddle-owner-contact-sheet.png"
    sheet.save(target)
    return target


def write_readme(evidence_root: Path, summary: dict) -> None:
    """写入专项证据说明。"""
    lines = [
        "# 鞍与 Bukkit Tameable 主人保护专项验收",
        "",
        "- 被测插件：`dist/WorldListTrashCan-universal.jar`",
        "- SHA256：`" + summary["jarSha256"] + "`",
        "- 真实客户端：1.12.2 与 1.21.8，各保留清理前、清理后、客户端断言截图。",
        "- 主人边界：四个平台映射器只读取 Bukkit `Tameable.getOwner()`。",
        "- 负向边界：Projectile shooter、TNT source、Item owner、Mythic 风格 Metadata、NBT 背书 PDC/scoreboard owner 均不能触发实体主人保护。",
        "- 模组私有 NBT 说明：Bukkit 无通用 API 可读取模组实体私有 NBT；源码契约禁止读取 NBT，现代端再用 NBT 背书的 PDC owner 做运行态负向验证。",
        "- 总结论：" + ("PASS" if summary["allPassed"] else "FAIL"),
        "",
    ]
    for result in summary["results"]:
        lines.extend([
            "## " + result["label"],
            "",
            "- 结果：`" + result["status"] + "`",
            "- 客户端截图：`" + result["id"] + "/screenshots/`",
            "- 服务端截图：`" + result["id"] + "/server-screenshots/`",
            "- 完整日志：`" + result["id"] + "/logs/`",
            "- 机器结果：`" + result["id"] + "/result.json`",
            "",
        ])
    lines.extend([
        "## 判定语义",
        "",
        "- 有鞍猪、马，以及现代端炽足兽、骆驼必须存活。",
        "- 有 Bukkit 主人的狼、猫、无鞍马必须存活。",
        "- 无鞍猪、无主人狼/马，以及五类非 Tameable owner 来源必须被正常清理。",
        "- 每个实体按 UUID 断言，不以名称数量或肉眼截图单独代替机器结果。",
        "",
    ])
    (evidence_root / "README.md").write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    """执行鞍与 Bukkit Tameable 主人真实客户端专项矩阵。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", default="")
    args = parser.parse_args()
    ensure_inputs()
    mapper_contract = audit_mapper_contract()
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    evidence_root = EVIDENCE_ROOT / ("entity-saddle-owner-visual-" + timestamp)
    run_root = BUILD_ROOT / timestamp
    evidence_root.mkdir(parents=True, exist_ok=True)
    run_root.mkdir(parents=True, exist_ok=True)
    fixture_jar = build_fixture(run_root)
    cases = selected_cases(args.case or None)
    cases = [isolate_modern_case(case, run_root) for case in cases]
    prepared_clients = {}
    results = []
    for case in cases:
        prepared_clients.setdefault(case["version"], base.ensure_client(case["version"]))
        results.append(run_case(case, fixture_jar, prepared_clients, evidence_root))
        write_json(evidence_root / "summary.json", {"results": results, "mapperContract": mapper_contract})
    contact_sheet = make_contact_sheet(results, evidence_root)
    summary = {
        "timestamp": timestamp,
        "allPassed": all(result.get("status") == "PASS" for result in results),
        "jar": str(UNIVERSAL_JAR),
        "jarSha256": hashlib.sha256(UNIVERSAL_JAR.read_bytes()).hexdigest(),
        "fixtureJar": str(fixture_jar),
        "fixtureSha256": hashlib.sha256(fixture_jar.read_bytes()).hexdigest(),
        "mapperContract": mapper_contract,
        "contactSheet": str(contact_sheet),
        "results": results,
    }
    write_json(evidence_root / "summary.json", summary)
    write_readme(evidence_root, summary)
    failed = [result for result in results if result.get("status") != "PASS"]
    log("鞍/主人保护矩阵完成 total=" + str(len(results)) + " failed=" + str(len(failed))
        + " evidence=" + str(evidence_root))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
