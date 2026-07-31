import argparse
import json
import hashlib
import shutil
import subprocess
import sys
import time
from pathlib import Path

from PIL import Image, ImageDraw

import run_cleanup_guard_visual_matrix as guard
import run_direct_remove_world_visual_matrix as direct
import run_rgb_external_server_matrix as external
import run_rgb_visual_matrix as base


RUN_REPO = Path.cwd() if (Path.cwd() / "pom.xml").is_file() else base.REPO
EVIDENCE_ROOT = RUN_REPO / "docs" / "test-evidence"
BUILD_ROOT = RUN_REPO / "build" / "moving-items-visual-matrix"
JAVAC17 = RUN_REPO / "build" / "tools" / "jdk-17.0.19+10" / "bin" / "javac.exe"
JAR17 = RUN_REPO / "build" / "tools" / "jdk-17.0.19+10" / "bin" / "jar.exe"
LOCAL_BUKKIT_API_JAR = RUN_REPO / "build" / "tools" / "spigot-api-1.12.2-R0.1-SNAPSHOT.jar"
BUKKIT_API_JAR = (LOCAL_BUKKIT_API_JAR if LOCAL_BUKKIT_API_JAR.is_file()
                  else Path.home() / ".m2" / "repository" / "org" / "spigotmc" / "spigot-api"
                  / "1.12.2-R0.1-SNAPSHOT" / "spigot-api-1.12.2-R0.1-SNAPSHOT.jar")
FIXTURE_NAME = "WtcMovingItemFixture"
FIXTURE_JAR_NAME = FIXTURE_NAME + ".jar"
TARGET_CASE_IDS = ["managed_paper1122", "external_folia1218"]
TEST_AMOUNT = 5


FIXTURE_SOURCE = r'''
package ai.wtc.fixture;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;

/** WorldListTrashCan 移动物品扫地验收夹具。 */
public final class MovingItemFixturePlugin extends JavaPlugin implements CommandExecutor {
    /** 注册移动物品夹具命令。 */
    @Override
    public void onEnable() {
        getCommand("movingfixture").setExecutor(this);
    }

    /** 分派生成或清理测试掉落物命令。 */
    @Override
    public boolean onCommand(final CommandSender sender, Command command, String label, final String[] args) {
        if (args.length == 0) {
            sender.sendMessage("AI_MOVING_FIXTURE_USAGE spawn <玩家> <moving|stationary>|cleanup");
            return true;
        }
        Player player = args.length > 1 ? Bukkit.getPlayer(args[1]) : sender instanceof Player ? (Player) sender : null;
        final String action = args[0].toLowerCase();
        final boolean moving = args.length > 2 && "moving".equalsIgnoreCase(args[2]);
        runInPlayerRegion(player, new Runnable() {
            /** 在玩家所在合法区域生成或清理掉落物。 */
            @Override
            public void run() {
                if ("cleanup".equals(action)) {
                    cleanup();
                    sender.sendMessage("AI_MOVING_FIXTURE_CLEANUP done=true");
                    return;
                }
                if (!"spawn".equals(action) || player == null) {
                    sender.sendMessage("AI_MOVING_FIXTURE_RESULT success=false");
                    return;
                }
                Location location = player.getLocation().clone().add(0.0, 1.0, 0.0);
                Item item = player.getWorld().dropItem(location, new ItemStack(Material.STONE, 5));
                item.setPickupDelay(32767);
                item.setGravity(false);
                item.setVelocity(moving ? new Vector(0.0, 0.4, 0.0) : new Vector(0.0, 0.0, 0.0));
                sender.sendMessage("AI_MOVING_FIXTURE_RESULT success=true moving=" + moving);
            }
        });
        return true;
    }

    /** 在普通主线程或 Folia 玩家所在 Region 中执行动作。 */
    private void runInPlayerRegion(Player player, Runnable action) {
        if (player == null || !isRegionThreaded()) {
            action.run();
            return;
        }
        try {
            Object scheduler = Bukkit.class.getMethod("getRegionScheduler").invoke(null);
            Method execute = scheduler.getClass().getMethod("execute", Plugin.class, World.class,
                    int.class, int.class, Runnable.class);
            Location location = player.getLocation();
            execute.invoke(scheduler, this, player.getWorld(), location.getBlockX() >> 4,
                    location.getBlockZ() >> 4, action);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("无法分派 Folia 玩家区域任务", exception);
        }
    }

    /** 判断当前服务端是否提供 Folia 区域调度 API。 */
    private boolean isRegionThreaded() {
        try {
            Class.forName("io.papermc.paper.threadedregions.scheduler.FoliaRegionScheduler", false,
                    getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    /** 清理夹具自己生成的掉落物。 */
    private void cleanup() {
        for (World world : Bukkit.getWorlds()) {
            for (org.bukkit.entity.Entity entity : world.getEntities()) {
                if (entity instanceof Item) {
                    entity.remove();
                }
            }
        }
    }
}
'''


