import hashlib
import json
import shutil
import subprocess
import time
from pathlib import Path

import run_legacy_migration_matrix as legacy


EVIDENCE_ROOT = legacy.REPO / "docs" / "test-evidence"
BUILD_ROOT = legacy.REPO / "build" / "boat-entity-protection-matrix"
UNIVERSAL_JAR = legacy.UNIVERSAL_JAR
JAVA25 = legacy.REPO / "build" / "tools" / "jre-25.0.3+9" / "bin" / "java.exe"
JAVAC17 = legacy.REPO / "build" / "tools" / "jdk-17.0.19+10" / "bin" / "javac.exe"
JAR17 = legacy.REPO / "build" / "tools" / "jdk-17.0.19+10" / "bin" / "jar.exe"
SPIGOT2612_JAR = Path(r"E:\server_work\spigot-26.1.2-test-server\spigot-26.1.2.jar")
BUKKIT_API_JAR = Path.home() / ".m2" / "repository" / "org" / "spigotmc" / "spigot-api" / "1.12.2-R0.1-SNAPSHOT" / "spigot-api-1.12.2-R0.1-SNAPSHOT.jar"


FIXTURE_SOURCE = r'''
package ai.blwtc.fixture;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Chunk;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.UUID;

/** BLWorldTrashCan 船内实体保护验收夹具。 */
public final class BoatFixturePlugin extends JavaPlugin implements CommandExecutor {
    private UUID boatId;
    private UUID protectedCowId;
    private UUID normalCowId;
    private Location baseLocation;

    /** 注册 boatfixture 命令。 */
    @Override
    public void onEnable() {
        getCommand("boatfixture").setExecutor(this);
    }

    /** 执行夹具命令。 */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("AI_BOAT_FIXTURE_USAGE prepare|assert|cleanup");
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
        if ("cleanup".equalsIgnoreCase(args[0])) {
            cleanup();
            sender.sendMessage("AI_BOAT_FIXTURE_CLEANUP done=true");
            return true;
        }
        sender.sendMessage("AI_BOAT_FIXTURE_USAGE prepare|assert|cleanup");
        return true;
    }

    /** 准备一只船内牛和一只普通牛作为清理对照。 */
    private void prepare(CommandSender sender) {
        cleanup();
        World world = Bukkit.getWorlds().get(0);
        Location base = world.getSpawnLocation().clone().add(8.0, 2.0, 8.0);
        baseLocation = base.clone();
        keepChunkLoaded(baseLocation, true);
        Boat boat = (Boat) world.spawnEntity(base, EntityType.BOAT);
        Cow protectedCow = (Cow) world.spawnEntity(base, EntityType.COW);
        Cow normalCow = (Cow) world.spawnEntity(base.clone().add(5.0, 0.0, 0.0), EntityType.COW);
        protectedCow.setCustomName("AI_BOAT_PROTECTED");
        normalCow.setCustomName("AI_BOAT_NORMAL");
        boolean passengerAdded = addPassenger(boat, protectedCow);
        boatId = boat.getUniqueId();
        protectedCowId = protectedCow.getUniqueId();
        normalCowId = normalCow.getUniqueId();
        sender.sendMessage("AI_BOAT_FIXTURE_PREPARED passengerAdded=" + passengerAdded
                + " protectedInsideBoat=" + isInsideBoat(protectedCow)
                + " protectedCow=" + protectedCowId
                + " normalCow=" + normalCowId
                + " boat=" + boatId);
    }

    /** 兼容旧 Bukkit 和现代 Bukkit 的乘客添加 API。 */
    private boolean addPassenger(Boat boat, Entity passenger) {
        try {
            return boat.addPassenger(passenger);
        } catch (Throwable ignored) {
            try {
                Method method = boat.getClass().getMethod("setPassenger", Entity.class);
                method.invoke(boat, passenger);
                return passenger.isInsideVehicle() && passenger.getVehicle() instanceof Boat;
            } catch (Throwable ignoredAgain) {
                return false;
            }
        }
    }

    /** 输出正式清理后的实体保留状态。 */
    private void assertState(CommandSender sender) {
        if (baseLocation != null) {
            keepChunkLoaded(baseLocation, true);
        }
        Entity protectedCow = find(protectedCowId);
        Entity normalCow = find(normalCowId);
        Entity boat = find(boatId);
        boolean protectedExists = exists(protectedCow);
        boolean normalExists = exists(normalCow);
        boolean boatExists = exists(boat);
        boolean protectedInsideBoat = protectedExists && isInsideBoat(protectedCow);
        sender.sendMessage("AI_BOAT_FIXTURE_RESULT protectedExists=" + protectedExists
                + " normalExists=" + normalExists
                + " boatExists=" + boatExists
                + " protectedInsideBoat=" + protectedInsideBoat);
    }

    /** 清理本夹具生成的残留实体。 */
    private void cleanup() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (isFixtureEntity(entity)) {
                    entity.remove();
                }
            }
        }
        if (baseLocation != null) {
            keepChunkLoaded(baseLocation, false);
        }
        boatId = null;
        protectedCowId = null;
        normalCowId = null;
        baseLocation = null;
    }

    /** 在无玩家临时服务端里保持夹具 chunk 已加载。 */
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

    /** 判断实体是否仍然有效存在。 */
    private boolean exists(Entity entity) {
        return entity != null && !entity.isDead();
    }

    /** 判断实体是否坐在船内。 */
    private boolean isInsideBoat(Entity entity) {
        return entity != null && entity.isInsideVehicle() && entity.getVehicle() instanceof Boat;
    }

    /** 判断是否是本夹具生成的实体。 */
    private boolean isFixtureEntity(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (entity.getUniqueId().equals(boatId) || entity.getUniqueId().equals(protectedCowId)
                || entity.getUniqueId().equals(normalCowId)) {
            return true;
        }
        String customName = entity.getCustomName();
        return customName != null && customName.startsWith("AI_BOAT_");
    }
}
'''


