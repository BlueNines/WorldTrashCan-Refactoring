import hashlib
import json
import shutil
import subprocess
import time
from pathlib import Path

import run_legacy_migration_matrix as legacy


EVIDENCE_ROOT = legacy.REPO / "docs" / "test-evidence"
BUILD_ROOT = legacy.REPO / "build" / "entity-cleanup-toggle-matrix"
UNIVERSAL_JAR = legacy.UNIVERSAL_JAR
JAVA25 = legacy.REPO / "build" / "tools" / "jre-25.0.3+9" / "bin" / "java.exe"
JAVAC17 = legacy.REPO / "build" / "tools" / "jdk-17.0.19+10" / "bin" / "javac.exe"
JAR17 = legacy.REPO / "build" / "tools" / "jdk-17.0.19+10" / "bin" / "jar.exe"
SPIGOT2612_JAR = Path(r"E:\server_work\spigot-26.1.2-test-server\spigot-26.1.2.jar")
BUKKIT_API_JAR = Path.home() / ".m2" / "repository" / "org" / "spigotmc" / "spigot-api" / "1.12.2-R0.1-SNAPSHOT" / "spigot-api-1.12.2-R0.1-SNAPSHOT.jar"


FIXTURE_SOURCE = r'''
package ai.wtc.fixture;

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
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** WorldListTrashCan 实体清理总开关验收夹具。 */
public final class EntityToggleFixturePlugin extends JavaPlugin implements CommandExecutor {
    private static final String PREFIX = "AI_ENTITY_TOGGLE_";
    private final Map<String, UUID> trackedEntities = new LinkedHashMap<>();
    private Location baseLocation;

    /** 注册 entitytogglefixture 命令。 */
    @Override
    public void onEnable() {
        getCommand("entitytogglefixture").setExecutor(this);
    }

    /** 执行夹具命令。 */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("AI_ENTITY_TOGGLE_USAGE prepare|assert_disabled|assert_enabled|cleanup");
            return true;
        }
        if ("prepare".equalsIgnoreCase(args[0])) {
            prepare(sender);
            return true;
        }
        if ("assert_disabled".equalsIgnoreCase(args[0])) {
            assertDisabled(sender);
            return true;
        }
        if ("assert_enabled".equalsIgnoreCase(args[0])) {
            assertEnabled(sender);
            return true;
        }
        if ("cleanup".equalsIgnoreCase(args[0])) {
            cleanup();
            sender.sendMessage("AI_ENTITY_TOGGLE_CLEANUP done=true");
            return true;
        }
        sender.sendMessage("AI_ENTITY_TOGGLE_USAGE prepare|assert_disabled|assert_enabled|cleanup");
        return true;
    }

    /** 生成一组覆盖实体总开关的可清理目标。 */
    private void prepare(CommandSender sender) {
        cleanup();
        World world = Bukkit.getWorlds().get(0);
        Location base = safeBaseLocation(world);
        baseLocation = base.clone();
        keepChunkLoaded(baseLocation, true);
        world.setTime(18000L);
        spawnTracked(world, "cow", EntityType.COW, base.clone().add(0.0, 0.0, 0.0), PREFIX + "COW");
        spawnTracked(world, "zombie", EntityType.ZOMBIE, base.clone().add(2.0, 0.0, 0.0), PREFIX + "ZOMBIE");
        spawnTracked(world, "arrow", EntityType.ARROW, base.clone().add(4.0, 1.0, 0.0), PREFIX + "ARROW");
        spawnTracked(world, "experience_orb", EntityType.EXPERIENCE_ORB, base.clone().add(6.0, 0.0, 0.0), PREFIX + "ORB");
        spawnTracked(world, "blacklist_named", EntityType.COW, base.clone().add(8.0, 0.0, 0.0), PREFIX + "BLACKLIST");
        sender.sendMessage("AI_ENTITY_TOGGLE_PREPARED total=" + trackedEntities.size()
                + " alive=" + aliveCount()
                + " names=" + statusText()
                + " world=" + world.getName());
    }

    /** 断言关闭 entities.enabled 后所有目标仍然存在。 */
    private void assertDisabled(CommandSender sender) {
        if (baseLocation != null) {
            keepChunkLoaded(baseLocation, true);
        }
        int alive = aliveCount();
        boolean passed = alive == trackedEntities.size();
        sender.sendMessage("AI_ENTITY_TOGGLE_DISABLED_RESULT passed=" + passed
                + " alive=" + alive
                + " expected=" + trackedEntities.size()
                + " names=" + statusText());
    }

    /** 断言重新开启 entities.enabled 后所有目标都已被清理。 */
    private void assertEnabled(CommandSender sender) {
        if (baseLocation != null) {
            keepChunkLoaded(baseLocation, true);
        }
        int alive = aliveCount();
        boolean passed = alive == 0;
        sender.sendMessage("AI_ENTITY_TOGGLE_ENABLED_RESULT passed=" + passed
                + " alive=" + alive
                + " expected=0"
                + " names=" + statusText());
    }

    /** 生成并标记一个测试实体。 */
    private void spawnTracked(World world, String key, EntityType type, Location location, String name) {
        Entity entity = world.spawnEntity(location, type);
        entity.setCustomName(name);
        entity.setVelocity(new Vector(0.0, 0.0, 0.0));
        try {
            entity.getClass().getMethod("setGravity", boolean.class).invoke(entity, Boolean.FALSE);
        } catch (Throwable ignored) {
            // 旧版本没有 setGravity 时，测试仍会等待一拍再断言。
        }
        if (entity instanceof LivingEntity) {
            LivingEntity living = (LivingEntity) entity;
            living.setRemoveWhenFarAway(false);
            try {
                living.getClass().getMethod("setAI", boolean.class).invoke(living, Boolean.FALSE);
            } catch (Throwable ignored) {
                // 1.12.2 以下兼容 API 不保证有 setAI，不影响清理总开关验证。
            }
        }
        trackedEntities.put(key, entity.getUniqueId());
    }

    /** 清理本夹具生成的残留实体。 */
    private void cleanup() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                String name = entity.getCustomName();
                if (name != null && name.startsWith(PREFIX)) {
                    entity.remove();
                }
            }
        }
        if (baseLocation != null) {
            keepChunkLoaded(baseLocation, false);
        }
        trackedEntities.clear();
        baseLocation = null;
    }

    /** 返回适合无玩家临时服生成实体的稳定位置。 */
    private Location safeBaseLocation(World world) {
        Location spawn = world.getSpawnLocation().clone().add(16.0, 0.0, 16.0);
        int surfaceY = world.getHighestBlockYAt(spawn.getBlockX(), spawn.getBlockZ());
        return new Location(world, spawn.getBlockX() + 0.5, Math.max(surfaceY + 2, spawn.getBlockY() + 2), spawn.getBlockZ() + 0.5);
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

    /** 统计当前仍存在的测试实体数量。 */
    private int aliveCount() {
        int alive = 0;
        for (UUID uniqueId : trackedEntities.values()) {
            if (isEntityAlive(uniqueId)) {
                alive++;
            }
        }
        return alive;
    }

    /** 输出每个测试实体的存在状态。 */
    private String statusText() {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, UUID> entry : trackedEntities.entrySet()) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(entry.getKey()).append(':').append(isEntityAlive(entry.getValue()));
        }
        return builder.toString();
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
}
'''


