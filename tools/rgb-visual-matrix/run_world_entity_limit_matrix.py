import hashlib
import json
import re
import shutil
import subprocess
import time
from pathlib import Path

import run_legacy_migration_matrix as legacy


EVIDENCE_ROOT = legacy.REPO / "docs" / "test-evidence"
BUILD_ROOT = legacy.REPO / "build" / "world-entity-limit-matrix"
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
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.UUID;

/** BlWorldTrashCan 世界实体上限验收夹具。 */
public final class WorldEntityLimitFixturePlugin extends JavaPlugin implements CommandExecutor {
    private static final String PREFIX = "AI_WORLD_ENTITY_LIMIT_";
    private static final int BASE_OFFSET = 40;

    /** 注册 worldentitylimitfixture 命令。 */
    @Override
    public void onEnable() {
        getCommand("worldentitylimitfixture").setExecutor(this);
    }

    /** 执行夹具命令。 */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("AI_WORLD_ENTITY_LIMIT_USAGE cleanup|seed|spawncheck|count");
            return true;
        }
        if ("cleanup".equalsIgnoreCase(args[0])) {
            cleanup();
            sender.sendMessage("AI_WORLD_ENTITY_LIMIT_CLEANUP done=true");
            return true;
        }
        if ("seed".equalsIgnoreCase(args[0])) {
            seed(sender, args);
            return true;
        }
        if ("spawncheck".equalsIgnoreCase(args[0])) {
            spawnCheck(sender, args);
            return true;
        }
        if ("count".equalsIgnoreCase(args[0])) {
            count(sender, args);
            return true;
        }
        sender.sendMessage("AI_WORLD_ENTITY_LIMIT_USAGE cleanup|seed|spawncheck|count");
        return true;
    }

    /** 生成达到配置上限的测试实体。 */
    private void seed(CommandSender sender, String[] args) {
        EntityType type = entityType(args, 1, EntityType.COW);
        int count = args.length > 2 ? parseInt(args[2], 2) : 2;
        cleanup();
        World world = mainWorld();
        Location base = baseLocation();
        keepChunkLoaded(base, true);
        for (int index = 0; index < count; index++) {
            Entity entity = world.spawnEntity(base.clone().add(index, 0.0, 0.0), type);
            mark(entity, "SEED_" + index);
        }
        sender.sendMessage("AI_WORLD_ENTITY_LIMIT_SEED type=" + type.name()
                + " requested=" + count
                + " count=" + countEntities(type)
                + " world=" + world.getName()
                + " chunk=" + base.getBlockX() / 16 + "," + base.getBlockZ() / 16);
    }

    /** 尝试额外生成一只实体，检查正式监听器是否按上限拦截。 */
    private void spawnCheck(CommandSender sender, String[] args) {
        EntityType type = entityType(args, 1, EntityType.COW);
        int before = countEntities(type);
        Entity entity = null;
        UUID uniqueId = null;
        boolean thrown = false;
        try {
            entity = mainWorld().spawnEntity(baseLocation().clone().add(4.0, 0.0, 0.0), type);
            uniqueId = entity.getUniqueId();
            mark(entity, "SPAWN_CHECK");
        } catch (RuntimeException exception) {
            thrown = true;
        }
        boolean alive = uniqueId != null && isEntityAlive(uniqueId);
        int after = countEntities(type);
        boolean blocked = thrown || (!alive && after <= before);
        boolean allowed = alive && after > before;
        sender.sendMessage("AI_WORLD_ENTITY_LIMIT_SPAWN type=" + type.name()
                + " before=" + before
                + " after=" + after
                + " spawnedAlive=" + alive
                + " blocked=" + blocked
                + " allowed=" + allowed
                + " thrown=" + thrown);
    }

    /** 输出当前测试实体数量。 */
    private void count(CommandSender sender, String[] args) {
        EntityType type = entityType(args, 1, EntityType.COW);
        sender.sendMessage("AI_WORLD_ENTITY_LIMIT_COUNT type=" + type.name()
                + " count=" + countEntities(type)
                + " world=" + mainWorld().getName());
    }

    /** 清理本夹具生成的实体。 */
    private void cleanup() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                String name = entity.getCustomName();
                if (name != null && name.startsWith(PREFIX)) {
                    entity.remove();
                }
            }
        }
    }

    /** 给测试实体设置稳定标记。 */
    private void mark(Entity entity, String suffix) {
        if (entity == null) {
            return;
        }
        entity.setCustomName(PREFIX + suffix);
        if (entity instanceof LivingEntity) {
            LivingEntity living = (LivingEntity) entity;
            living.setRemoveWhenFarAway(false);
            try {
                living.getClass().getMethod("setAI", boolean.class).invoke(living, Boolean.FALSE);
            } catch (Throwable ignored) {
                // 老版本或特殊端不支持 setAI 时，不影响世界上限测试。
            }
        }
    }

    /** 统计当前主世界里的测试实体。 */
    private int countEntities(EntityType type) {
        int count = 0;
        for (Entity entity : mainWorld().getEntities()) {
            String name = entity.getCustomName();
            if (entity.getType() == type && name != null && name.startsWith(PREFIX)) {
                count++;
            }
        }
        return count;
    }

    /** 判断指定实体是否仍然存在。 */
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

    /** 保持测试 chunk 已加载。 */
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

    /** 按参数解析实体类型。 */
    private EntityType entityType(String[] args, int index, EntityType fallback) {
        if (args.length <= index) {
            return fallback;
        }
        try {
            return EntityType.valueOf(args[index].trim().toUpperCase());
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    /** 解析整数。 */
    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /** 返回测试基准位置。 */
    private Location baseLocation() {
        Location base = mainWorld().getSpawnLocation().clone().add(BASE_OFFSET, 1.0, 0.0);
        keepChunkLoaded(base, true);
        return base;
    }

    /** 返回主世界。 */
    private World mainWorld() {
        return Bukkit.getWorlds().get(0);
    }
}
'''


PLUGIN_YML = """name: BlWtcWorldEntityLimitFixture
version: 1.0.0
main: ai.blwtc.fixture.WorldEntityLimitFixturePlugin
commands:
  worldentitylimitfixture:
    description: BlWorldTrashCan world entity limit fixture
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
    """确认 F-070/F-072 验证所需运行环境存在。"""
    sync_universal_dist()
    missing = []
    for path in (UNIVERSAL_JAR, legacy.PAPER1122_JAR, SPIGOT2612_JAR, legacy.JAVA8, JAVA25, JAVAC17, JAR17, BUKKIT_API_JAR):
        if not path.is_file():
            missing.append(str(path))
    if missing:
        raise RuntimeError("缺少世界实体上限验证输入: " + "; ".join(missing))


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
    fixture_jar = run_root / "BlWtcWorldEntityLimitFixture.jar"
    if classes_dir.exists():
        shutil.rmtree(classes_dir)
    if resources_dir.exists():
        shutil.rmtree(resources_dir)
    source_dir.mkdir(parents=True, exist_ok=True)
    resources_dir.mkdir(parents=True, exist_ok=True)
    (source_dir / "WorldEntityLimitFixturePlugin.java").write_text(FIXTURE_SOURCE, encoding="utf-8")
    (resources_dir / "plugin.yml").write_text(PLUGIN_YML, encoding="utf-8")
    subprocess.run([
        str(JAVAC17),
        "--release", "8",
        "-encoding", "UTF-8",
        "-cp", str(BUKKIT_API_JAR),
        "-d", str(classes_dir),
        str(source_dir / "WorldEntityLimitFixturePlugin.java"),
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
    """返回本轮 F-070/F-072 覆盖的服务端用例。"""
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


def entity_limit_config(enabled: bool, ignored_worlds: list[str]) -> str:
    """生成本轮世界实体上限测试配置。"""
    if ignored_worlds:
        ignored_block = "  ignored-worlds:\n" + "".join('    - "' + world + '"\n' for world in ignored_worlds)
    else:
        ignored_block = "  ignored-worlds: []\n"
    enabled_text = "true" if enabled else "false"
    return (
        "# AI 自动化 F-070/F-072 测试临时配置。\n"
        "# 测试服目录会在 build 中重建，不会覆盖用户正式配置。\n"
        "world-limits:\n"
        "  # 启用单世界实体数量限制。\n"
        "  enabled: " + enabled_text + "\n"
        "  # 本轮测试会在 active/ignored 两种状态下重写这里。\n"
        + ignored_block
        + "  # COW 上限设为 2，方便验证第 3 只实体被拦截。\n"
        "  defaults:\n"
        "    - entity: \"COW\"\n"
        "      max-count: 2\n"
        "scanner:\n"
        "  # 测试服使用小周期和较高 chunk 预算，加快建立索引。\n"
        "  target-full-cycle-seconds: 30\n"
        "  scan-interval-ticks: 1\n"
        "  min-chunks-per-scan: 16\n"
        "  max-chunks-per-scan: 128\n"
        "  max-scan-millis-per-run: 20\n"
        "  remove-interval-ticks: 1\n"
        "  max-removes-per-run: 20\n"
        "  max-pending-removals: 200\n"
        "  candidate-ttl-seconds: 30\n"
        "  max-candidate-retries: 1\n"
        "  max-dirty-chunks: 512\n"
        "  stale-chunk-seconds: 120\n"
        "  max-index-entities: 1000\n"
        "  max-index-entities-per-chunk: 128\n"
        "  log-summary-seconds: 0\n"
        "gather-limits:\n"
        "  # 本轮只测单世界实体数量限制，关闭密集实体删除避免干扰。\n"
        "  enabled: false\n"
        "  drop-items: false\n"
        "  ignored-worlds: []\n"
        "  defaults: []\n"
    )


def prepare_server(case: dict, run_root: Path, fixture_jar: Path) -> Path:
    """准备独立世界实体上限测试服目录。"""
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


def write_entity_limits(server_dir: Path, enabled: bool, ignored_worlds: list[str]) -> Path:
    """写入临时 entity-limits.yml。"""
    target = server_dir / "plugins" / "BlWorldTrashCan" / "entity-limits.yml"
    if not target.parent.is_dir():
        raise RuntimeError("BlWorldTrashCan 配置目录尚未生成: " + str(target.parent))
    target.write_text(entity_limit_config(enabled, ignored_worlds), encoding="utf-8")
    return target


def run_case(case: dict, run_root: Path, evidence_dir: Path, fixture_jar: Path) -> dict:
    """运行一个世界实体上限服务端用例。"""
    log("准备世界实体上限用例 " + case["id"])
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
            seed_config = write_entity_limits(server_dir, False, [])
            copy_if_exists(seed_config, case_dir / "config" / "entity-limits-seed-disabled.yml")
            run_rcon(case, "plugins", responses, entries, "plugins")
            run_rcon(case, "blwtc platform", responses, entries, "platform")
            run_rcon(case, "blwtc reload", responses, entries, "reload-seed-disabled")
            run_rcon(case, "worldentitylimitfixture cleanup", responses, entries, "cleanup-before")
            run_rcon(case, "worldentitylimitfixture seed COW 2", responses, entries, "seed")
            active_config = write_entity_limits(server_dir, True, [])
            copy_if_exists(active_config, case_dir / "config" / "entity-limits-active.yml")
            run_rcon(case, "blwtc reload", responses, entries, "reload-active")
            responses["debug-active"] = wait_indexed(case, entries, minimum_entities=2)
            run_rcon(case, "worldentitylimitfixture spawncheck COW", responses, entries, "spawn-active")
            run_rcon(case, "worldentitylimitfixture count COW", responses, entries, "count-after-active")
            ignored_config = write_entity_limits(server_dir, True, ["world", "world_nether", "world_the_end"])
            copy_if_exists(ignored_config, case_dir / "config" / "entity-limits-ignored.yml")
            run_rcon(case, "blwtc reload", responses, entries, "reload-ignored")
            responses["debug-ignored"] = wait_ignored(case, entries)
            run_rcon(case, "worldentitylimitfixture spawncheck COW", responses, entries, "spawn-ignored")
            run_rcon(case, "worldentitylimitfixture count COW", responses, entries, "count-after-ignored")
            run_rcon(case, "worldentitylimitfixture cleanup", responses, entries, "cleanup-after")
            command_log.write_text("\n\n".join(entries) + "\n", encoding="utf-8")
            time.sleep(1.0)
            result = assert_case(case, responses, active_config, ignored_config, stdout_log)
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


def wait_indexed(case: dict, entries: list[str], minimum_entities: int) -> str:
    """等待低占用索引至少记录指定数量的实体。"""
    deadline = time.time() + 45
    last = ""
    while time.time() < deadline:
        body = legacy.rcon_command(case["rcon"], "blwtc debugdensity")
        last = body
        entries.append("> blwtc debugdensity\n" + body.rstrip())
        if parse_indexed_entities(body) >= minimum_entities:
            return body
        time.sleep(1.0)
    raise AssertionError(case["id"] + " 等待实体索引超时，最后输出:\n" + last)


def wait_ignored(case: dict, entries: list[str]) -> str:
    """等待 ignored-worlds 生效后扫描器不再选择默认世界 chunk。"""
    deadline = time.time() + 20
    last = ""
    while time.time() < deadline:
        body = legacy.rcon_command(case["rcon"], "blwtc debugdensity")
        last = body
        entries.append("> blwtc debugdensity\n" + body.rstrip())
        loaded_selected = parse_loaded_selected(body)
        if loaded_selected == (0, 0):
            return body
        time.sleep(1.0)
    raise AssertionError(case["id"] + " 等待 ignored-worlds 扫描跳过超时，最后输出:\n" + last)


def strip_colors(text: str) -> str:
    """移除 Bukkit 颜色码。"""
    return re.sub(r"§.", "", text or "")


def parse_indexed_entities(text: str) -> int:
    """解析 debugdensity 的索引实体数。"""
    plain = strip_colors(text)
    match = re.search(r"索引 chunk/实体:\s*(\d+)\D+(\d+)", plain)
    if match:
        return int(match.group(2))
    groups = re.findall(r"(\d+)\s*/\s*(\d+)", plain)
    return int(groups[1][1]) if len(groups) > 1 else 0


def parse_loaded_selected(text: str) -> tuple[int, int] | None:
    """解析 debugdensity 的已加载/本轮选择 chunk。"""
    plain = strip_colors(text)
    match = re.search(r"已加载/本轮选择 chunk:\s*(\d+)\D+(\d+)", plain)
    if match:
        return int(match.group(1)), int(match.group(2))
    groups = re.findall(r"(\d+)\s*/\s*(\d+)", plain)
    if groups:
        return int(groups[0][0]), int(groups[0][1])
    return None


def assert_case(case: dict, responses: dict, active_config: Path, ignored_config: Path, stdout_log: Path) -> dict:
    """断言单端世界实体上限结果。"""
    require("BlWorldTrashCan", responses.get("plugins", ""), case["id"] + " 插件列表缺少 BlWorldTrashCan")
    require("BlWtcWorldEntityLimitFixture", responses.get("plugins", ""), case["id"] + " 插件列表缺少夹具")
    require(case["expectedPlatform"], responses.get("platform", ""), case["id"] + " 平台不符合预期")
    require("universal", responses.get("platform", ""), case["id"] + " 未加载 universal 分支")
    require_all(["AI_WORLD_ENTITY_LIMIT_SEED", "requested=2"], responses.get("seed", ""), case["id"] + " 种子实体生成失败")
    require_all(["AI_WORLD_ENTITY_LIMIT_SPAWN", "before=2", "blocked=true", "allowed=false"], responses.get("spawn-active", ""), case["id"] + " F-070 未拦截超量生成")
    require_all(["AI_WORLD_ENTITY_LIMIT_SPAWN", "allowed=true", "blocked=false"], responses.get("spawn-ignored", ""), case["id"] + " F-072 ignored-worlds 未放行生成")
    require_all(["world-limits:", "enabled: true", "entity: \"COW\"", "max-count: 2"], active_config.read_text(encoding="utf-8"), case["id"] + " active entity-limits.yml 不符合预期")
    require_all(["world_nether", "world_the_end"], ignored_config.read_text(encoding="utf-8"), case["id"] + " ignored entity-limits.yml 不符合预期")
    require_all(["[EntityLimit]", "world=world", "type=COW", "max=2"],
                stdout_log.read_text(encoding="utf-8", errors="replace"),
                case["id"] + " 缺少正式拦截日志")
    return {
        "id": case["id"],
        "label": case["label"],
        "passed": True,
        "platform": case["expectedPlatform"],
        "indexedEntities": parse_indexed_entities(responses.get("debug-active", "")),
        "ignoredLoadedSelected": parse_loaded_selected(responses.get("debug-ignored", "")),
        "f070": responses.get("spawn-active", ""),
        "f072": responses.get("spawn-ignored", ""),
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
    """复制单个世界实体上限用例证据。"""
    copy_if_exists(server_dir / "logs" / "latest.log", case_dir / "logs" / "latest.log")
    plugin_dir = server_dir / "plugins" / "BlWorldTrashCan"
    copy_if_exists(plugin_dir / "entity-limits.yml", case_dir / "config" / "entity-limits-after-test.yml")
    copy_if_exists(plugin_dir / "config.yml", case_dir / "config" / "config.yml")


def copy_if_exists(source: Path, target: Path) -> None:
    """复制可能存在的文件。"""
    if source.is_file():
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)


def write_readme(evidence_dir: Path, summary: dict) -> None:
    """写入 F-070/F-072 证据说明。"""
    lines = [
        "# F-070/F-072 世界实体上限专项验收",
        "",
        "- 被测插件: `dist/BlWorldTrashCan-universal.jar`",
        "- SHA256: `" + summary["jarSha256"] + "`",
        "- 验收方式: 真实服务端启动 + 低占用索引等待 + 临时 Bukkit 夹具走正式实体生成路径",
        "- 通过标准: 先关闭实体限制铺底 COW=2，再开启上限等待缓存建立，第 3 只被拦截；同一世界写入 `ignored-worlds` 后第 3 只被放行",
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
            "- 配置证据: `" + result["id"] + "/config/entity-limits-seed-disabled.yml`、`" + result["id"] + "/config/entity-limits-active.yml`、`" + result["id"] + "/config/entity-limits-ignored.yml`、`" + result["id"] + "/config/entity-limits-after-test.yml`",
            "- 索引实体数: `" + str(result["indexedEntities"]) + "`",
            "- ignored-worlds 扫描状态: `" + str(result["ignoredLoadedSelected"]) + "`",
            "",
        ])
    (evidence_dir / "README.md").write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")


def main() -> int:
    """执行 F-070/F-072 世界实体上限专项矩阵。"""
    ensure_inputs()
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    run_root = BUILD_ROOT / timestamp
    evidence_dir = EVIDENCE_ROOT / ("world-entity-limit-" + timestamp)
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
