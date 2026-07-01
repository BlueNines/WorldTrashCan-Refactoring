import hashlib
import json
import re
import shutil
import subprocess
import time
from pathlib import Path

import run_legacy_migration_matrix as legacy


EVIDENCE_ROOT = legacy.REPO / "docs" / "test-evidence"
BUILD_ROOT = legacy.REPO / "build" / "world-trash-boundary-matrix"
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
import org.bukkit.block.BlockState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** BLWorldTrashCan 世界垃圾桶边界验收夹具。 */
public final class WorldTrashFixturePlugin extends JavaPlugin implements CommandExecutor {
    private static final UUID FAKE_PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-00000000a119");
    private static final int BREAK_OFFSET = 12;
    private static final int BLACKLIST_OFFSET = 28;
    private static final int FAR_X = 200000;
    private static final int FAR_Z = 200000;
    private final List<String> fakePlayerMessages = new ArrayList<String>();
    private UUID lastDropId;

    /** 注册 worldtrashfixture 命令。 */
    @Override
    public void onEnable() {
        getCommand("worldtrashfixture").setExecutor(this);
    }

    /** 执行夹具命令。 */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("AI_WORLD_TRASH_USAGE cleanup|banned|break|prepareblacklist|drop|assertblacklist|prepareunloaded|assertunloaded");
            return true;
        }
        if ("cleanup".equalsIgnoreCase(args[0])) {
            cleanup();
            sender.sendMessage("AI_WORLD_TRASH_CLEANUP done=true");
            return true;
        }
        if ("banned".equalsIgnoreCase(args[0])) {
            checkBannedWorld(sender);
            return true;
        }
        if ("break".equalsIgnoreCase(args[0])) {
            checkBreakRemoval(sender);
            return true;
        }
        if ("prepareblacklist".equalsIgnoreCase(args[0])) {
            prepareBlacklist(sender);
            return true;
        }
        if ("drop".equalsIgnoreCase(args[0])) {
            dropItem(sender, args);
            return true;
        }
        if ("assertblacklist".equalsIgnoreCase(args[0])) {
            assertBlacklist(sender);
            return true;
        }
        if ("prepareunloaded".equalsIgnoreCase(args[0])) {
            prepareUnloaded(sender);
            return true;
        }
        if ("assertunloaded".equalsIgnoreCase(args[0])) {
            assertUnloaded(sender);
            return true;
        }
        sender.sendMessage("AI_WORLD_TRASH_USAGE cleanup|banned|break|prepareblacklist|drop|assertblacklist|prepareunloaded|assertunloaded");
        return true;
    }

    /** 验证禁止世界不会让普通有权限玩家创建世界垃圾桶。 */
    private void checkBannedWorld(CommandSender sender) {
        cleanup();
        World world = mainWorld();
        Block chest = placeChestWithSign(BREAK_OFFSET);
        int before = countLocations(world);
        fireSignChange(chest.getRelative(BlockFace.UP), fakePlayer(world, false, true));
        int after = countLocations(world);
        sender.sendMessage("AI_WORLD_TRASH_BANNED before=" + before
                + " after=" + after
                + " registered=" + (after > before)
                + " messages=" + fakePlayerMessages);
    }

    /** 验证破坏已登记容器会移除世界垃圾桶登记。 */
    private void checkBreakRemoval(CommandSender sender) {
        cleanup();
        World world = mainWorld();
        Block chest = placeChestWithSign(BREAK_OFFSET);
        fireSignChange(chest.getRelative(BlockFace.UP), fakePlayer(world, false, true));
        int afterCreate = countLocations(world);
        Bukkit.getPluginManager().callEvent(new BlockBreakEvent(chest, fakePlayer(world, false, true)));
        int afterBreak = countLocations(world);
        sender.sendMessage("AI_WORLD_TRASH_BREAK afterCreate=" + afterCreate
                + " afterBreak=" + afterBreak
                + " removed=" + (afterCreate > 0 && afterBreak == 0)
                + " messages=" + fakePlayerMessages);
    }

    /** 准备世界黑名单路由降级场景。 */
    private void prepareBlacklist(CommandSender sender) {
        cleanup();
        World world = mainWorld();
        Block chest = placeChest(BLACKLIST_OFFSET);
        writeWorldData(world, chest.getX(), chest.getY(), chest.getZ(), Collections.singletonList("STONE"));
        sender.sendMessage("AI_WORLD_TRASH_BLACKLIST_PREPARED world=" + world.getName()
                + " chest=" + locationText(chest)
                + " banned=STONE"
                + " locations=" + countLocations(world));
    }

    /** 丢出测试物品给正式清理流程处理。 */
    private void dropItem(CommandSender sender, String[] args) {
        World world = mainWorld();
        String materialName = args.length > 1 ? args[1] : "STONE";
        int amount = args.length > 2 ? parseInt(args[2], 1) : 1;
        Material material = material(materialName);
        Item item = world.dropItemNaturally(world.getSpawnLocation().clone().add(2.0, 2.0, 2.0), new ItemStack(material, amount));
        item.setPickupDelay(32767);
        item.setCustomName("AI_WORLD_TRASH_DROP");
        lastDropId = item.getUniqueId();
        sender.sendMessage("AI_WORLD_TRASH_DROP material=" + material.name()
                + " amount=" + amount
                + " uuid=" + lastDropId);
    }

    /** 断言黑名单物品没有进入世界垃圾桶容器。 */
    private void assertBlacklist(CommandSender sender) {
        Block chest = placeChest(BLACKLIST_OFFSET);
        int chestAmount = countInventory(chest);
        boolean dropAlive = isEntityAlive(lastDropId);
        sender.sendMessage("AI_WORLD_TRASH_BLACKLIST_RESULT chestAmount=" + chestAmount
                + " dropAlive=" + dropAlive
                + " expectedGlobalFallback=true");
    }

    /** 准备未加载区块路由降级场景。 */
    private void prepareUnloaded(CommandSender sender) {
        cleanup();
        World world = mainWorld();
        int y = Math.max(5, world.getSpawnLocation().getBlockY());
        int chunkX = FAR_X >> 4;
        int chunkZ = FAR_Z >> 4;
        if (world.isChunkLoaded(chunkX, chunkZ)) {
            world.unloadChunk(chunkX, chunkZ, false);
        }
        boolean beforeLoaded = world.isChunkLoaded(chunkX, chunkZ);
        writeWorldData(world, FAR_X, y, FAR_Z, Collections.<String>emptyList());
        sender.sendMessage("AI_WORLD_TRASH_UNLOADED_PREPARED world=" + world.getName()
                + " location=" + FAR_X + "," + y + "," + FAR_Z
                + " chunk=" + chunkX + "," + chunkZ
                + " chunkLoadedBefore=" + beforeLoaded
                + " locations=" + countLocations(world));
    }

    /** 断言未加载世界垃圾桶位置没有被同步加载。 */
    private void assertUnloaded(CommandSender sender) {
        World world = mainWorld();
        int chunkX = FAR_X >> 4;
        int chunkZ = FAR_Z >> 4;
        boolean chunkLoadedAfter = world.isChunkLoaded(chunkX, chunkZ);
        boolean dropAlive = isEntityAlive(lastDropId);
        sender.sendMessage("AI_WORLD_TRASH_UNLOADED_RESULT chunkLoadedAfter=" + chunkLoadedAfter
                + " dropAlive=" + dropAlive
                + " expectedGlobalFallback=true");
    }

    /** 清理夹具产生的方块、实体和世界垃圾桶数据。 */
    private void cleanup() {
        World world = mainWorld();
        clearDataFile();
        clearFixtureEntities();
        clearArea(world.getSpawnLocation().clone().add(BREAK_OFFSET, 0.0, 0.0));
        clearArea(world.getSpawnLocation().clone().add(BLACKLIST_OFFSET, 0.0, 0.0));
        fakePlayerMessages.clear();
        lastDropId = null;
    }

    /** 触发正式 SignChangeEvent。 */
    private void fireSignChange(Block signBlock, Player player) {
        fakePlayerMessages.clear();
        Bukkit.getPluginManager().callEvent(new SignChangeEvent(signBlock, player, new String[] {"[世界垃圾桶]", "", "", ""}));
    }

    /** 放置一个箱子和箱子上方的告示牌。 */
    private Block placeChestWithSign(int offset) {
        Block chest = placeChest(offset);
        Block sign = chest.getRelative(BlockFace.UP);
        sign.setType(material("SIGN_POST", "OAK_SIGN", "SIGN"));
        return chest;
    }

    /** 放置一个箱子。 */
    private Block placeChest(int offset) {
        World world = mainWorld();
        Location base = world.getSpawnLocation().clone().add(offset, 0.0, 0.0);
        clearArea(base);
        Block chest = base.getBlock();
        chest.setType(material("CHEST"));
        chest.getState().update(true, false);
        return chest;
    }

    /** 清理测试区域中的方块。 */
    private void clearArea(Location base) {
        if (base == null || base.getWorld() == null) {
            return;
        }
        Chunk chunk = base.getChunk();
        chunk.load(true);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    base.clone().add(dx, dy, dz).getBlock().setType(Material.AIR);
                }
            }
        }
    }

    /** 写入世界垃圾桶数据文件。 */
    private void writeWorldData(World world, int x, int y, int z, List<String> bannedMaterials) {
        File file = dataFile();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String path = "worlds." + world.getName() + ".";
        yaml.set(path + "max-count", 3);
        yaml.set(path + "locations", Collections.singletonList(x + "," + y + "," + z));
        yaml.set(path + "banned-materials", bannedMaterials);
        saveYaml(yaml, file);
    }

    /** 清空世界垃圾桶数据文件。 */
    private void clearDataFile() {
        File file = dataFile();
        YamlConfiguration yaml = new YamlConfiguration();
        saveYaml(yaml, file);
    }

    /** 返回当前世界登记位置数量。 */
    private int countLocations(World world) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile());
        return yaml.getStringList("worlds." + world.getName() + ".locations").size();
    }

    /** 保存 YAML 文件。 */
    private void saveYaml(YamlConfiguration yaml, File file) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try {
            yaml.save(file);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot save " + file.getAbsolutePath(), exception);
        }
    }

    /** 返回 BLWorldTrashCan 世界数据文件。 */
    private File dataFile() {
        return new File(getServer().getPluginManager().getPlugin("BLWorldTrashCan").getDataFolder(), "data/worlds.yml");
    }

    /** 统计容器内物品总量。 */
    private int countInventory(Block block) {
        BlockState state = block.getState();
        if (!(state instanceof InventoryHolder)) {
            return -1;
        }
        Inventory inventory = ((InventoryHolder) state).getInventory();
        int amount = 0;
        for (ItemStack itemStack : inventory.getContents()) {
            if (itemStack != null && itemStack.getType() != Material.AIR) {
                amount += itemStack.getAmount();
            }
        }
        return amount;
    }

    /** 清理夹具掉落物。 */
    private void clearFixtureEntities() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                String customName = entity.getCustomName();
                if (entity instanceof Item || entity.getUniqueId().equals(lastDropId)
                        || (customName != null && customName.startsWith("AI_WORLD_TRASH_"))) {
                    entity.remove();
                }
            }
        }
    }

    /** 判断实体是否仍存在。 */
    private boolean isEntityAlive(UUID uniqueId) {
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

    /** 创建只覆盖本测试所需方法的临时 Player 代理。 */
    private Player fakePlayer(final World world, final boolean op, final boolean permission) {
        InvocationHandler handler = new InvocationHandler() {
            /** 处理 Player 代理方法。 */
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if ("getName".equals(name)) {
                    return "AIWorldFixturePlayer";
                }
                if ("getUniqueId".equals(name)) {
                    return FAKE_PLAYER_ID;
                }
                if ("isOp".equals(name)) {
                    return Boolean.valueOf(op);
                }
                if ("hasPermission".equals(name)) {
                    return Boolean.valueOf(permission);
                }
                if ("sendMessage".equals(name)) {
                    rememberMessage(args);
                    return null;
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
                    return "AIWorldFixturePlayer";
                }
                return defaultValue(method.getReturnType());
            }
        };
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class[] { Player.class }, handler);
    }

    /** 记录 Player 代理收到的消息。 */
    private void rememberMessage(Object[] args) {
        if (args == null || args.length == 0 || args[0] == null) {
            return;
        }
        if (args[0] instanceof String[]) {
            fakePlayerMessages.addAll(Arrays.asList((String[]) args[0]));
            return;
        }
        fakePlayerMessages.add(String.valueOf(args[0]));
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

    /** 返回主世界。 */
    private World mainWorld() {
        return Bukkit.getWorlds().get(0);
    }

    /** 解析整数。 */
    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /** 兼容不同版本 Material 名称。 */
    private Material material(String... names) {
        for (String name : names) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                return material;
            }
        }
        return Material.STONE;
    }

    /** 返回方块位置文本。 */
    private String locationText(Block block) {
        return block.getWorld().getName() + ":" + block.getX() + "," + block.getY() + "," + block.getZ();
    }
}
'''


PLUGIN_YML = """name: BLWtcWorldTrashFixture
version: 1.0.0
main: ai.blwtc.fixture.WorldTrashFixturePlugin
commands:
  worldtrashfixture:
    description: BLWorldTrashCan world trash boundary fixture
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
    """确认世界垃圾桶边界验证所需运行环境存在。"""
    missing = []
    for path in (UNIVERSAL_JAR, legacy.PAPER1122_JAR, SPIGOT2612_JAR, legacy.JAVA8, JAVA25, JAVAC17, JAR17, BUKKIT_API_JAR):
        if not path.is_file():
            missing.append(str(path))
    if missing:
        raise RuntimeError("缺少世界垃圾桶边界验证输入: " + "; ".join(missing))


def build_fixture(run_root: Path) -> Path:
    """编译临时 Bukkit 测试插件。"""
    source_dir = run_root / "fixture-src" / "ai" / "blwtc" / "fixture"
    classes_dir = run_root / "fixture-classes"
    resources_dir = run_root / "fixture-resources"
    fixture_jar = run_root / "BLWtcWorldTrashFixture.jar"
    if classes_dir.exists():
        shutil.rmtree(classes_dir)
    if resources_dir.exists():
        shutil.rmtree(resources_dir)
    source_dir.mkdir(parents=True, exist_ok=True)
    resources_dir.mkdir(parents=True, exist_ok=True)
    (source_dir / "WorldTrashFixturePlugin.java").write_text(FIXTURE_SOURCE, encoding="utf-8")
    (resources_dir / "plugin.yml").write_text(PLUGIN_YML, encoding="utf-8")
    subprocess.run([
        str(JAVAC17),
        "--release", "8",
        "-encoding", "UTF-8",
        "-cp", str(BUKKIT_API_JAR),
        "-d", str(classes_dir),
        str(source_dir / "WorldTrashFixturePlugin.java"),
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
    """返回本轮覆盖的服务端用例。"""
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
    """准备独立测试服目录。"""
    server_dir = run_root / case["id"] / "server"
    if server_dir.exists():
        shutil.rmtree(server_dir)
    (server_dir / "plugins").mkdir(parents=True)
    shutil.copy2(case["serverJar"], server_dir / Path(case["serverJar"]).name)
    if case.get("copyPaperCache"):
        legacy.copy_paper_runtime_cache(server_dir)
    shutil.copy2(UNIVERSAL_JAR, server_dir / "plugins" / "BLWorldTrashCan-universal.jar")
    shutil.copy2(fixture_jar, server_dir / "plugins" / fixture_jar.name)
    (server_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (server_dir / "server.properties").write_text(
        legacy.make_server_properties(case["port"], case["rcon"]),
        encoding="utf-8",
    )
    return server_dir


def patch_cleanup_config(server_dir: Path) -> Path:
    """修改 cleanup.yml，使手动清理无门禁且不自动后台扫地。"""
    cleanup = server_dir / "plugins" / "BLWorldTrashCan" / "cleanup.yml"
    text = cleanup.read_text(encoding="utf-8")
    replacements = {
        "interval-seconds: 360": "interval-seconds: 0",
        "min-online-players: 1": "min-online-players: 0",
        "min-total-entities: 150": "min-total-entities: 0",
    }
    for old, new in replacements.items():
        text = text.replace(old, new)
    cleanup.write_text(text, encoding="utf-8")
    return cleanup


def patch_trash_config(server_dir: Path, banned_world: bool) -> Path:
    """修改 trash.yml，使测试场景路由明确。"""
    trash = server_dir / "plugins" / "BLWorldTrashCan" / "trash.yml"
    text = trash.read_text(encoding="utf-8")
    text = text.replace("clear-every-cleanups: 3", "clear-every-cleanups: 0")
    text = replace_banned_worlds(text, ["world"] if banned_world else [])
    trash.write_text(text, encoding="utf-8")
    return trash


def replace_banned_worlds(text: str, worlds: list[str]) -> str:
    """只替换 banned-worlds 配置块，不吞掉后续配置。"""
    if worlds:
        replacement = "  banned-worlds:\n" + "".join('    - "' + world + '"\n' for world in worlds)
    else:
        replacement = "  banned-worlds: []\n"
    pattern = re.compile(r"(?m)^  banned-worlds:(?:\s*\[\])?\n?(?:^    - .+\n)*")
    if pattern.search(text):
        return pattern.sub(replacement, text, count=1)
    marker = "  allow-load-unloaded-chunks: false\n"
    if marker in text:
        return text.replace(marker, marker + replacement, 1)
    return text.rstrip() + "\n" + replacement


def run_case(case: dict, run_root: Path, evidence_dir: Path, fixture_jar: Path) -> dict:
    """运行一个世界垃圾桶边界用例。"""
    log("准备世界垃圾桶边界用例 " + case["id"])
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
            cleanup_file = patch_cleanup_config(server_dir)
            trash_file = patch_trash_config(server_dir, banned_world=True)
            run_rcon(case, "plugins", responses, entries, "plugins")
            run_rcon(case, "blwtc platform", responses, entries, "platform")
            run_rcon(case, "blwtc reload", responses, entries, "reload-banned")
            run_rcon(case, "worldtrashfixture cleanup", responses, entries, "fixture-cleanup-1")
            run_rcon(case, "worldtrashfixture banned", responses, entries, "banned")
            trash_file = patch_trash_config(server_dir, banned_world=False)
            run_rcon(case, "blwtc reload", responses, entries, "reload-unbanned")
            run_rcon(case, "worldtrashfixture break", responses, entries, "break")
            run_rcon(case, "worldtrashfixture prepareblacklist", responses, entries, "prepare-blacklist")
            run_rcon(case, "blwtc reload", responses, entries, "reload-blacklist")
            run_rcon(case, "blwtc stats", responses, entries, "stats-before-blacklist")
            run_rcon(case, "worldtrashfixture drop STONE 5", responses, entries, "drop-blacklist")
            run_rcon(case, "blwtc clear true", responses, entries, "clear-blacklist")
            run_rcon(case, "worldtrashfixture assertblacklist", responses, entries, "assert-blacklist")
            run_rcon(case, "blwtc stats", responses, entries, "stats-blacklist")
            run_rcon(case, "worldtrashfixture prepareunloaded", responses, entries, "prepare-unloaded")
            run_rcon(case, "blwtc reload", responses, entries, "reload-unloaded")
            run_rcon(case, "blwtc stats", responses, entries, "stats-before-unloaded")
            run_rcon(case, "worldtrashfixture drop STONE 7", responses, entries, "drop-unloaded")
            run_rcon(case, "blwtc clear true", responses, entries, "clear-unloaded")
            run_rcon(case, "worldtrashfixture assertunloaded", responses, entries, "assert-unloaded")
            run_rcon(case, "blwtc stats", responses, entries, "stats-unloaded")
            run_rcon(case, "worldtrashfixture cleanup", responses, entries, "fixture-cleanup-2")
            command_log.write_text("\n\n".join(entries) + "\n", encoding="utf-8")
            time.sleep(1.0)
            result = assert_case(case, responses, cleanup_file, trash_file, stdout_log)
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


def assert_case(case: dict, responses: dict, cleanup_file: Path, trash_file: Path, stdout_log: Path) -> dict:
    """断言单端世界垃圾桶边界结果。"""
    require("BLWorldTrashCan", responses.get("plugins", ""), case["id"] + " 插件列表缺少 BLWorldTrashCan")
    require("BLWtcWorldTrashFixture", responses.get("plugins", ""), case["id"] + " 插件列表缺少夹具")
    require(case["expectedPlatform"], responses.get("platform", ""), case["id"] + " 平台不符合预期")
    require("universal", responses.get("platform", ""), case["id"] + " 未加载 universal 分支")
    require_all(["AI_WORLD_TRASH_BANNED", "registered=false"], responses.get("banned", ""), case["id"] + " F-019 失败")
    require_all(["AI_WORLD_TRASH_BREAK", "removed=true", "afterBreak=0"], responses.get("break", ""), case["id"] + " F-020 失败")
    require_all(["AI_WORLD_TRASH_BLACKLIST_RESULT", "chestAmount=0", "dropAlive=false"], responses.get("assert-blacklist", ""), case["id"] + " F-021 夹具失败")
    require_global_delta(responses.get("stats-before-blacklist", ""), responses.get("stats-blacklist", ""), 5, case["id"] + " F-021 stats 未证明公共降级")
    require_all(["AI_WORLD_TRASH_UNLOADED_RESULT", "chunkLoadedAfter=false", "dropAlive=false"], responses.get("assert-unloaded", ""), case["id"] + " F-022 夹具失败")
    require_global_delta(responses.get("stats-before-unloaded", ""), responses.get("stats-unloaded", ""), 7, case["id"] + " F-022 stats 未证明公共降级")
    server_text = stdout_log.read_text(encoding="utf-8", errors="replace")
    require("worldTrashSkippedUnloadedChunks=", server_text, case["id"] + " F-022 缺少未加载跳过日志")
    cleanup_text = cleanup_file.read_text(encoding="utf-8")
    trash_text = trash_file.read_text(encoding="utf-8")
    require_all(["interval-seconds: 0", "min-online-players: 0", "min-total-entities: 0"], cleanup_text, case["id"] + " cleanup.yml 未正确补丁")
    require_all(["clear-every-cleanups: 0", "banned-worlds: []"], trash_text, case["id"] + " trash.yml 未正确补丁")
    return {
        "id": case["id"],
        "label": case["label"],
        "passed": True,
        "platform": case["expectedPlatform"],
        "f019": responses.get("banned", ""),
        "f020": responses.get("break", ""),
        "f021": {
            "fixture": responses.get("assert-blacklist", ""),
            "stats": responses.get("stats-blacklist", ""),
        },
        "f022": {
            "fixture": responses.get("assert-unloaded", ""),
            "stats": responses.get("stats-unloaded", ""),
            "serverLogContainsSkippedUnloaded": True,
        },
    }


def require_global_delta(before: str, after: str, amount: int, message: str) -> None:
    """断言 stats 输出证明公共垃圾桶物品数量按预期增加。"""
    before_amount = parse_global_amount(before)
    after_amount = parse_global_amount(after)
    if after_amount != amount and after_amount - before_amount != amount:
        raise AssertionError(message + ": before=" + str(before_amount) + ", after=" + str(after_amount) + "\n" + after)


def parse_global_amount(text: str) -> int:
    """从 stats 输出中解析公共垃圾桶当前物品数量。"""
    plain = re.sub(r"§.", "", text)
    match = re.search(r"公共垃圾桶当前物品:\s*(\d+)", plain)
    return int(match.group(1)) if match else -1


def require(needle: str, text: str, message: str) -> None:
    """断言文本包含标记。"""
    if needle not in text:
        raise AssertionError(message + ": 缺少 " + needle + "\n" + text)


def require_all(needles: list[str], text: str, message: str) -> None:
    """断言文本包含全部标记。"""
    for needle in needles:
        require(needle, text, message)


def copy_case_evidence(server_dir: Path, case_dir: Path) -> None:
    """复制单端证据。"""
    copy_if_exists(server_dir / "logs" / "latest.log", case_dir / "logs" / "latest.log")
    plugin_dir = server_dir / "plugins" / "BLWorldTrashCan"
    copy_if_exists(plugin_dir / "cleanup.yml", case_dir / "config" / "cleanup-after-patch.yml")
    copy_if_exists(plugin_dir / "trash.yml", case_dir / "config" / "trash-after-patch.yml")
    copy_if_exists(plugin_dir / "data" / "worlds.yml", case_dir / "data" / "worlds.yml")


def copy_if_exists(source: Path, target: Path) -> None:
    """复制可能存在的文件。"""
    if source.is_file():
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)


def write_readme(evidence_dir: Path, summary: dict) -> None:
    """写入世界垃圾桶边界证据说明。"""
    lines = [
        "# F-019 至 F-022 世界垃圾桶边界专项验收",
        "",
        "- 被测插件: `dist/BLWorldTrashCan-universal.jar`",
        "- SHA256: `" + summary["jarSha256"] + "`",
        "- 验收方式: 真实服务端启动 + 临时 Bukkit 夹具触发正式事件/RCON 正式清理",
        "- 覆盖功能: F-019 禁止世界普通玩家创建、F-020 破坏登记移除、F-021 世界物品黑名单降级、F-022 未加载区块降级",
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
            "- 配置证据: `" + result["id"] + "/config/cleanup-after-patch.yml`、`" + result["id"] + "/config/trash-after-patch.yml`",
            "- 数据证据: `" + result["id"] + "/data/worlds.yml`",
            "",
        ])
    (evidence_dir / "README.md").write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")


def main() -> int:
    """执行 F-019 至 F-022 世界垃圾桶边界专项矩阵。"""
    ensure_inputs()
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    run_root = BUILD_ROOT / timestamp
    evidence_dir = EVIDENCE_ROOT / ("world-trash-boundary-" + timestamp)
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