PLUGIN_YML = """name: BlWtcEntityToggleFixture
version: 1.0.0
main: ai.wtc.fixture.EntityToggleFixturePlugin
commands:
  entitytogglefixture:
    description: WorldListTrashCan entity cleanup toggle fixture
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
    """确认实体清理总开关验证所需运行环境存在。"""
    missing = []
    for path in (UNIVERSAL_JAR, legacy.PAPER1122_JAR, SPIGOT2612_JAR, legacy.JAVA8, JAVA25, JAVAC17, JAR17, BUKKIT_API_JAR):
        if not path.is_file():
            missing.append(str(path))
    if missing:
        raise RuntimeError("缺少实体清理总开关验证输入: " + "; ".join(missing))


def build_fixture(run_root: Path) -> Path:
    """编译临时 Bukkit 测试插件。"""
    source_dir = run_root / "fixture-src" / "ai" / "wtc" / "fixture"
    classes_dir = run_root / "fixture-classes"
    resources_dir = run_root / "fixture-resources"
    fixture_jar = run_root / "BlWtcEntityToggleFixture.jar"
    if classes_dir.exists():
        shutil.rmtree(classes_dir)
    if resources_dir.exists():
        shutil.rmtree(resources_dir)
    source_dir.mkdir(parents=True, exist_ok=True)
    resources_dir.mkdir(parents=True, exist_ok=True)
    (source_dir / "EntityToggleFixturePlugin.java").write_text(FIXTURE_SOURCE, encoding="utf-8")
    (resources_dir / "plugin.yml").write_text(PLUGIN_YML, encoding="utf-8")
    subprocess.run([
        str(JAVAC17),
        "--release", "8",
        "-encoding", "UTF-8",
        "-cp", str(BUKKIT_API_JAR),
        "-d", str(classes_dir),
        str(source_dir / "EntityToggleFixturePlugin.java"),
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
    """返回本轮实体总开关覆盖的服务端用例。"""
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
    """准备独立实体总开关测试服目录。"""
    server_dir = run_root / case["id"] / "server"
    if server_dir.exists():
        shutil.rmtree(server_dir)
    (server_dir / "plugins").mkdir(parents=True)
    shutil.copy2(case["serverJar"], server_dir / Path(case["serverJar"]).name)
    if case.get("copyPaperCache"):
        legacy.copy_paper_runtime_cache(server_dir)
    shutil.copy2(UNIVERSAL_JAR, server_dir / "plugins" / "WorldListTrashCan-universal.jar")
    shutil.copy2(fixture_jar, server_dir / "plugins" / fixture_jar.name)
    (server_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (server_dir / "server.properties").write_text(
        legacy.make_server_properties(case["port"], case["rcon"]) + "difficulty=2\n",
        encoding="utf-8",
    )
    return server_dir


def patch_cleanup_config(server_dir: Path, entity_enabled: bool) -> Path:
    """修改 cleanup.yml，使实体总开关语义可被运行态验证。"""
    cleanup = server_dir / "plugins" / "WorldListTrashCan" / "cleanup.yml"
    if not cleanup.is_file():
        raise RuntimeError("cleanup.yml 不存在，无法配置实体总开关验证: " + str(cleanup))
    text = cleanup.read_text(encoding="utf-8")
    replacements = {
        "interval-seconds: 360": "interval-seconds: 0",
        "  min-online-players: 1": "  min-online-players: 0",
        "  min-total-entities: 150": "  min-total-entities: 0",
        "  clear-animals: false": "  clear-animals: true",
        "  clear-named-entities: false": "  clear-named-entities: true",
    }
    for old, new in replacements.items():
        text = text.replace(old, new)
    if entity_enabled:
        text = text.replace("  enabled: false\n  # 是否清理经验球", "  enabled: true\n  # 是否清理经验球")
    else:
        text = text.replace("  enabled: true\n  # 是否清理经验球", "  enabled: false\n  # 是否清理经验球")
    if "AI_ENTITY_TOGGLE_BLACKLIST" not in text:
        text = text.replace(
            '    - "FLAMMPFEIL.SLASHBLADE_BLADESTAND"',
            '    - "FLAMMPFEIL.SLASHBLADE_BLADESTAND"\n    - "AI_ENTITY_TOGGLE_BLACKLIST"',
        )
    cleanup.write_text(text, encoding="utf-8")
    return cleanup


def run_case(case: dict, run_root: Path, evidence_dir: Path, fixture_jar: Path) -> dict:
    """运行一个实体清理总开关服务端用例。"""
    log("准备实体总开关用例 " + case["id"])
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
        try:
            legacy.wait_for_rcon(case["rcon"])
            disabled_cleanup = patch_cleanup_config(server_dir, False)
            disabled_cleanup_text = disabled_cleanup.read_text(encoding="utf-8")
            copy_if_exists(disabled_cleanup, case_dir / "config" / "cleanup-disabled-after-patch.yml")
            commands = [
                ("plugins", "plugins", 0.4),
                ("wtc platform", "wtc platform", 0.4),
                ("wtc reload", "wtc reload", 0.4),
                ("entitytogglefixture prepare", "entitytogglefixture prepare", 1.0),
                ("entitytogglefixture assert_ready", "entitytogglefixture assert_disabled", 0.4),
                ("wtc clear true", "wtc clear true", 0.4),
                ("entitytogglefixture assert_disabled", "entitytogglefixture assert_disabled", 0.4),
            ]
            responses = {}
            entries = []
            for response_key, command, delay_seconds in commands:
                body = legacy.rcon_command(case["rcon"], command)
                responses[response_key] = body
                entries.append("> " + command + "\n" + body.rstrip())
                time.sleep(delay_seconds)
            enabled_cleanup = patch_cleanup_config(server_dir, True)
            enabled_cleanup_text = enabled_cleanup.read_text(encoding="utf-8")
            enabled_commands = (
                ("wtc reload #enabled", "wtc reload"),
                ("wtc clear true #enabled", "wtc clear true"),
                ("entitytogglefixture assert_enabled", "entitytogglefixture assert_enabled"),
                ("wtc stats", "wtc stats"),
                ("entitytogglefixture cleanup", "entitytogglefixture cleanup"),
            )
            for response_key, command in enabled_commands:
                body = legacy.rcon_command(case["rcon"], command)
                responses[response_key] = body
                entries.append("> " + command + "\n" + body.rstrip())
                time.sleep(0.4)
            command_log.write_text("\n\n".join(entries) + "\n", encoding="utf-8")
            time.sleep(1.0)
            result = assert_case(case, responses, disabled_cleanup_text, enabled_cleanup_text)
            legacy.stop_server(process, case["rcon"])
        finally:
            legacy.terminate_server(process)
    copy_case_evidence(case, server_dir, case_dir)
    return result


def assert_case(case: dict, responses: dict, disabled_cleanup_text: str, enabled_cleanup_text: str) -> dict:
    """断言实体清理总开关用例结果。"""
    plugins = responses.get("plugins", "")
    platform = responses.get("wtc platform", "")
    prepared = responses.get("entitytogglefixture prepare", "")
    ready_result = responses.get("entitytogglefixture assert_ready", "")
    disabled_result = responses.get("entitytogglefixture assert_disabled", "")
    enabled_result = responses.get("entitytogglefixture assert_enabled", "")
    stats = responses.get("wtc stats", "")
    if "WorldListTrashCan" not in plugins or "BlWtcEntityToggleFixture" not in plugins:
        raise AssertionError(case["id"] + " 插件列表未包含主插件和夹具: " + plugins)
    if case["expectedPlatform"] not in platform or "universal" not in platform:
        raise AssertionError(case["id"] + " 平台输出不符合预期: " + platform)
    for marker in ("AI_ENTITY_TOGGLE_PREPARED", "total=5"):
        if marker not in prepared:
            raise AssertionError(case["id"] + " prepare 缺少标记 " + marker + ": " + prepared)
    for marker in ("AI_ENTITY_TOGGLE_DISABLED_RESULT", "passed=true", "alive=5", "cow:true", "zombie:true", "arrow:true", "experience_orb:true", "blacklist_named:true"):
        if marker not in ready_result:
            raise AssertionError(case["id"] + " ready assert 缺少标记 " + marker + ": " + ready_result)
    for marker in ("AI_ENTITY_TOGGLE_DISABLED_RESULT", "passed=true", "alive=5", "cow:true", "zombie:true", "arrow:true", "experience_orb:true", "blacklist_named:true"):
        if marker not in disabled_result:
            raise AssertionError(case["id"] + " disabled assert 缺少标记 " + marker + ": " + disabled_result)
    for marker in ("AI_ENTITY_TOGGLE_ENABLED_RESULT", "passed=true", "alive=0", "cow:false", "zombie:false", "arrow:false", "experience_orb:false", "blacklist_named:false"):
        if marker not in enabled_result:
            raise AssertionError(case["id"] + " enabled assert 缺少标记 " + marker + ": " + enabled_result)
    if "清理统计" not in stats or "删除实体" not in stats:
        raise AssertionError(case["id"] + " stats 缺少清理统计: " + stats)
    for marker in ("enabled: true", "clear-animals: true", "clear-named-entities: true", "AI_ENTITY_TOGGLE_BLACKLIST"):
        if marker not in enabled_cleanup_text:
            raise AssertionError(case["id"] + " enabled cleanup.yml 缺少 " + marker)
    if "enabled: false" not in disabled_cleanup_text:
        raise AssertionError(case["id"] + " disabled cleanup.yml 未保留 enabled=false 证据")
    return {
        "id": case["id"],
        "label": case["label"],
        "passed": True,
        "platform": case["expectedPlatform"],
        "prepared": prepared,
        "readyResult": ready_result,
        "disabledResult": disabled_result,
        "enabledResult": enabled_result,
        "stats": stats,
    }


def copy_case_evidence(case: dict, server_dir: Path, case_dir: Path) -> None:
    """复制单个实体总开关用例证据。"""
    copy_if_exists(server_dir / "logs" / "latest.log", case_dir / "logs" / "latest.log")
    plugin_dir = server_dir / "plugins" / "WorldListTrashCan"
    copy_if_exists(plugin_dir / "cleanup.yml", case_dir / "config" / "cleanup-enabled-after-patch.yml")
    copy_if_exists(plugin_dir / "config.yml", case_dir / "config" / "config.yml")


def copy_if_exists(source: Path, target: Path) -> None:
    """复制可能存在的文件。"""
    if source.is_file():
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)


def write_readme(evidence_dir: Path, summary: dict) -> None:
    """写入实体清理总开关证据说明。"""
    lines = [
        "# 实体清理总开关专项验收",
        "",
        "- 被测插件: `dist/WorldListTrashCan-universal.jar`",
        "- SHA256: `" + summary["jarSha256"] + "`",
        "- 验收方式: 真实服务端启动 + 临时 Bukkit 夹具生成牛/僵尸/箭/经验球/黑名单命名实体 + 正式 `/wtc clear true`",
        "- 通过标准: `entities.enabled=false` 时 5 个实体全部保留；切回 `entities.enabled=true` 后同一批实体全部被正式清理",
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
            "- 关闭配置证据: `" + result["id"] + "/config/cleanup-disabled-after-patch.yml`",
            "- 开启配置证据: `" + result["id"] + "/config/cleanup-enabled-after-patch.yml`",
            "- 准备后断言: `" + result["readyResult"].strip().replace("`", "'") + "`",
            "- 关闭后断言: `" + result["disabledResult"].strip().replace("`", "'") + "`",
            "- 开启后断言: `" + result["enabledResult"].strip().replace("`", "'") + "`",
            "",
        ])
    (evidence_dir / "README.md").write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")


def main() -> int:
    """执行实体清理总开关专项矩阵。"""
    ensure_inputs()
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    run_root = BUILD_ROOT / timestamp
    evidence_dir = EVIDENCE_ROOT / ("entity-cleanup-toggle-" + timestamp)
    run_root.mkdir(parents=True, exist_ok=True)
    evidence_dir.mkdir(parents=True, exist_ok=True)
    fixture_jar = build_fixture(run_root)
    results = []
    for case in cases():
        results.append(run_case(case, run_root, evidence_dir, fixture_jar))
    summary = {
        "timestamp": timestamp,
        "allPassed": all(item["passed"] for item in results),
        "jar": str(UNIVERSAL_JAR),
        "jarSha256": sha256_file(UNIVERSAL_JAR),
        "fixtureJar": str(fixture_jar),
        "fixtureSha256": sha256_file(fixture_jar),
        "evidenceDir": str(evidence_dir),
        "results": results,
    }
    write_json(evidence_dir / "summary.json", summary)
    write_readme(evidence_dir, summary)
    log("实体清理总开关专项完成: " + str(evidence_dir))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
