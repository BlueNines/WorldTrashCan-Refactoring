import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import time
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw

import run_cleanup_guard_visual_matrix as guard
import run_direct_remove_world_visual_matrix as direct
import run_moving_items_visual_matrix as moving
import run_rgb_external_server_matrix as external
import run_rgb_visual_matrix as base


RUN_REPO = Path.cwd() if (Path.cwd() / "pom.xml").is_file() else base.REPO
EVIDENCE_ROOT = RUN_REPO / "docs" / "test-evidence"
BUILD_ROOT = RUN_REPO / "build" / "filled-shulker-visual-matrix"
JAVAC17 = (moving.JAVAC17 if moving.JAVAC17.is_file()
           else Path(r"C:\Program Files\Java\jdk-21\bin\javac.exe"))
JAR17 = (moving.JAR17 if moving.JAR17.is_file()
         else Path(r"C:\Program Files\Java\jdk-21\bin\jar.exe"))
BUKKIT_API_JAR = moving.BUKKIT_API_JAR
if not base.JAVA17.is_file():
    base.JAVA17 = Path(r"C:\Program Files\Java\jdk-21\bin\java.exe")
FIXTURE_NAME = "WtcFilledShulkerFixture"
FIXTURE_JAR_NAME = FIXTURE_NAME + ".jar"
TARGET_CASE_IDS = ["managed_paper1122", "external_folia1218"]
EXISTING_MODERN_CLIENT_CACHE = Path(r"E:\server_work\client\.minecraft")


def ensure_authlib_social_patch_compat(libraries: list[Path]) -> Path:
    """为测试使用的旧版和 1.21.8 authlib 创建空兼容补丁。"""
    if any("authlib-1.5.25.jar" in library.name or "authlib-6.0.58.jar" in library.name
           for library in libraries):
        target = base.CLIENT_CACHE / "patches" / "authlib-social-patch-empty.jar"
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.is_file():
            with zipfile.ZipFile(target, "w"):
                pass
        return target
    return base._ORIGINAL_ENSURE_AUTHLIB_SOCIAL_PATCH(libraries)


if not hasattr(base, "_ORIGINAL_ENSURE_AUTHLIB_SOCIAL_PATCH"):
    base._ORIGINAL_ENSURE_AUTHLIB_SOCIAL_PATCH = base.ensure_authlib_social_patch
    base.ensure_authlib_social_patch = ensure_authlib_social_patch_compat


if not hasattr(base, "_ORIGINAL_VERSION_DATA"):
    base._ORIGINAL_VERSION_DATA = base.version_data


if not hasattr(base, "_ORIGINAL_CHOOSE_SUBST_DRIVE"):
    base._ORIGINAL_CHOOSE_SUBST_DRIVE = base.choose_subst_drive


def read_existing_modern_version_data(version: str) -> dict:
    """读取本机已准备好的现代客户端版本清单，避免重复下载。"""
    path = EXISTING_MODERN_CLIENT_CACHE / "versions" / version / (version + ".json")
    if not path.is_file():
        return base._ORIGINAL_VERSION_DATA(version)
    return base.read_json(path)


def prepare_client_cache(version: str) -> None:
    """按客户端版本选择本机完整缓存并切换版本清单读取方式。"""
    if version == "1.21.8" and EXISTING_MODERN_CLIENT_CACHE.is_dir():
        base.CLIENT_CACHE = EXISTING_MODERN_CLIENT_CACHE
        base.version_data = read_existing_modern_version_data
        return
    base.CLIENT_CACHE = base.BUILD_ROOT / "client-cache"
    base.version_data = base._ORIGINAL_VERSION_DATA


