import hashlib
import json
import shutil
import subprocess
import time
from pathlib import Path

import run_legacy_migration_matrix as legacy


BUILD_ROOT = legacy.REPO / "build" / "cleanup-console-detail-matrix"
UNIVERSAL_JAR = legacy.UNIVERSAL_JAR
JAVA25 = legacy.REPO / "build" / "tools" / "jre-25.0.3+9" / "bin" / "java.exe"
JAVAC17 = legacy.REPO / "build" / "tools" / "jdk-17.0.19+10" / "bin" / "javac.exe"
JAR17 = legacy.REPO / "build" / "tools" / "jdk-17.0.19+10" / "bin" / "jar.exe"
BUKKIT_API_JAR = Path.home() / ".m2" / "repository" / "org" / "spigotmc" / "spigot-api" / "1.12.2-R0.1-SNAPSHOT" / "spigot-api-1.12.2-R0.1-SNAPSHOT.jar"
LUMINOL2612_JAR = Path(r"C:\Users\pc\Desktop\ai开发插件\luminol-26.1.2-test-server\luminol-26.1.2-paperclip.jar")
LUMINOL2612_RUNTIME = LUMINOL2612_JAR.parent


FIXTURE_SOURCE = r'''
package ai.blwtc.fixture;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** BLWorldTrashCan 控制台清理明细验收夹具。 */
public final class CleanupConsoleFixturePlugin extends JavaPlugin implements CommandExecutor {
    private final Set<UUID> tracked = new HashSet<>();
    private World world;
    private Location base;

    /** 注册 cleanupconsolefixture 命令。 */
    @Override
    public void onEnable() {
        getCommand("cleanupconsolefixture").setExecutor(this);
    }

    /** 执行准备、状态或清理命令。 */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("AI_CLEANUP_DETAIL_USAGE prepare|status|cleanup");
            return true;
        }
        final String action = args[0].toLowerCase();
        runInSpawnRegion(new Runnable() {
            /** 在测试区域执行夹具动作。 */
            @Override
            public void run() {
                if ("prepare".equals(action)) {
                    prepare();
                } else if ("status".equals(action)) {
                    reportStatus();
                } else if ("cleanup".equals(action)) {
                    cleanup();
                    getLogger().info("AI_CLEANUP_DETAIL_FIXTURE_CLEANUP done=true");
                }
            }
        });
        sender.sendMessage("AI_CLEANUP_DETAIL_SCHEDULED action=" + action);
        return true;
    }

    /** 在主线程或 Folia spawn region 执行动作。 */
    private void runInSpawnRegion(Runnable action) {
        world = Bukkit.getWorlds().get(0);
        base = world.getSpawnLocation().clone().add(4.5, 3.0, 4.5);
        if (!isRegionThreaded()) {
            action.run();
            return;
        }
        try {
            Object scheduler = Bukkit.class.getMethod("getRegionScheduler").invoke(null);
            Method execute = scheduler.getClass().getMethod("execute", Plugin.class, World.class,
                    int.class, int.class, Runnable.class);
            execute.invoke(scheduler, this, world, base.getBlockX() >> 4, base.getBlockZ() >> 4, action);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("无法分派 Folia 测试区域任务", exception);
        }
    }

    /** 判断当前服务端是否提供 Folia region scheduler。 */
    private boolean isRegionThreaded() {
        try {
            Class.forName("io.papermc.paper.threadedregions.scheduler.FoliaRegionScheduler", false,
                    getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    /** 生成命名实体、普通实体和 97 个实际物品。 */
    private void prepare() {
        cleanup();
        keepChunkLoaded(true);
        clearSpawnChunk();
        for (int index = 0; index < 3; index++) {
            spawn(EntityType.ARMOR_STAND, base.clone().add(index, 0.0, 0.0), "§c神话最强怪");
        }
        for (int index = 0; index < 2; index++) {
            spawn(EntityType.ARMOR_STAND, base.clone().add(index + 3, 0.0, 0.0), "§6神话最强怪");
        }
        for (int index = 0; index < 4; index++) {
            spawn(EntityType.SHEEP, base.clone().add(index, 0.0, 2.0), null);
        }
        spawn(EntityType.PIG, base.clone().add(5.0, 0.0, 2.0), null);
        drop(Material.STONE, 64, base.clone().add(0.0, 1.0, 4.0));
        drop(Material.DIRT, 33, base.clone().add(3.0, 1.0, 4.0));
        getLogger().info("AI_CLEANUP_DETAIL_PREPARED entities=10, items=97, tracked=" + tracked.size());
    }

    /** 生成一个测试实体并记录 UUID。 */
    private void spawn(EntityType type, Location location, String customName) {
        Entity entity = world.spawnEntity(location, type);
        if (customName != null) {
            entity.setCustomName(customName);
        }
        tracked.add(entity.getUniqueId());
    }

    /** 生成指定实际数量的掉落物并记录 UUID。 */
    private void drop(Material material, int amount, Location location) {
        Item item = world.dropItem(location, new ItemStack(material, amount));
        item.setPickupDelay(Integer.MAX_VALUE);
        tracked.add(item.getUniqueId());
    }

    /** 输出仍存在的测试实体数量。 */
    private void reportStatus() {
        int alive = 0;
        for (Entity entity : world.getChunkAt(base).getEntities()) {
            if (tracked.contains(entity.getUniqueId()) && !entity.isDead()) {
                alive++;
            }
        }
        getLogger().info("AI_CLEANUP_DETAIL_STATUS alive=" + alive + ", tracked=" + tracked.size());
    }

    /** 清理夹具仍残留在测试区域的实体。 */
    private void cleanup() {
        if (world != null && base != null) {
            for (Entity entity : world.getChunkAt(base).getEntities()) {
                if (tracked.contains(entity.getUniqueId())) {
                    entity.remove();
                }
            }
        }
        keepChunkLoaded(false);
        tracked.clear();
    }

    /** 在测试期间保持 spawn chunk 已加载，结束后解除。 */
    private void keepChunkLoaded(boolean forceLoaded) {
        if (world == null || base == null) {
            return;
        }
        Chunk chunk = world.getChunkAt(base);
        chunk.load(true);
        try {
            Method method = chunk.getClass().getMethod("setForceLoaded", boolean.class);
            method.invoke(chunk, Boolean.valueOf(forceLoaded));
        } catch (ReflectiveOperationException ignored) {
            // 1.12.2 没有 force-loaded API，当前主线程已加载 chunk 足够完成即时测试。
        }
    }

    /** 清除隔离测试 chunk 中自然生成的非玩家实体。 */
    private void clearSpawnChunk() {
        for (Entity entity : world.getChunkAt(base).getEntities()) {
            if (!(entity instanceof Player)) {
                entity.remove();
            }
        }
    }
}
'''