FIXTURE_PLUGIN_YML = """name: WtcMovingItemFixture
version: 1.0.0
main: ai.wtc.fixture.MovingItemFixturePlugin
folia-supported: true
commands:
  movingfixture:
    description: WorldListTrashCan moving item cleanup fixture
    usage: /movingfixture spawn <player> <moving|stationary>|cleanup
"""


def log(message: str) -> None:
    """输出带时间戳的脚本日志。"""
    print(time.strftime("[%H:%M:%S] ") + message, flush=True)


def write_json(path: Path, data) -> None:
    """按 UTF-8 写入 JSON 证据。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(to_json_value(data), ensure_ascii=False, indent=2), encoding="utf-8")


def to_json_value(value):
    """把 Path 等对象转换为 JSON 可写值。"""
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, list):
        return [to_json_value(item) for item in value]
    if isinstance(value, dict):
        return {key: to_json_value(item) for key, item in value.items()}
    return value


def selected_cases(case_id: str | None) -> list[dict]:
    """选择 Paper 1.12.2 与 Folia 1.21.8 通用整包用例。"""
    cases = []
    for wanted in TARGET_CASE_IDS:
        for item in external.EXTERNAL_MATRIX:
            if item["id"] == wanted:
                cases.append(external.universal_case(item))
                break
    if not case_id:
        return cases
    for item in cases:
        if case_id in (item["id"], item.get("sourceId", ""), item["label"], item["version"]):
            return [item]
    raise RuntimeError("未知移动物品测试用例: " + case_id)


def build_fixture(run_root: Path) -> Path:
    """编译 Java 8 兼容的跨版本移动物品夹具。"""
    source_dir = run_root / "fixture-src" / "ai" / "wtc" / "fixture"
    classes_dir = run_root / "fixture-classes"
    resources_dir = run_root / "fixture-resources"
    fixture_jar = run_root / FIXTURE_JAR_NAME
    source_dir.mkdir(parents=True, exist_ok=True)
    classes_dir.mkdir(parents=True, exist_ok=True)
    resources_dir.mkdir(parents=True, exist_ok=True)
    source = source_dir / "MovingItemFixturePlugin.java"
    source.write_text(FIXTURE_SOURCE, encoding="utf-8")
    (resources_dir / "plugin.yml").write_text(FIXTURE_PLUGIN_YML, encoding="utf-8")
    subprocess.run([
        str(JAVAC17), "--release", "8", "-encoding", "UTF-8",
        "-cp", str(BUKKIT_API_JAR), "-d", str(classes_dir), str(source),
    ], cwd=run_root, check=True)
    subprocess.run([
        str(JAR17), "cf", str(fixture_jar),
        "-C", str(classes_dir), ".", "-C", str(resources_dir), ".",
    ], cwd=run_root, check=True)
    return fixture_jar


def configure_moving_items(case: dict, enabled: bool) -> Path:
    """写入本轮扫地和垃圾桶测试配置，返回 cleanup.yml 路径。"""
    direct.write_test_config(case, [])
    data_dir = Path(case["serverDir"]) / "plugins" / "WorldListTrashCan"
    cleanup = data_dir / "cleanup.yml"
    text = cleanup.read_text(encoding="utf-8", errors="replace")
    if "moving-items:" not in text:
        text = text.rstrip() + (
            "\n\n# 本轮测试临时补入移动物品保护配置。\n"
            "moving-items:\n"
            "  enabled: false\n"
            "  minimum-speed: 0.01\n"
        )
    text = external.update_yaml_scalars(text, {
        "moving-items.enabled": "true" if enabled else "false",
        "moving-items.minimum-speed": "0.01",
    })
    cleanup.write_text(text, encoding="utf-8")
    return cleanup


def motion_command(case: dict) -> str:
    """返回兼容目标服务端版本的掉落物持续移动命令。"""
    if guard.is_legacy(case):
        return "entitydata @e[type=Item,c=1] {Motion:[3.0d,0.0d,0.0d],NoGravity:1b}"
    return "data merge entity @e[type=item,limit=1] {Motion:[3.0d,0.0d,0.0d],NoGravity:1b}"


def remaining_marker_command(case: dict) -> str:
    """返回检查扫地后移动掉落物是否仍存在的命令。"""
    if guard.is_legacy(case):
        return "execute @e[type=Item,c=1] ~ ~ ~ say AI_WTC_MOVING_ITEM_REMAINS"
    return "execute if entity @e[type=minecraft:item,limit=1] run say AI_WTC_MOVING_ITEM_REMAINS"


def kill_items_command(case: dict) -> str:
    """返回清理测试夹具残留掉落物的命令。"""
    if guard.is_legacy(case):
        return "minecraft:kill @e[type=Item]"
    return "minecraft:kill @e[type=minecraft:item]"


def wait_client_stats(stdout_path: Path, offset: int, expected: dict,
                      timeout: float = 15.0) -> tuple[str, dict | None]:
    """等待客户端统计输出达到预期。"""
    deadline = time.time() + timeout
    latest = ""
    parsed = None
    while time.time() < deadline:
        latest = external.read_text_since(stdout_path, offset)
        parsed = direct.parse_stats(latest)
        if parsed == expected:
            return latest, parsed
        time.sleep(0.4)
    return latest, parsed


def spawn_fixture_item(case: dict, username: str, process, command_log: Path, moving: bool) -> None:
    """通过 Java 8 跨版本夹具生成指定运动状态的掉落物。"""
    state = "moving" if moving else "stationary"
    guard.run_console(
        process,
        command_log,
        "movingfixture spawn " + username + " " + state,
        1.0 if guard.is_folia(case) else 0.3,
    )


def capture_phase(case: dict, username: str, process, game_dir: Path, run_dir: Path,
                  server_log: Path, command_log: Path, phase: str,
                  enabled: bool, moving: bool, expected: dict) -> dict:
    """执行一个移动物品开关、移动状态和正式扫地组合。"""
    server_offset = external.log_text_offset(server_log)
    spawn_fixture_item(case, username, process, command_log, moving)
    clear_shot = guard.send_command_and_screenshot(
        case,
        game_dir,
        run_dir,
        "/wtc clear true",
        phase + "-clear",
        4.5 if guard.is_folia(case) else 2.2,
    )
    if guard.is_folia(case):
        time.sleep(4.0)
    server_text = guard.wait_server_marker(
        server_log,
        server_offset,
        ["itemsRouted=" + str(expected["routed"]), "itemsRemoved=" + str(expected["removed"])],
        25.0,
    )
    stdout_path = run_dir / "logs" / (case["id"] + "-client-stdout.log")
    stats_offset = external.log_text_offset(stdout_path)
    stats_shot = guard.send_command_and_screenshot(
        case,
        game_dir,
        run_dir,
        "/wtc stats",
        phase + "-stats",
        2.0,
    )
    client_text, parsed = wait_client_stats(stdout_path, stats_offset, expected)
    remaining = False
    remaining_excerpt = ""
    if enabled and moving:
        remaining_offset = external.log_text_offset(server_log)
        guard.run_console(process, command_log, remaining_marker_command(case), 0.8)
        remaining_excerpt = guard.wait_server_marker(
            server_log, remaining_offset, ["AI_WTC_MOVING_ITEM_REMAINS"], 3.0
        )
        remaining = "AI_WTC_MOVING_ITEM_REMAINS" in remaining_excerpt
    passed = parsed == expected and (not enabled or not moving or remaining)
    return {
        "phase": phase,
        "enabled": enabled,
        "moving": moving,
        "expected": expected,
        "actual": parsed,
        "remainingAfterClear": remaining,
        "status": "PASS" if passed else "FAIL",
        "serverExcerpt": (server_text + "\n" + remaining_excerpt)[-2600:],
        "clientExcerpt": client_text[-1800:],
        "clientScreenshots": [guard.screenshot_info(clear_shot), guard.screenshot_info(stats_shot)],
    }


def render_server_screenshot(case: dict, result: dict, config_text: str, target: Path) -> Path:
    """把移动配置、三轮统计和服务端日志渲染为证据图。"""
    target.parent.mkdir(parents=True, exist_ok=True)
    lines = [case["label"] + " 扫地移动物品保护服务端证据", "", "配置快照:"]
    lines.extend(config_text.splitlines())
    for phase in result.get("phases", []):
        lines.extend([
            "",
            phase["phase"] + " expected=" + json.dumps(phase["expected"], ensure_ascii=False),
            phase["phase"] + " actual=" + json.dumps(phase["actual"], ensure_ascii=False),
            "remainingAfterClear=" + str(phase["remainingAfterClear"]),
            phase["serverExcerpt"],
        ])
    normalized_lines = []
    for line in lines:
        split_lines = str(line).splitlines()
        normalized_lines.extend(split_lines if split_lines else [""])
    wrapped = []
    for line in normalized_lines:
        rest = line
        while len(rest) > 112:
            wrapped.append(rest[:112])
            rest = "  " + rest[112:]
        wrapped.append(rest)
    width = 1560
    line_height = 25
    image = Image.new("RGB", (width, max(360, (len(wrapped) + 2) * line_height)), (15, 23, 42))
    draw = ImageDraw.Draw(image)
    used_font = guard.font()
    y = 18
    for index, line in enumerate(wrapped):
        draw.text((22, y), line, fill=(250, 204, 21) if index == 0 else (226, 232, 240), font=used_font)
        y += line_height
    image.save(target)
    return target


def run_case(case: dict, prepared_clients: dict, evidence_root: Path, fixture_jar: Path) -> dict:
    """运行单个服务端的移动物品保护三轮真实客户端验收。"""
    case = dict(case)
    run_dir = evidence_root / case["id"]
    run_dir.mkdir(parents=True, exist_ok=True)
    server_log = run_dir / "logs" / (case["id"] + "-server-console.log")
    command_log = run_dir / "logs" / (case["id"] + "-console-commands.log")
    process = None
    client = None
    backups = []
    chest = None
    result = {
        "id": case["id"],
        "label": case["label"],
        "version": case.get("displayVersion", case["version"]),
        "status": "FAIL",
        "artifact": external.artifact_summary_for_plugin(case),
        "phases": [],
    }
    try:
        log("开始移动物品保护用例 " + case["id"] + " / " + case["label"])
        data_dir = Path(case["serverDir"]) / "plugins" / "WorldListTrashCan"
        backup_dir = run_dir / "logs" / "file-backup"
        for relative in ("cleanup.yml", "trash.yml", "data/worlds.yml"):
            backups.append(guard.backup_file(data_dir / relative, backup_dir / Path(relative).parent))
        fixture_target = Path(case["serverDir"]) / "plugins" / fixture_jar.name
        backups.append(guard.backup_file(fixture_target, backup_dir / "plugins"))
        shutil.copy2(fixture_jar, fixture_target)
        process = external.launch_server(case, run_dir)
        prepared = prepared_clients[case["version"]]
        client, username, game_dir = base.launch_client(case, prepared, run_dir)
        base.ACTIVE_CLIENT_PID = client.pid
        result["username"] = username
        external.wait_player_online(case, username, server_log)
        guard.setup_player(case, username, process, command_log)

        configure_moving_items(case, False)
        guard.reload_plugin(process, command_log)
        guard.run_console(process, command_log, "wtc debugworldtrash " + username, 2.0)
        if guard.is_folia(case):
            time.sleep(2.5)
        chest = direct.parse_debug_chest(server_log)
        if chest is None:
            raise RuntimeError("没有创建出可用世界垃圾桶")

        disabled_moving = capture_phase(
            case, username, process, game_dir, run_dir, server_log, command_log,
            "moving-items-disabled-moving", False, True,
            {"routed": TEST_AMOUNT, "world": TEST_AMOUNT, "personal": 0,
             "global": 0, "removed": 0},
        )
        result["phases"].append(disabled_moving)
        guard.run_console(process, command_log, kill_items_command(case), 0.5)

        configure_moving_items(case, True)
        guard.reload_plugin(process, command_log)
        enabled_moving = capture_phase(
            case, username, process, game_dir, run_dir, server_log, command_log,
            "moving-items-enabled-moving", True, True,
            {"routed": 0, "world": 0, "personal": 0,
             "global": 0, "removed": 0},
        )
        result["phases"].append(enabled_moving)
        guard.run_console(process, command_log, kill_items_command(case), 0.5)

        enabled_stationary = capture_phase(
            case, username, process, game_dir, run_dir, server_log, command_log,
            "moving-items-enabled-stationary", True, False,
            {"routed": TEST_AMOUNT, "world": TEST_AMOUNT, "personal": 0,
             "global": 0, "removed": 0},
        )
        result["phases"].append(enabled_stationary)
        result["status"] = "PASS" if all(item["status"] == "PASS" for item in result["phases"]) else "FAIL"
        config_text = (data_dir / "cleanup.yml").read_text(encoding="utf-8", errors="replace")
        config_excerpt = "\n".join(
            line for line in config_text.splitlines()
            if "moving-items" in line or "minimum-speed" in line or "enabled: true" in line
        )
        server_shot = render_server_screenshot(
            case, result, config_excerpt,
            run_dir / "server-screenshots" / (case["id"] + "-moving-items-server.png"),
        )
        result["serverScreenshot"] = guard.screenshot_info(server_shot)
    except Exception as exc:
        result["status"] = "FAIL"
        result["error"] = repr(exc)
        log("移动物品保护用例失败 " + case["id"] + ": " + repr(exc))
    finally:
        if client is not None:
            external.stop_process(client)
        base.ACTIVE_CLIENT_PID = None
        if process is not None:
            direct.remove_debug_chest(process, command_log, chest)
            external.stop_process(process, "stop")
        guard.restore_backups(backups)
    write_json(run_dir / "result.json", result)
    return result


def make_contact_sheet(results: list[dict], evidence_root: Path) -> Path:
    """生成客户端和服务端证据联系表。"""
    screenshots = []
    for result in results:
        for phase in result.get("phases", []):
            screenshots.extend(Path(item["path"]) for item in phase.get("clientScreenshots", []))
        if result.get("serverScreenshot"):
            screenshots.append(Path(result["serverScreenshot"]["path"]))
    thumbs = []
    for path in screenshots:
        image = Image.open(path).convert("RGB")
        image.thumbnail((480, 278))
        canvas = Image.new("RGB", (500, 320), (15, 23, 42))
        canvas.paste(image, ((500 - image.width) // 2, 8))
        ImageDraw.Draw(canvas).text((10, 292), path.name[:64], fill=(226, 232, 240), font=guard.font())
        thumbs.append(canvas)
    columns = 2
    rows = (len(thumbs) + columns - 1) // columns
    sheet = Image.new("RGB", (columns * 500, rows * 320), (2, 6, 23))
    for index, thumb in enumerate(thumbs):
        sheet.paste(thumb, ((index % columns) * 500, (index // columns) * 320))
    target = evidence_root / "moving-items-contact-sheet.png"
    sheet.save(target)
    return target


def write_readme(evidence_root: Path, results: list[dict], contact_sheet: Path) -> None:
    """写入本轮移动物品保护证据说明。"""
    lines = [
        "# 扫地跳过移动物品真实客户端专项验收",
        "",
        "- 被测产物：`dist/WorldListTrashCan-universal.jar`",
        "- 测试范围：只验证扫地时的移动物品保护，不混入持续监听、移动历史表或其他业务功能。",
        "- 默认关闭移动轮：物品设置为移动状态，要求仍进入世界垃圾桶。",
        "- 开启移动轮：物品保持移动，要求本轮回收/删除均为 0，并由服务端标记确认物品仍存在。",
        "- 开启静止轮：物品停止移动，要求正常进入世界垃圾桶。",
        "- 联系表：`" + contact_sheet.name + "`",
        "",
    ]
    for result in results:
        lines.extend([
            "## " + result["label"],
            "",
            "- 结果：`" + result["status"] + "`",
            "- 客户端与服务端原始证据：`" + result["id"] + "/`",
            "",
        ])
    (evidence_root / "README.md").write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    """运行移动物品保护跨平台真实客户端矩阵。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", default="")
    args = parser.parse_args()
    run_id = "moving-items-visual-" + time.strftime("%Y%m%d-%H%M%S")
    evidence_root = EVIDENCE_ROOT / run_id
    evidence_root.mkdir(parents=True, exist_ok=True)
    fixture_jar = build_fixture(BUILD_ROOT / run_id)
    cases = selected_cases(args.case or None)
    prepared_clients = {}
    results = []
    for case in cases:
        prepared_clients.setdefault(case["version"], base.ensure_client(case["version"]))
        results.append(run_case(case, prepared_clients, evidence_root, fixture_jar))
        write_json(evidence_root / "summary.json", {"run": run_id, "results": results})
    contact_sheet = make_contact_sheet(results, evidence_root)
    write_readme(evidence_root, results, contact_sheet)
    summary = {
        "run": run_id,
        "results": results,
        "contactSheet": str(contact_sheet),
        "fixtureJar": str(fixture_jar),
        "fixtureSha256": hashlib.sha256(fixture_jar.read_bytes()).hexdigest(),
    }
    write_json(evidence_root / "summary.json", summary)
    failed = [item for item in results if item.get("status") != "PASS"]
    log("移动物品保护矩阵完成: total=" + str(len(results)) + " failed=" + str(len(failed))
        + " evidence=" + str(evidence_root))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