def choose_subst_drive_compat(target: Path) -> Path:
    """为现代客户端选择指向当前缓存的新 ASCII 盘符，避免复用旧 R 盘。"""
    if target.resolve() != EXISTING_MODERN_CLIENT_CACHE.resolve():
        return base._ORIGINAL_CHOOSE_SUBST_DRIVE(target)
    target.mkdir(parents=True, exist_ok=True)
    sentinel = target / ".wtc-rgb-client-cache"
    sentinel.write_text("WorldListTrashCan RGB client cache\n", encoding="utf-8")
    for drive in ["S:", "T:", "U:"]:
        root = Path(drive + "\\")
        if root.exists():
            if (root / sentinel.name).is_file():
                try:
                    if (root / sentinel.name).read_text(encoding="utf-8") == sentinel.read_text(encoding="utf-8"):
                        return root
                except OSError:
                    pass
            continue
        subprocess.run(["cmd", "/c", "subst", drive, str(target)], check=True)
        return root
    raise RuntimeError("没有可用的现代客户端 ASCII 盘符")


base.choose_subst_drive = choose_subst_drive_compat


FIXTURE_SOURCE = r'''
package ai.wtc.fixture;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;

/** WorldListTrashCan 潜影盒扫地保护验收夹具。 */
public final class FilledShulkerFixturePlugin extends JavaPlugin implements CommandExecutor {
    /** 注册潜影盒验收夹具命令。 */
    @Override
    public void onEnable() {
        getCommand("shulkerfixture").setExecutor(this);
    }

    /** 分派生成潜影盒掉落物命令。 */
    @Override
    public boolean onCommand(final CommandSender sender, Command command, String label, final String[] args) {
        if (args.length < 2 || !"spawn".equalsIgnoreCase(args[0])) {
            sender.sendMessage("AI_SHULKER_FIXTURE_USAGE spawn <玩家> <filled|empty>");
            return true;
        }
        final Player player = Bukkit.getPlayer(args[1]);
        final boolean filled = args.length > 2 && "filled".equalsIgnoreCase(args[2]);
        runInPlayerRegion(player, new Runnable() {
            /** 在玩家所在合法区域生成潜影盒掉落物。 */
            @Override
            public void run() {
                if (player == null) {
                    sender.sendMessage("AI_SHULKER_FIXTURE_RESULT success=false");
                    return;
                }
                ItemStack shulker = createShulker(filled);
                Location location = player.getLocation().clone().add(0.0, 1.0, 0.0);
                Item item = player.getWorld().dropItem(location, shulker);
                item.setPickupDelay(32767);
                item.setGravity(false);
                item.setVelocity(new Vector(0.0, 0.0, 0.0));
                sender.sendMessage("AI_SHULKER_FIXTURE_RESULT success=true filled=" + filled
                        + " amount=" + shulker.getAmount());
            }
        });
        return true;
    }

    /** 创建指定内容状态的潜影盒物品。 */
    private ItemStack createShulker(boolean filled) {
        ItemStack shulker = new ItemStack(Material.WHITE_SHULKER_BOX, 1);
        ItemMeta itemMeta = shulker.getItemMeta();
        if (!(itemMeta instanceof BlockStateMeta)) {
            throw new IllegalStateException("潜影盒没有 BlockStateMeta");
        }
        BlockState state = ((BlockStateMeta) itemMeta).getBlockState();
        if (!(state instanceof Container)) {
            throw new IllegalStateException("潜影盒物品没有可读的内嵌库存");
        }
        if (filled) {
            Inventory inventory = ((Container) state).getInventory();
            inventory.setItem(0, new ItemStack(Material.STONE, 32));
        }
        ((BlockStateMeta) itemMeta).setBlockState(state);
        shulker.setItemMeta(itemMeta);
        return shulker;
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
}
'''


FIXTURE_PLUGIN_YML = """name: WtcFilledShulkerFixture
version: 1.0.0
main: ai.wtc.fixture.FilledShulkerFixturePlugin
folia-supported: true
commands:
  shulkerfixture:
    description: WorldListTrashCan filled shulker cleanup fixture
    usage: /shulkerfixture spawn <player> <filled|empty>
"""


def log(message: str) -> None:
    """输出带时间戳的脚本日志。"""
    print(time.strftime("[%H:%M:%S] ") + message, flush=True)