PLUGIN_YML = """name: BLWtcCleanupConsoleFixture
version: 1.0.0
main: ai.blwtc.fixture.CleanupConsoleFixturePlugin
folia-supported: true
commands:
  cleanupconsolefixture:
    description: BLWorldTrashCan cleanup console detail fixture
"""


def log(message: str) -> None:
    """输出带时间戳的脚本日志。"""
    print(time.strftime("[%H:%M:%S] ") + message, flush=True)


def sha256_file(path: Path) -> str:
    """计算文件 SHA256。"""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def ensure_inputs() -> None:
    """确认真实服务端矩阵所需输入存在。"""
    required = (UNIVERSAL_JAR, legacy.PAPER1122_JAR, LUMINOL2612_JAR,
                legacy.JAVA8, JAVA25, JAVAC17, JAR17, BUKKIT_API_JAR)
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise RuntimeError("缺少控制台明细验收输入: " + "; ".join(missing))


def build_fixture(run_root: Path) -> Path:
    """编译跨 1.12.2 与 Folia 的临时 Bukkit 夹具。"""
    source_dir = run_root / "fixture-src" / "ai" / "blwtc" / "fixture"
    classes_dir = run_root / "fixture-classes"
    resources_dir = run_root / "fixture-resources"
    fixture_jar = run_root / "BLWtcCleanupConsoleFixture.jar"
    source_dir.mkdir(parents=True, exist_ok=True)
    classes_dir.mkdir(parents=True, exist_ok=True)
    resources_dir.mkdir(parents=True, exist_ok=True)
    (source_dir / "CleanupConsoleFixturePlugin.java").write_text(FIXTURE_SOURCE, encoding="utf-8")
    (resources_dir / "plugin.yml").write_text(PLUGIN_YML, encoding="utf-8")
    subprocess.run([
        str(JAVAC17), "--release", "8", "-encoding", "UTF-8",
        "-cp", str(BUKKIT_API_JAR), "-d", str(classes_dir),
        str(source_dir / "CleanupConsoleFixturePlugin.java"),
    ], check=True)
    subprocess.run([
        str(JAR17), "cf", str(fixture_jar),
        "-C", str(classes_dir), ".", "-C", str(resources_dir), ".",
    ], check=True)
    return fixture_jar


