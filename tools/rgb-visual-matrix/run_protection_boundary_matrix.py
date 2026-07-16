import hashlib
import json
import shutil
import subprocess
import time
from pathlib import Path

import run_legacy_migration_matrix as legacy


EVIDENCE_ROOT = legacy.REPO / "docs" / "test-evidence"
BUILD_ROOT = legacy.REPO / "build" / "protection-boundary-matrix"
UNIVERSAL_JAR = legacy.UNIVERSAL_JAR
JAVA25 = legacy.REPO / "build" / "tools" / "jre-25.0.3+9" / "bin" / "java.exe"
JAVAC17 = legacy.REPO / "build" / "tools" / "jdk-17.0.19+10" / "bin" / "javac.exe"
JAR17 = legacy.REPO / "build" / "tools" / "jdk-17.0.19+10" / "bin" / "jar.exe"
SPIGOT2612_JAR = Path(r"E:\server_work\spigot-26.1.2-test-server\spigot-26.1.2.jar")
BUKKIT_API_JAR = Path.home() / ".m2" / "repository" / "org" / "spigotmc" / "spigot-api" / "1.12.2-R0.1-SNAPSHOT" / "spigot-api-1.12.2-R0.1-SNAPSHOT.jar"


FIXTURE_SOURCE = r'''
package ai.blwtc.fixture;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

/** BlWorldTrashCan 保护边界验收夹具。 */
public final class ProtectionFixturePlugin extends JavaPlugin implements CommandExecutor {
    private static final UUID FAKE_PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-00000000a168");
    private static final int ARROW_OFFSET = 16;
    private static final int FARMLAND_OFFSET = 24;

    /** 注册 protectionfixture 命令。 */
    @Override
    public void onEnable() {
        getCommand("protectionfixture").setExecutor(this);
    }

    /** 执行夹具命令。 */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("AI_PROTECTION_USAGE arrow|farmland|cleanup");
            return true;
        }
        if ("arrow".equalsIgnoreCase(args[0])) {
            checkArrowCleanup(sender);
            return true;
        }
        if ("farmland".equalsIgnoreCase(args[0])) {
            checkFarmlandProtection(sender);
            return true;
        }
        if ("cleanup".equalsIgnoreCase(args[0])) {
            cleanup();
            sender.sendMessage("AI_PROTECTION_CLEANUP done=true");
            return true;
        }
        sender.sendMessage("AI_PROTECTION_USAGE arrow|farmland|cleanup");
        return true;
    }

    /** 验证不可拾取箭矢和追踪箭矢命中后会被正式监听器移除。 */
    private void checkArrowCleanup(CommandSender sender) {
        cleanup();
        World world = mainWorld();
        Location base = world.getSpawnLocation().clone().add(ARROW_OFFSET, 3.0, 0.0);
        keepChunkLoaded(base, true);

        Arrow unpickableArrow = spawnArrow(world, base, "AI_PROTECTION_UNPICKABLE_ARROW");
        boolean pickupStatusSet = setArrowPickupStatus(unpickableArrow, "DISALLOWED");
        Bukkit.getPluginManager().callEvent(new ProjectileHitEvent(unpickableArrow));
        boolean unpickableAlive = exists(unpickableArrow);

        Skeleton skeleton = (Skeleton) world.spawnEntity(base.clone().add(2.0, 0.0, 0.0), EntityType.SKELETON);
        skeleton.setCustomName("AI_PROTECTION_SKELETON");
        Arrow trackedArrow = spawnArrow(world, base.clone().add(2.0, 1.0, 0.0), "AI_PROTECTION_TRACKED_ARROW");
        ItemStack bow = new ItemStack(Material.BOW, 1);
        bow.addUnsafeEnchantment(Enchantment.ARROW_INFINITE, 1);
        EntityShootBowEvent shootEvent = createShootBowEvent(skeleton, bow, trackedArrow);
        Bukkit.getPluginManager().callEvent(shootEvent);
        ProjectileHitEvent hitEvent = new ProjectileHitEvent(trackedArrow);
        Bukkit.getPluginManager().callEvent(hitEvent);
        boolean trackedAlive = exists(trackedArrow);

        sender.sendMessage("AI_PROTECTION_ARROW unpickableRemoved=" + !unpickableAlive
                + " trackedRemoved=" + !trackedAlive
                + " unpickableAliveAfter=" + unpickableAlive
                + " trackedAliveAfter=" + trackedAlive
                + " pickupStatusSet=" + pickupStatusSet
                + " pickupStatusAfter=" + pickupStatusName(unpickableArrow)
                + " shootCancelled=" + shootEvent.isCancelled()
                + " hitEntity=" + hitEvent.getEntity().getType().name());
    }

    /** 验证实体和玩家踩踏农田事件都会被正式监听器取消。 */
    private void checkFarmlandProtection(CommandSender sender) {
        cleanup();
        World world = mainWorld();
        Location base = world.getSpawnLocation().clone().add(FARMLAND_OFFSET, 0.0, 0.0);
        keepChunkLoaded(base, true);
        Block farmland = base.getBlock();
        farmland.setType(material("FARMLAND", "SOIL", "LEGACY_SOIL"));
        Cow cow = (Cow) world.spawnEntity(base.clone().add(0.5, 1.0, 0.5), EntityType.COW);
        cow.setCustomName("AI_PROTECTION_FARMLAND_COW");

        EntityInteractEvent entityEvent = new EntityInteractEvent(cow, farmland);
        Bukkit.getPluginManager().callEvent(entityEvent);
        PlayerInteractEvent playerEvent = new PlayerInteractEvent(fakePlayer(world), Action.PHYSICAL, null, farmland, BlockFace.UP);
        Bukkit.getPluginManager().callEvent(playerEvent);

        sender.sendMessage("AI_PROTECTION_FARMLAND entityCancelled=" + entityEvent.isCancelled()
                + " playerCancelled=" + playerEvent.isCancelled()
                + " farmlandType=" + farmland.getType().name());
    }

    /** 清理本夹具生成的实体和方块。 */
    private void cleanup() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (isFixtureEntity(entity)) {
                    entity.remove();
                }
            }
        }
        clearArea(mainWorld().getSpawnLocation().clone().add(ARROW_OFFSET, 0.0, 0.0));
        clearArea(mainWorld().getSpawnLocation().clone().add(FARMLAND_OFFSET, 0.0, 0.0));
    }

    /** 生成测试箭矢。 */
    private Arrow spawnArrow(World world, Location location, String name) {
        Arrow arrow = world.spawnArrow(location, new Vector(0.0, 0.0, 0.0), 0.0F, 0.0F);
        arrow.setCustomName(name);
        return arrow;
    }

    /** 兼容旧版和新版 EntityShootBowEvent 构造器。 */
    private EntityShootBowEvent createShootBowEvent(Skeleton skeleton, ItemStack bow, Arrow arrow) {
        for (Constructor<?> constructor : EntityShootBowEvent.class.getConstructors()) {
            Object[] args = shootBowArgs(constructor.getParameterTypes(), skeleton, bow, arrow);
            if (args == null) {
                continue;
            }
            try {
                return (EntityShootBowEvent) constructor.newInstance(args);
            } catch (Throwable ignored) {
                // 继续尝试其它构造器。
            }
        }
        throw new IllegalStateException("No compatible EntityShootBowEvent constructor");
    }

    /** 为当前服务端的 EntityShootBowEvent 构造器组装参数。 */
    private Object[] shootBowArgs(Class<?>[] types, Skeleton skeleton, ItemStack bow, Arrow arrow) {
        Object[] args = new Object[types.length];
        int itemStackIndex = 0;
        for (int index = 0; index < types.length; index++) {
            Class<?> type = types[index];
            if (LivingEntity.class.isAssignableFrom(type)) {
                args[index] = skeleton;
            } else if (type == ItemStack.class) {
                args[index] = itemStackIndex++ == 0 ? bow : new ItemStack(Material.ARROW, 1);
            } else if (type.isInstance(arrow)) {
                args[index] = arrow;
            } else if (type == Float.TYPE || type == Float.class) {
                args[index] = Float.valueOf(1.0F);
            } else if (type == Boolean.TYPE || type == Boolean.class) {
                args[index] = Boolean.TRUE;
            } else if (type.isEnum() && "org.bukkit.inventory.EquipmentSlot".equals(type.getName())) {
                args[index] = enumValue(type, "HAND");
            } else {
                return null;
            }
        }
        return args;
    }

    /** 兼容 Arrow.PickupStatus 和 AbstractArrow.PickupStatus 设置拾取状态。 */
    private boolean setArrowPickupStatus(Arrow arrow, String statusName) {
        for (Method method : arrow.getClass().getMethods()) {
            if (!"setPickupStatus".equals(method.getName()) || method.getParameterTypes().length != 1) {
                continue;
            }
            Class<?> parameterType = method.getParameterTypes()[0];
            if (!parameterType.isEnum()) {
                continue;
            }
            try {
                Object status = enumValue(parameterType, statusName);
                method.invoke(arrow, status);
                return statusName.equals(pickupStatusName(arrow));
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    /** 读取箭矢当前拾取状态名称。 */
    private String pickupStatusName(Arrow arrow) {
        try {
            Object status = arrow.getClass().getMethod("getPickupStatus").invoke(arrow);
            return status == null ? "null" : String.valueOf(status);
        } catch (Throwable ignored) {
            return "unavailable";
        }
    }

    /** 返回枚举常量。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object enumValue(Class<?> enumType, String name) {
        return Enum.valueOf((Class<? extends Enum>) enumType, name);
    }

    /** 判断实体是否仍然存在。 */
    private boolean exists(Entity entity) {
        return entity != null && !entity.isDead() && find(entity.getUniqueId()) != null;
    }

    /** 查找指定 UUID 的实体。 */
    private Entity find(UUID uniqueId) {
        if (uniqueId == null) {
            return null;
        }
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (uniqueId.equals(entity.getUniqueId())) {
                    return entity;
                }
            }
        }
        return null;
    }

    /** 在无玩家临时服务端里保持测试 chunk 已加载。 */
    private void keepChunkLoaded(Location location, boolean forceLoaded) {
        if (location == null) {
            return;
        }
        Chunk chunk = location.getChunk();
        chunk.load(true);
        try {
            Method method = chunk.getClass().getMethod("setForceLoaded", boolean.class);
            method.invoke(chunk, Boolean.valueOf(forceLoaded));
        } catch (Throwable ignored) {
            // 1.12.2 没有 setForceLoaded，load(true) 已足够覆盖本轮夹具。
        }
    }

    /** 清理测试区域中的方块。 */
    private void clearArea(Location base) {
        if (base == null || base.getWorld() == null) {
            return;
        }
        keepChunkLoaded(base, false);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = 0; dy <= 4; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    base.clone().add(dx, dy, dz).getBlock().setType(Material.AIR);
                }
            }
        }
    }

    /** 判断实体是否是夹具生成的实体。 */
    private boolean isFixtureEntity(Entity entity) {
        if (entity == null) {
            return false;
        }
        String customName = entity.getCustomName();
        return customName != null && customName.startsWith("AI_PROTECTION_");
    }

    /** 创建只覆盖本测试所需方法的临时 Player 代理。 */
    private Player fakePlayer(final World world) {
        InvocationHandler handler = new InvocationHandler() {
            /** 处理 Player 代理方法。 */
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if ("getName".equals(name)) {
                    return "AIProtectionFixturePlayer";
                }
                if ("getUniqueId".equals(name)) {
                    return FAKE_PLAYER_ID;
                }
                if ("isOp".equals(name) || "hasPermission".equals(name)) {
                    return Boolean.TRUE;
                }
                if ("getWorld".equals(name)) {
                    return world;
                }
                if ("getLocation".equals(name)) {
                    return world.getSpawnLocation();
                }
                if ("getServer".equals(name)) {
                    return Bukkit.getServer();
                }
                if ("isOnline".equals(name) || "isValid".equals(name)) {
                    return Boolean.TRUE;
                }
                if ("equals".equals(name)) {
                    return Boolean.valueOf(args != null && args.length == 1 && proxy == args[0]);
                }
                if ("hashCode".equals(name)) {
                    return Integer.valueOf(FAKE_PLAYER_ID.hashCode());
                }
                if ("toString".equals(name)) {
                    return "AIProtectionFixturePlayer";
                }
                return defaultValue(method.getReturnType());
            }
        };
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class[] { Player.class }, handler);
    }

    /** 返回代理方法的类型默认值。 */
    private Object defaultValue(Class<?> type) {
        if (type == Void.TYPE) {
            return null;
        }
        if (type == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (type == Byte.TYPE) {
            return Byte.valueOf((byte) 0);
        }
        if (type == Short.TYPE) {
            return Short.valueOf((short) 0);
        }
        if (type == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        if (type == Long.TYPE) {
            return Long.valueOf(0L);
        }
        if (type == Float.TYPE) {
            return Float.valueOf(0F);
        }
        if (type == Double.TYPE) {
            return Double.valueOf(0D);
        }
        if (type == Character.TYPE) {
            return Character.valueOf((char) 0);
        }
        return null;
    }

    /** 兼容不同版本 Material 名称。 */
    private Material material(String... names) {
        for (String name : names) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                return material;
            }
        }
        return Material.SOIL;
    }

    /** 返回主世界。 */
    private World mainWorld() {
        return Bukkit.getWorlds().get(0);
    }
}
'''