def write_json(path: Path, data) -> None:
    """按 UTF-8 写入 JSON 证据。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2, default=str), encoding="utf-8")


def selected_cases(case_id: str | None) -> list[dict]:
    """选择 Paper 1.12.2 与 Folia 1.21.8 的 universal 整包用例。"""
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
    raise RuntimeError("未知潜影盒保护测试用例: " + case_id)


def build_fixture(run_root: Path) -> Path:
    """编译 Java 8 兼容的跨版本潜影盒验收夹具。"""
    source_dir = run_root / "fixture-src" / "ai" / "wtc" / "fixture"
    classes_dir = run_root / "fixture-classes"
    resources_dir = run_root / "fixture-resources"
    fixture_jar = run_root / FIXTURE_JAR_NAME
    source_dir.mkdir(parents=True, exist_ok=True)
    classes_dir.mkdir(parents=True, exist_ok=True)
    resources_dir.mkdir(parents=True, exist_ok=True)
    source = source_dir / "FilledShulkerFixturePlugin.java"
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


def configure_shulker(case: dict, enabled: bool) -> Path:
    """写入本轮潜影盒保护配置并保持其它测试配置不变。"""
    direct.write_test_config(case, [])
    data_dir = Path(case["serverDir"]) / "plugins" / "WorldListTrashCan"
    cleanup = data_dir / "cleanup.yml"
    text = cleanup.read_text(encoding="utf-8", errors="replace")
    if "filled-shulker-boxes:" not in text:
        text = text.rstrip() + (
            "\n\n# 本轮测试临时补入装有物品潜影盒保护配置。\n"
            "filled-shulker-boxes:\n"
            "  enabled: false\n"
        )
    text = external.update_yaml_scalars(text, {
        "filled-shulker-boxes.enabled": "true" if enabled else "false",
    })
    cleanup.write_text(text, encoding="utf-8")
    return cleanup


def kill_items_command(case: dict) -> str:
    """返回清理所有验收掉落物的跨版本命令。"""
    if guard.is_legacy(case):
        return "minecraft:kill @e[type=Item]"
    return "minecraft:kill @e[type=minecraft:item]"


def remaining_marker_command(case: dict) -> str:
    """返回检查潜影盒掉落物是否仍存在的跨版本命令。"""
    if guard.is_legacy(case):
        return "execute @e[type=Item,c=1] ~ ~ ~ say AI_WTC_FILLED_SHULKER_REMAINS"
    return "execute if entity @e[type=minecraft:item,limit=1] run say AI_WTC_FILLED_SHULKER_REMAINS"


def spawn_fixture_item(case: dict, username: str, process, command_log: Path, state: str) -> None:
    """通过 Java 8 跨版本夹具生成空或装有物品的潜影盒。"""
    guard.run_console(
        process, command_log, "shulkerfixture spawn " + username + " " + state,
        1.0 if guard.is_folia(case) else 0.3,
    )


def wait_client_stats(stdout_path: Path, offset: int, expected: dict,
                      timeout: float = 15.0) -> tuple[str, dict | None]:
    """等待真实客户端统计输出达到预期。"""
    return moving.wait_client_stats(stdout_path, offset, expected, timeout)


def capture_phase(case: dict, client_case: dict, username: str, process, game_dir: Path, run_dir: Path,
                  server_log: Path, command_log: Path, phase: str, enabled: bool,
                  state: str, expected: dict, should_remain: bool) -> dict:
    """执行一轮潜影盒开关、内容状态和正式扫地组合验收。"""
    server_offset = external.log_text_offset(server_log)
    spawn_fixture_item(case, username, process, command_log, state)
    clear_shot = guard.send_command_and_screenshot(
        client_case, game_dir, run_dir, "/wtc clear true", phase + "-clear",
        4.5 if guard.is_folia(case) else 2.2,
    )
    if guard.is_folia(case):
        time.sleep(4.0)
    server_text = guard.wait_server_marker(
        server_log, server_offset,
        ["itemsRouted=" + str(expected["routed"]),
         "itemsRemoved=" + str(expected["removed"]),
         "itemsSkipped=" + str(expected["skipped"])],
        25.0,
    )
    stdout_path = run_dir / "logs" / (client_case["id"] + "-client-stdout.log")
    stats_offset = external.log_text_offset(stdout_path)
    stats_shot = guard.send_command_and_screenshot(
        client_case, game_dir, run_dir, "/wtc stats", phase + "-stats", 2.0,
    )
    client_text, parsed = wait_client_stats(stdout_path, stats_offset, {
        "routed": expected["routed"],
        "world": expected["world"],
        "personal": expected["personal"],
        "global": expected["global"],
        "removed": expected["removed"],
    })
    remaining_offset = external.log_text_offset(server_log)
    guard.run_console(process, command_log, remaining_marker_command(case), 0.8)
    remaining_excerpt = guard.wait_server_marker(
        server_log, remaining_offset,
        ["AI_WTC_FILLED_SHULKER_REMAINS"] if should_remain else [],
        3.0,
    )
    remaining = "AI_WTC_FILLED_SHULKER_REMAINS" in remaining_excerpt
    passed = parsed == {
        "routed": expected["routed"],
        "world": expected["world"],
        "personal": expected["personal"],
        "global": expected["global"],
        "removed": expected["removed"],
    } and remaining == should_remain
    return {
        "phase": phase,
        "enabled": enabled,
        "state": state,
        "expected": expected,
        "actual": parsed,
        "remainingAfterClear": remaining,
        "status": "PASS" if passed else "FAIL",
        "serverExcerpt": (server_text + "\n" + remaining_excerpt)[-2800:],
        "clientExcerpt": client_text[-1800:],
        "clientScreenshots": [guard.screenshot_info(clear_shot), guard.screenshot_info(stats_shot)],
    }


def render_server_screenshot(case: dict, result: dict, config_text: str, target: Path) -> Path:
    """把配置、三轮统计和服务端日志渲染为证据图。"""
    target.parent.mkdir(parents=True, exist_ok=True)
    lines = [case["label"] + " 装有物品潜影盒保护服务端证据", "", "配置快照:"]
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
        rest = str(line)
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
    """运行单个服务端的潜影盒保护三轮真实客户端验收。"""
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
        log("开始潜影盒保护用例 " + case["id"] + " / " + case["label"])
        data_dir = Path(case["serverDir"]) / "plugins" / "WorldListTrashCan"
        backup_dir = run_dir / "logs" / "file-backup"
        for relative in ("cleanup.yml", "trash.yml", "data/worlds.yml"):
            backups.append(guard.backup_file(data_dir / relative, backup_dir / Path(relative).parent))
        fixture_target = Path(case["serverDir"]) / "plugins" / fixture_jar.name
        backups.append(guard.backup_file(fixture_target, backup_dir / "plugins"))
        shutil.copy2(fixture_jar, fixture_target)
        process = external.launch_server(case, run_dir)
        prepared = prepared_clients[case["version"]]
        client_case = dict(case)
        client_case["id"] = case["id"] + "_client_" + run_dir.name.rsplit("-", 1)[-1]
        client, username, game_dir = base.launch_client(client_case, prepared, run_dir)
        base.ACTIVE_CLIENT_PID = client.pid
        result["username"] = username
        external.wait_player_online(case, username, server_log)
        guard.setup_player(case, username, process, command_log)

        configure_shulker(case, False)
        guard.reload_plugin(process, command_log)
        guard.run_console(process, command_log, "wtc debugworldtrash " + username, 2.0)
        if guard.is_folia(case):
            time.sleep(2.5)
        chest = direct.parse_debug_chest(server_log)
        if chest is None:
            raise RuntimeError("没有创建出可用世界垃圾桶")

        result["phases"].append(capture_phase(
            case, client_case, username, process, game_dir, run_dir, server_log, command_log,
            "filled-shulker-disabled-filled", False, "filled",
            {"routed": 1, "world": 1, "personal": 0, "global": 0, "removed": 0, "skipped": 0},
            False,
        ))
        guard.run_console(process, command_log, kill_items_command(case), 0.5)

        configure_shulker(case, True)
        guard.reload_plugin(process, command_log)
        result["phases"].append(capture_phase(
            case, client_case, username, process, game_dir, run_dir, server_log, command_log,
            "filled-shulker-enabled-filled", True, "filled",
            {"routed": 0, "world": 0, "personal": 0, "global": 0, "removed": 0, "skipped": 1},
            True,
        ))
        guard.run_console(process, command_log, kill_items_command(case), 0.5)

        result["phases"].append(capture_phase(
            case, client_case, username, process, game_dir, run_dir, server_log, command_log,
            "filled-shulker-enabled-empty", True, "empty",
            {"routed": 1, "world": 1, "personal": 0, "global": 0, "removed": 0, "skipped": 0},
            False,
        ))
        result["status"] = "PASS" if all(item["status"] == "PASS" for item in result["phases"]) else "FAIL"
        config_text = (data_dir / "cleanup.yml").read_text(encoding="utf-8", errors="replace")
        config_lines = config_text.splitlines()
        config_excerpt = "filled-shulker-boxes:\n  enabled: 未找到"
        for index, line in enumerate(config_lines):
            if line.strip() == "filled-shulker-boxes:":
                enabled_line = config_lines[index + 1] if index + 1 < len(config_lines) else "  enabled: 缺失"
                config_excerpt = line + "\n" + enabled_line
                break
        server_shot = render_server_screenshot(
            case, result, config_excerpt,
            run_dir / "server-screenshots" / (case["id"] + "-filled-shulker-server.png"),
        )
        result["serverScreenshot"] = {"path": str(server_shot), "sha256": hashlib.sha256(server_shot.read_bytes()).hexdigest()}
    except Exception as exc:
        result["status"] = "FAIL"
        result["error"] = repr(exc)
        log("潜影盒保护用例失败 " + case["id"] + ": " + repr(exc))
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
    sheet = Image.new("RGB", (columns * 500, max(1, rows) * 320), (2, 6, 23))
    for index, thumb in enumerate(thumbs):
        sheet.paste(thumb, ((index % columns) * 500, (index // columns) * 320))
    target = evidence_root / "filled-shulker-contact-sheet.png"
    sheet.save(target)
    return target


def write_readme(evidence_root: Path, results: list[dict], contact_sheet: Path) -> None:
    """写入本轮潜影盒保护证据说明。"""
    lines = [
        "# 扫地装有物品潜影盒保护真实客户端专项验收",
        "",
        "- 被测产物：`dist/WorldListTrashCan-universal.jar`。",
        "- 测试范围：只验证扫地阶段的装有物品潜影盒保护，不混入事件监听或其它物品保护。",
        "- 默认关闭轮：装有物品潜影盒应正常进入世界垃圾桶。",
        "- 开启保护轮：装有物品潜影盒应保持在地面，路由、删除均为 0。",
        "- 空潜影盒轮：保护开启时仍应按正常规则进入世界垃圾桶。",
        "- 范围边界：只检查掉落物实体携带的 ItemStack，不读取世界中放置的潜影盒方块。",
        "- 联系表：`" + contact_sheet.name + "`。",
        "",
    ]
    for result in results:
        lines.extend([
            "## " + result["label"],
            "",
            "- 结果：`" + result["status"] + "`。",
            "- 客户端、服务端和完整日志：`" + result["id"] + "/`。",
            "",
        ])
    (evidence_root / "README.md").write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    """运行潜影盒保护跨平台真实客户端矩阵。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", default="")
    args = parser.parse_args()
    run_id = "filled-shulker-visual-" + time.strftime("%Y%m%d-%H%M%S")
    evidence_root = EVIDENCE_ROOT / run_id
    evidence_root.mkdir(parents=True, exist_ok=True)
    fixture_jar = build_fixture(BUILD_ROOT / run_id)
    cases = selected_cases(args.case or None)
    prepared_clients = {}
    results = []
    for case in cases:
        prepare_client_cache(case["version"])
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
    log("潜影盒保护矩阵完成: total=" + str(len(results)) + " failed=" + str(len(failed))
        + " evidence=" + str(evidence_root))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