def cases() -> list[dict]:
    """返回低版本和 Folia/Luminol 真实服务端用例。"""
    return [
        {
            "id": "paper1122",
            "label": "Paper 1.12.2",
            "serverJar": legacy.PAPER1122_JAR,
            "java": legacy.JAVA8,
            "copyPaperCache": True,
            "expectedPlatform": "legacy-1.12",
            "javaArgs": [],
        },
        {
            "id": "luminol2612",
            "label": "Luminol 26.1.2",
            "serverJar": LUMINOL2612_JAR,
            "java": JAVA25,
            "copyPaperCache": False,
            "copyLuminolRuntime": True,
            "expectedPlatform": "folia-1.20",
            "javaArgs": [
                "--add-opens", "java.base/java.net=ALL-UNNAMED",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:-UseAESCTRIntrinsics",
            ],
        },
    ]


def prepare_server(case: dict, run_root: Path, fixture_jar: Path) -> Path:
    """准备不会触碰用户现有世界和日志的隔离测试服。"""
    server_dir = run_root / case["id"] / "server"
    (server_dir / "plugins").mkdir(parents=True, exist_ok=True)
    shutil.copy2(case["serverJar"], server_dir / Path(case["serverJar"]).name)
    if case.get("copyPaperCache"):
        legacy.copy_paper_runtime_cache(server_dir)
    if case.get("copyLuminolRuntime"):
        for directory_name in ("cache", "libraries", "versions"):
            source = LUMINOL2612_RUNTIME / directory_name
            if source.is_dir():
                shutil.copytree(source, server_dir / directory_name, dirs_exist_ok=True)
    shutil.copy2(UNIVERSAL_JAR, server_dir / "plugins" / "BLWorldTrashCan-universal.jar")
    shutil.copy2(fixture_jar, server_dir / "plugins" / fixture_jar.name)
    (server_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    port = legacy.find_free_port()
    rcon_port = legacy.find_free_port()
    case["port"] = port
    case["rcon"] = rcon_port
    properties = legacy.make_server_properties(port, rcon_port)
    properties += "spawn-animals=false\nspawn-monsters=false\ngenerate-structures=false\nlevel-type=FLAT\n"
    (server_dir / "server.properties").write_text(properties, encoding="utf-8")
    return server_dir


def wait_for_rcon(port: int, timeout_seconds: int = 360) -> None:
    """等待服务端 RCON 就绪，允许 Luminol 首次展开运行库。"""
    deadline = time.time() + timeout_seconds
    last_error = None
    while time.time() < deadline:
        try:
            legacy.rcon_command(port, "list")
            return
        except Exception as error:
            last_error = error
            time.sleep(1.0)
    raise RuntimeError("等待 RCON 超时: " + repr(last_error))


def patch_cleanup_config(server_dir: Path) -> str:
    """配置可重复、无门禁且只显示前两组的清理场景。"""
    cleanup = server_dir / "plugins" / "BLWorldTrashCan" / "cleanup.yml"
    if not cleanup.is_file():
        raise RuntimeError("cleanup.yml 不存在: " + str(cleanup))
    text = cleanup.read_text(encoding="utf-8")
    replacements = {
        "interval-seconds: 360": "interval-seconds: 0",
        "  min-online-players: 1": "  min-online-players: 0",
        "  min-total-entities: 150": "  min-total-entities: 0",
        "  clear-animals: false": "  clear-animals: true",
        "  clear-named-entities: false": "  clear-named-entities: true",
        "  chat:\n    # 是否启用聊天提醒。\n    enabled: true":
            "  chat:\n    # 是否启用聊天提醒。\n    enabled: false",
        "    max-entries: 10": "    max-entries: 2",
    }
    for old, new in replacements.items():
        text = text.replace(old, new)
    text = text.replace(
        '    - "FLAMMPFEIL.SLASHBLADE_BLADESTAND"',
        '    - "FLAMMPFEIL.SLASHBLADE_BLADESTAND"\n    - "ARMOR_STAND"',
    )
    cleanup.write_text(text, encoding="utf-8")
    return text


def wait_for_text(path: Path, marker: str, timeout_seconds: int = 120) -> str:
    """等待日志出现指定标记并返回当前文本。"""
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        if path.is_file():
            text = path.read_text(encoding="utf-8", errors="replace")
            if marker in text:
                return text
        time.sleep(0.25)
    raise RuntimeError("等待日志标记超时: " + marker)


def run_case(case: dict, run_root: Path, fixture_jar: Path) -> dict:
    """运行一个真实服务端控制台明细用例。"""
    server_dir = prepare_server(case, run_root, fixture_jar)
    case_root = run_root / case["id"]
    stdout_log = case_root / "server-stdout.log"
    stderr_log = case_root / "server-stderr.log"
    command_log = case_root / "rcon-commands.log"
    command_entries = []
    launch = [str(case["java"]), "-Dfile.encoding=UTF-8", "-Xms512M", "-Xmx1536M"] + case["javaArgs"] + [
        "-jar", Path(case["serverJar"]).name, "nogui",
    ]
    log("启动 " + case["label"])
    with stdout_log.open("w", encoding="utf-8", errors="replace") as stdout, \
            stderr_log.open("w", encoding="utf-8", errors="replace") as stderr:
        process = subprocess.Popen(launch, cwd=server_dir, stdout=stdout, stderr=stderr)
        try:
            wait_for_rcon(case["rcon"])
            cleanup_text = patch_cleanup_config(server_dir)
            for command in ("plugins", "blwtc platform", "blwtc reload"):
                response = legacy.rcon_command(case["rcon"], command)
                command_entries.append("> " + command + "\n" + response.rstrip())
            legacy.rcon_command(case["rcon"], "cleanupconsolefixture prepare")
            wait_for_text(stdout_log, "AI_CLEANUP_DETAIL_PREPARED")
            clear_response = legacy.rcon_command(case["rcon"], "blwtc clear true")
            command_entries.append("> blwtc clear true\n" + clear_response.rstrip())
            log_text = wait_for_text(stdout_log, "[CleanupDetail] items:")
            legacy.rcon_command(case["rcon"], "cleanupconsolefixture status")
            log_text = wait_for_text(stdout_log, "AI_CLEANUP_DETAIL_STATUS alive=0")
            result = assert_case(case, cleanup_text, log_text)
            legacy.stop_server(process, case["rcon"])
        finally:
            legacy.terminate_server(process)
    command_log.write_text("\n\n".join(command_entries) + "\n", encoding="utf-8")
    return result


def assert_case(case: dict, cleanup_text: str, log_text: str) -> dict:
    """断言真实服务端输出符合 F-089 语义。"""
    required_config = (
        "enabled: false", "details-enabled: true", "max-entries: 2",
        'entity-format: "{name}_{type}: {count}"',
    )
    for marker in required_config:
        if marker not in cleanup_text:
            raise AssertionError(case["id"] + " cleanup.yml 缺少 " + marker)
    required_log = (
        "Universal runtime: " + ("folia" if case["expectedPlatform"].startswith("folia") else "legacy"),
        "Platform: " + case["expectedPlatform"],
        "AI_CLEANUP_DETAIL_PREPARED entities=10, items=97, tracked=12",
        "[CleanupDetail] entities=10, items=97, groups=3, shown=2, partial=false",
        "[CleanupDetail] 神话最强怪_armor_stand: 5",
        "_sheep: 4",
        "[CleanupDetail] others: 1",
        "[CleanupDetail] items: 97",
        "AI_CLEANUP_DETAIL_STATUS alive=0",
    )
    for marker in required_log:
        if marker not in log_text:
            raise AssertionError(case["id"] + " 日志缺少 " + marker)
    return {
        "id": case["id"],
        "label": case["label"],
        "platform": case["expectedPlatform"],
        "passed": True,
    }


def main() -> int:
    """执行 F-089 低版本与 Folia/Luminol 后台矩阵。"""
    ensure_inputs()
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    run_root = BUILD_ROOT / timestamp
    run_root.mkdir(parents=True, exist_ok=True)
    fixture_jar = build_fixture(run_root)
    results = [run_case(case, run_root, fixture_jar) for case in cases()]
    summary = {
        "timestamp": timestamp,
        "allPassed": all(result["passed"] for result in results),
        "jar": str(UNIVERSAL_JAR),
        "jarSha256": sha256_file(UNIVERSAL_JAR),
        "fixtureSha256": sha256_file(fixture_jar),
        "results": results,
    }
    (run_root / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    log("F-089 控制台清理明细矩阵完成: " + str(run_root))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