PLUGIN_YML = """name: BlWtcProtectionFixture
version: 1.0.0
main: ai.blwtc.fixture.ProtectionFixturePlugin
commands:
  protectionfixture:
    description: BlWorldTrashCan protection boundary fixture
"""


def log(message: str) -> None:
    """输出带时间戳的脚本日志。"""
    print(time.strftime("[%H:%M:%S] ") + message, flush=True)


def write_json(path: Path, data) -> None:
    """按 UTF-8 写入 JSON。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(legacy.to_json_value(data), ensure_ascii=False, indent=2), encoding="utf-8")


def sha256_file(path: Path) -> str:
    """计算文件 SHA256。"""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def ensure_inputs() -> None:
    """确认 F-068/F-069 验证所需运行环境存在。"""
    sync_universal_dist()
    missing = []
    for path in (UNIVERSAL_JAR, legacy.PAPER1122_JAR, SPIGOT2612_JAR, legacy.JAVA8, JAVA25, JAVAC17, JAR17, BUKKIT_API_JAR):
        if not path.is_file():
            missing.append(str(path))
    if missing:
        raise RuntimeError("缺少保护边界验证输入: " + "; ".join(missing))


def sync_universal_dist() -> None:
    """把 Maven 最新 universal 整包同步到 dist。"""
    candidates = [
        legacy.REPO / "bl-world-trashcan-plugin-universal" / "target" / "bl-world-trashcan-plugin-universal-7.0.0.jar",
        legacy.REPO / "bl-world-trashcan-plugin-universal" / "target" / "bl-world-trashcan-plugin-universal-7.0.0-shaded.jar",
    ]
    existing = [path for path in candidates if path.is_file()]
    if not existing:
        return
    latest = max(existing, key=lambda path: path.stat().st_mtime)
    UNIVERSAL_JAR.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(latest, UNIVERSAL_JAR)


def build_fixture(run_root: Path) -> Path:
    """编译临时 Bukkit 测试插件。"""
    source_dir = run_root / "fixture-src" / "ai" / "blwtc" / "fixture"
    classes_dir = run_root / "fixture-classes"
    resources_dir = run_root / "fixture-resources"
    fixture_jar = run_root / "BlWtcProtectionFixture.jar"
    if classes_dir.exists():
        shutil.rmtree(classes_dir)
    if resources_dir.exists():
        shutil.rmtree(resources_dir)
    source_dir.mkdir(parents=True, exist_ok=True)
    resources_dir.mkdir(parents=True, exist_ok=True)
    (source_dir / "ProtectionFixturePlugin.java").write_text(FIXTURE_SOURCE, encoding="utf-8")
    (resources_dir / "plugin.yml").write_text(PLUGIN_YML, encoding="utf-8")
    subprocess.run([
        str(JAVAC17),
        "--release", "8",
        "-encoding", "UTF-8",
        "-cp", str(BUKKIT_API_JAR),
        "-d", str(classes_dir),
        str(source_dir / "ProtectionFixturePlugin.java"),
    ], check=True)
    if fixture_jar.exists():
        fixture_jar.unlink()
    subprocess.run([
        str(JAR17),
        "cf", str(fixture_jar),
        "-C", str(classes_dir), ".",
        "-C", str(resources_dir), ".",
    ], check=True)
    return fixture_jar


def cases() -> list[dict]:
    """返回本轮 F-068/F-069 覆盖的服务端用例。"""
    return [
        {
            "id": "paper1122",
            "label": "Paper 1.12.2",
            "serverJar": legacy.PAPER1122_JAR,
            "java": legacy.JAVA8,
            "copyPaperCache": True,
            "expectedPlatform": "legacy-1.12",
            "port": legacy.find_free_port(),
            "rcon": legacy.find_free_port(),
        },
        {
            "id": "spigot2612",
            "label": "Spigot 26.1.2",
            "serverJar": SPIGOT2612_JAR,
            "java": JAVA25,
            "copyPaperCache": False,
            "expectedPlatform": "paper-1.16-1.20",
            "port": legacy.find_free_port(),
            "rcon": legacy.find_free_port(),
        },
    ]


def prepare_server(case: dict, run_root: Path, fixture_jar: Path) -> Path:
    """准备独立保护边界测试服目录。"""
    server_dir = run_root / case["id"] / "server"
    if server_dir.exists():
        shutil.rmtree(server_dir)
    (server_dir / "plugins").mkdir(parents=True)
    shutil.copy2(case["serverJar"], server_dir / Path(case["serverJar"]).name)
    if case.get("copyPaperCache"):
        legacy.copy_paper_runtime_cache(server_dir)
    shutil.copy2(UNIVERSAL_JAR, server_dir / "plugins" / "BlWorldTrashCan-universal.jar")
    shutil.copy2(fixture_jar, server_dir / "plugins" / fixture_jar.name)
    (server_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (server_dir / "server.properties").write_text(
        legacy.make_server_properties(case["port"], case["rcon"]),
        encoding="utf-8",
    )
    return server_dir


def patch_protections_config(server_dir: Path) -> Path:
    """修改 protections.yml，明确开启本轮保护开关。"""
    protections = server_dir / "plugins" / "BlWorldTrashCan" / "protections.yml"
    if not protections.is_file():
        raise RuntimeError("protections.yml 不存在，无法配置保护边界测试: " + str(protections))
    text = protections.read_text(encoding="utf-8")
    replacements = {
        "remove-unpickable-arrow: false": "remove-unpickable-arrow: true",
        "prevent-farmland-trampling: false": "prevent-farmland-trampling: true",
    }
    for old, new in replacements.items():
        text = text.replace(old, new)
    protections.write_text(text, encoding="utf-8")
    return protections


def run_case(case: dict, run_root: Path, evidence_dir: Path, fixture_jar: Path) -> dict:
    """运行一个保护边界服务端用例。"""
    log("准备保护边界用例 " + case["id"])
    server_dir = prepare_server(case, run_root, fixture_jar)
    case_dir = evidence_dir / case["id"]
    stdout_log = case_dir / "logs" / "server-stdout.log"
    stderr_log = case_dir / "logs" / "server-stderr.log"
    command_log = case_dir / "logs" / "rcon-commands.log"
    stdout_log.parent.mkdir(parents=True, exist_ok=True)
    with stdout_log.open("w", encoding="utf-8", errors="replace") as stdout, stderr_log.open("w", encoding="utf-8", errors="replace") as stderr:
        process = subprocess.Popen(
            [str(case["java"]), "-Xms512M", "-Xmx1024M", "-jar", Path(case["serverJar"]).name, "nogui"],
            cwd=server_dir,
            stdin=subprocess.PIPE,
            stdout=stdout,
            stderr=stderr,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        responses = {}
        entries = []
        result = None
        try:
            legacy.wait_for_rcon(case["rcon"])
            protections_file = patch_protections_config(server_dir)
            run_rcon(case, "plugins", responses, entries, "plugins")
            run_rcon(case, "blwtc platform", responses, entries, "platform")
            run_rcon(case, "blwtc reload", responses, entries, "reload")
            run_rcon(case, "protectionfixture cleanup", responses, entries, "cleanup-before")
            run_rcon(case, "protectionfixture arrow", responses, entries, "arrow")
            run_rcon(case, "protectionfixture farmland", responses, entries, "farmland")
            run_rcon(case, "protectionfixture cleanup", responses, entries, "cleanup-after")
            command_log.write_text("\n\n".join(entries) + "\n", encoding="utf-8")
            time.sleep(1.0)
            result = assert_case(case, responses, protections_file)
            legacy.stop_server(process, case["rcon"])
        finally:
            command_log.write_text("\n\n".join(entries) + "\n", encoding="utf-8")
            legacy.terminate_server(process)
            copy_case_evidence(server_dir, case_dir)
    return result


def run_rcon(case: dict, command: str, responses: dict, entries: list[str], key: str) -> str:
    """执行 RCON 并记录响应。"""
    body = legacy.rcon_command(case["rcon"], command)
    responses[key] = body
    entries.append("> " + command + "\n" + body.rstrip())
    time.sleep(0.35)
    return body


def assert_case(case: dict, responses: dict, protections_file: Path) -> dict:
    """断言单端保护边界结果。"""
    require("BlWorldTrashCan", responses.get("plugins", ""), case["id"] + " 插件列表缺少 BlWorldTrashCan")
    require("BlWtcProtectionFixture", responses.get("plugins", ""), case["id"] + " 插件列表缺少夹具")
    require(case["expectedPlatform"], responses.get("platform", ""), case["id"] + " 平台不符合预期")
    require("universal", responses.get("platform", ""), case["id"] + " 未加载 universal 分支")
    require_all(["AI_PROTECTION_ARROW", "pickupStatusSet=true", "unpickableRemoved=true", "trackedRemoved=true"], responses.get("arrow", ""), case["id"] + " F-068 失败")
    require_all(["AI_PROTECTION_FARMLAND", "entityCancelled=true", "playerCancelled=true"], responses.get("farmland", ""), case["id"] + " F-069 失败")
    protections_text = protections_file.read_text(encoding="utf-8")
    require_all(["remove-unpickable-arrow: true", "prevent-farmland-trampling: true"], protections_text, case["id"] + " protections.yml 未正确开启保护项")
    return {
        "id": case["id"],
        "label": case["label"],
        "passed": True,
        "platform": case["expectedPlatform"],
        "f068": responses.get("arrow", ""),
        "f069": responses.get("farmland", ""),
    }


def require(needle: str, text: str, message: str) -> None:
    """断言文本包含标记。"""
    if needle not in text:
        raise AssertionError(message + ": 缺少 " + needle + "\n" + text)


def require_all(needles: list[str], text: str, message: str) -> None:
    """断言文本包含全部标记。"""
    for needle in needles:
        require(needle, text, message)


def copy_case_evidence(server_dir: Path, case_dir: Path) -> None:
    """复制单个保护边界用例证据。"""
    copy_if_exists(server_dir / "logs" / "latest.log", case_dir / "logs" / "latest.log")
    plugin_dir = server_dir / "plugins" / "BlWorldTrashCan"
    copy_if_exists(plugin_dir / "protections.yml", case_dir / "config" / "protections-after-patch.yml")
    copy_if_exists(plugin_dir / "config.yml", case_dir / "config" / "config.yml")


def copy_if_exists(source: Path, target: Path) -> None:
    """复制可能存在的文件。"""
    if source.is_file():
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)


def write_readme(evidence_dir: Path, summary: dict) -> None:
    """写入 F-068/F-069 证据说明。"""
    lines = [
        "# F-068/F-069 保护边界专项验收",
        "",
        "- 被测插件: `dist/BlWorldTrashCan-universal.jar`",
        "- SHA256: `" + summary["jarSha256"] + "`",
        "- 验收方式: 真实服务端启动 + 临时 Bukkit 夹具触发正式 ProjectileHitEvent、EntityShootBowEvent、EntityInteractEvent、PlayerInteractEvent",
        "- 通过标准: 不可拾取箭矢和追踪箭矢命中后被移除；实体和玩家踩踏农田事件均被取消",
        "- 结论: " + ("PASS" if summary["allPassed"] else "FAIL"),
        "",
    ]
    for result in summary["results"]:
        lines.extend([
            "## " + result["id"],
            "",
            "- 服务端: `" + result["label"] + "`",
            "- 平台: `" + result["platform"] + "`",
            "- RCON 记录: `" + result["id"] + "/logs/rcon-commands.log`",
            "- 服务端日志: `" + result["id"] + "/logs/latest.log`、`" + result["id"] + "/logs/server-stdout.log`",
            "- 配置证据: `" + result["id"] + "/config/protections-after-patch.yml`",
            "",
        ])
    (evidence_dir / "README.md").write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")


def main() -> int:
    """执行 F-068/F-069 保护边界专项矩阵。"""
    ensure_inputs()
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    run_root = BUILD_ROOT / timestamp
    evidence_dir = EVIDENCE_ROOT / ("protection-boundary-" + timestamp)
    run_root.mkdir(parents=True, exist_ok=True)
    evidence_dir.mkdir(parents=True, exist_ok=True)
    fixture_jar = build_fixture(run_root)
    results = []
    summary = {
        "timestamp": timestamp,
        "allPassed": False,
        "jar": UNIVERSAL_JAR,
        "jarSha256": sha256_file(UNIVERSAL_JAR),
        "fixtureJar": fixture_jar,
        "fixtureSha256": sha256_file(fixture_jar),
        "evidenceDir": evidence_dir,
        "results": results,
    }
    try:
        for case in cases():
            results.append(run_case(case, run_root, evidence_dir, fixture_jar))
        summary["allPassed"] = all(result.get("passed") for result in results)
        return 0 if summary["allPassed"] else 1
    finally:
        write_json(evidence_dir / "summary.json", summary)
        write_readme(evidence_dir, summary)
        log("证据目录: " + str(evidence_dir))
        log("allPassed=" + str(summary["allPassed"]))


if __name__ == "__main__":
    raise SystemExit(main())