PLUGIN_YML = """name: BLWtcBoatFixture
version: 1.0.0
main: ai.blwtc.fixture.BoatFixturePlugin
commands:
  boatfixture:
    description: BLWorldTrashCan boat entity protection fixture
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
    """确认 F-054 验证所需运行环境存在。"""
    missing = []
    for path in (UNIVERSAL_JAR, legacy.PAPER1122_JAR, SPIGOT2612_JAR, legacy.JAVA8, JAVA25, JAVAC17, JAR17, BUKKIT_API_JAR):
        if not path.is_file():
            missing.append(str(path))
    if missing:
        raise RuntimeError("缺少 F-054 船内实体保护验证输入: " + "; ".join(missing))


def build_fixture(run_root: Path) -> Path:
    """编译临时 Bukkit 测试插件。"""
    source_dir = run_root / "fixture-src" / "ai" / "blwtc" / "fixture"
    classes_dir = run_root / "fixture-classes"
    resources_dir = run_root / "fixture-resources"
    fixture_jar = run_root / "BLWtcBoatFixture.jar"
    if classes_dir.exists():
        shutil.rmtree(classes_dir)
    if resources_dir.exists():
        shutil.rmtree(resources_dir)
    source_dir.mkdir(parents=True, exist_ok=True)
    resources_dir.mkdir(parents=True, exist_ok=True)
    (source_dir / "BoatFixturePlugin.java").write_text(FIXTURE_SOURCE, encoding="utf-8")
    (resources_dir / "plugin.yml").write_text(PLUGIN_YML, encoding="utf-8")
    subprocess.run([
        str(JAVAC17),
        "--release", "8",
        "-encoding", "UTF-8",
        "-cp", str(BUKKIT_API_JAR),
        "-d", str(classes_dir),
        str(source_dir / "BoatFixturePlugin.java"),
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
    """返回本轮 F-054 覆盖的服务端用例。"""
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
    """准备独立 F-054 测试服目录。"""
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
    """修改 cleanup.yml，使 F-054 对照清晰可测。"""
    cleanup = server_dir / "plugins" / "BLWorldTrashCan" / "cleanup.yml"
    if not cleanup.is_file():
        raise RuntimeError("cleanup.yml 不存在，无法配置 F-054: " + str(cleanup))
    text = cleanup.read_text(encoding="utf-8")
    replacements = {
        "interval-seconds: 360": "interval-seconds: 0",
        "clear-animals: false": "clear-animals: true",
        "clear-named-entities: false": "clear-named-entities: true",
        "ignore-entities-in-boat: false": "ignore-entities-in-boat: true",
    }
    for old, new in replacements.items():
        text = text.replace(old, new)
    cleanup.write_text(text, encoding="utf-8")
    return cleanup


def run_case(case: dict, run_root: Path, evidence_dir: Path, fixture_jar: Path) -> dict:
    """运行一个 F-054 服务端用例。"""
    log("准备 F-054 用例 " + case["id"])
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
            patched_cleanup = patch_cleanup_config(server_dir)
            commands = [
                "plugins",
                "blwtc platform",
                "blwtc reload",
                "boatfixture prepare",
                "blwtc clear true",
                "boatfixture assert",
                "blwtc stats",
                "boatfixture cleanup",
            ]
            responses = {}
            entries = []
            for command in commands:
                body = legacy.rcon_command(case["rcon"], command)
                responses[command] = body
                entries.append("> " + command + "\n" + body.rstrip())
                time.sleep(0.4)
            command_log.write_text("\n\n".join(entries) + "\n", encoding="utf-8")
            time.sleep(1.0)
            result = assert_case(case, responses, patched_cleanup)
            legacy.stop_server(process, case["rcon"])
        finally:
            legacy.terminate_server(process)
    copy_case_evidence(case, server_dir, case_dir)
    return result


def assert_case(case: dict, responses: dict, patched_cleanup: Path) -> dict:
    """断言 F-054 用例结果。"""
    plugins = responses.get("plugins", "")
    platform = responses.get("blwtc platform", "")
    prepared = responses.get("boatfixture prepare", "")
    result = responses.get("boatfixture assert", "")
    stats = responses.get("blwtc stats", "")
    if "BLWorldTrashCan" not in plugins or "BLWtcBoatFixture" not in plugins:
        raise AssertionError(case["id"] + " 插件列表未包含主插件和夹具: " + plugins)
    if case["expectedPlatform"] not in platform or "universal" not in platform:
        raise AssertionError(case["id"] + " 平台输出不符合预期: " + platform)
    expected_prepare = ["AI_BOAT_FIXTURE_PREPARED", "passengerAdded=true", "protectedInsideBoat=true"]
    expected_result = ["AI_BOAT_FIXTURE_RESULT", "protectedExists=true", "normalExists=false", "protectedInsideBoat=true"]
    expected_stats = ["清理统计", "删除实体"]
    for marker in expected_prepare:
        if marker not in prepared:
            raise AssertionError(case["id"] + " prepare 缺少标记 " + marker + ": " + prepared)
    for marker in expected_result:
        if marker not in result:
            raise AssertionError(case["id"] + " assert 缺少标记 " + marker + ": " + result)
    for marker in expected_stats:
        if marker not in stats:
            raise AssertionError(case["id"] + " stats 缺少标记 " + marker + ": " + stats)
    cleanup_text = patched_cleanup.read_text(encoding="utf-8")
    for marker in ("clear-animals: true", "clear-named-entities: true", "ignore-entities-in-boat: true"):
        if marker not in cleanup_text:
            raise AssertionError(case["id"] + " cleanup.yml 未写入 " + marker)
    return {
        "id": case["id"],
        "label": case["label"],
        "passed": True,
        "platform": case["expectedPlatform"],
        "prepared": prepared,
        "assertion": result,
        "stats": stats,
    }


def copy_case_evidence(case: dict, server_dir: Path, case_dir: Path) -> None:
    """复制单个 F-054 用例证据。"""
    copy_if_exists(server_dir / "logs" / "latest.log", case_dir / "logs" / "latest.log")
    plugin_dir = server_dir / "plugins" / "BLWorldTrashCan"
    copy_if_exists(plugin_dir / "cleanup.yml", case_dir / "config" / "cleanup-after-patch.yml")
    copy_if_exists(plugin_dir / "config.yml", case_dir / "config" / "config.yml")


def copy_if_exists(source: Path, target: Path) -> None:
    """复制可能存在的文件。"""
    if source.is_file():
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)


def write_readme(evidence_dir: Path, summary: dict) -> None:
    """写入 F-054 证据说明。"""
    lines = [
        "# F-054 船内实体保护专项验收",
        "",
        "- 被测插件: `dist/BLWorldTrashCan-universal.jar`",
        "- SHA256: `" + summary["jarSha256"] + "`",
        "- 验收方式: 真实服务端启动 + 临时 Bukkit 夹具生成船内牛/普通牛 + 正式 `/blwtc clear true`",
        "- 通过标准: 船内牛仍存在且仍在船内，普通牛被清理",
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
            "- 配置证据: `" + result["id"] + "/config/cleanup-after-patch.yml`",
            "",
        ])
    (evidence_dir / "README.md").write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")


def main() -> int:
    """执行 F-054 船内实体保护专项矩阵。"""
    ensure_inputs()
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    run_root = BUILD_ROOT / timestamp
    evidence_dir = EVIDENCE_ROOT / ("boat-entity-protection-" + timestamp)
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
    log("F-054 船内实体保护专项完成: " + str(evidence_dir))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
