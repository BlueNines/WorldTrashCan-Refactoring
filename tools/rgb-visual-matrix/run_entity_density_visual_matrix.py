import argparse
import hashlib
import json
import re
import shutil
import sys
import time
from pathlib import Path

from PIL import Image, ImageDraw

import run_rgb_external_server_matrix as external
import run_rgb_visual_matrix as base


BUILD_ROOT = base.REPO / "build" / "entity-density-visual-matrix"
EVIDENCE_ROOT = base.REPO / "docs" / "test-evidence"
TEST_ENTITY_NAME = "AI_BlWTC_DENSITY"
TARGET_CASE_IDS = [
    "external_paper12111",
    "external_folia1218",
    "external_cat1122",
    "external_spigot2612",
    "external_arclight1211",
    "external_banner1201",
]


def log(message: str) -> None:
    """输出带时间戳的脚本日志。"""
    print(time.strftime("[%H:%M:%S] ") + message, flush=True)


def write_json(path: Path, data) -> None:
    """按 UTF-8 写入 JSON 文件。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(to_json_value(data), ensure_ascii=False, indent=2), encoding="utf-8")


def to_json_value(value):
    """把 Path 等对象转换成 JSON 可写值。"""
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, list):
        return [to_json_value(item) for item in value]
    if isinstance(value, dict):
        return {key: to_json_value(item) for key, item in value.items()}
    return value


def selected_cases(case_id: str | None) -> list[dict]:
    """按参数返回本轮要测试的外部服务端。"""
    source = []
    for wanted in TARGET_CASE_IDS:
        for item in external.EXTERNAL_MATRIX:
            if item["id"] == wanted:
                source.append(external.universal_case(item))
                break
    if not case_id:
        return source
    for item in source:
        if case_id in (item["id"], item.get("sourceId", ""), item["label"], item["version"]):
            return [item]
    raise RuntimeError("未知密集实体截图用例: " + case_id)


def backup_file(target: Path, backup_dir: Path, label: str) -> dict:
    """备份单个文件并返回恢复信息。"""
    backup = backup_dir / label
    backup.parent.mkdir(parents=True, exist_ok=True)
    if target.is_file():
        shutil.copy2(target, backup)
        return {"target": target, "backup": backup, "existed": True}
    return {"target": target, "backup": backup, "existed": False}


def restore_backups(backups: list[dict]) -> None:
    """恢复本轮测试改动过的配置文件。"""
    for item in backups:
        target = Path(item["target"])
        backup = Path(item["backup"])
        if item.get("existed") and backup.is_file():
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(backup, target)
        elif not item.get("existed") and target.is_file():
            target.unlink()


def write_quiet_server_properties(case: dict) -> None:
    """关闭控制台命令广播，避免大量 summon 反馈刷爆真实客户端聊天。"""
    target = Path(case["serverDir"]) / "server.properties"
    values = {}
    if target.is_file():
        for line in target.read_text(encoding="utf-8", errors="replace").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                key, value = line.split("=", 1)
                values[key] = value
    values["broadcast-console-to-ops"] = "false"
    values["broadcast-rcon-to-ops"] = "false"
    values["enable-command-block"] = "false"
    target.write_text("\n".join(key + "=" + value for key, value in values.items()) + "\n", encoding="utf-8")


def entity_limit_config(enabled: bool, remove_count: int) -> str:
    """生成本轮测试专用实体密度配置。"""
    enabled_text = "true" if enabled else "false"
    safe_remove_count = max(1, remove_count)
    return (
        "# AI 自动化密集实体视觉测试临时配置。\n"
        "# 测试结束后脚本会恢复原 entity-limits.yml。\n"
        "world-limits:\n"
        "  enabled: false\n"
        "  ignored-worlds: []\n"
        "  defaults:\n"
        "    - entity: \"VILLAGER\"\n"
        "      max-count: 10\n"
        "scanner:\n"
        "  target-full-cycle-seconds: 30\n"
        "  scan-interval-ticks: 5\n"
        "  min-chunks-per-scan: 8\n"
        "  max-chunks-per-scan: 128\n"
        "  max-scan-millis-per-run: 20\n"
        "  remove-interval-ticks: 1\n"
        "  max-removes-per-run: 40\n"
        "  max-pending-removals: 1000\n"
        "  candidate-ttl-seconds: 60\n"
        "  max-candidate-retries: 2\n"
        "  max-dirty-chunks: 2048\n"
        "  stale-chunk-seconds: 120\n"
        "  max-index-entities: 10000\n"
        "  max-index-entities-per-chunk: 256\n"
        "  log-summary-seconds: 0\n"
        "gather-limits:\n"
        f"  enabled: {enabled_text}\n"
        "  drop-items: false\n"
        "  ignored-worlds: []\n"
        "  defaults:\n"
        "    - entity: \"COW\"\n"
        "      max-count: 8\n"
        "      radius: 16\n"
        f"      remove-count: {safe_remove_count}\n"
    )


def write_entity_config(case: dict, enabled: bool, remove_count: int) -> Path:
    """写入本轮测试专用 entity-limits.yml。"""
    target = Path(case["serverDir"]) / "plugins" / "BlWorldTrashCan" / "entity-limits.yml"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(entity_limit_config(enabled, remove_count), encoding="utf-8")
    return target


def write_quiet_cleanup_config(case: dict) -> None:
    """关闭定时扫地，避免它抢占密集实体提示截图和聊天日志。"""
    target = Path(case["serverDir"]) / "plugins" / "BlWorldTrashCan" / "cleanup.yml"
    if not target.is_file():
        return
    original = target.read_text(encoding="utf-8", errors="replace")
    updated = external.update_yaml_scalars(original, {"interval-seconds": "0"})
    if updated != original:
        target.write_text(updated, encoding="utf-8")


def is_legacy(case: dict) -> bool:
    """判断当前用例是否为 1.12.2 命令语法。"""
    return str(case["version"]) == "1.12.2"


def is_folia(case: dict) -> bool:
    """判断当前用例是否运行在 Folia 服务端。"""
    label = str(case.get("label", "")).lower()
    source = str(case.get("sourceId", "")).lower()
    return "folia" in label or "folia" in source


def setup_commands(case: dict, username: str) -> list[str]:
    """生成测试场景初始化命令。"""
    if is_legacy(case):
        return [
            "deop " + username,
            "minecraft:gamerule sendCommandFeedback false",
            "minecraft:gamerule commandBlockOutput false",
            "minecraft:gamerule doMobSpawning false",
            "minecraft:gamerule maxEntityCramming 0",
            "minecraft:time set day",
            "minecraft:weather clear",
            "minecraft:gamemode 1 " + username,
            "minecraft:tp " + username + " 0 91 -8 0 15",
            "minecraft:fill -8 90 -12 8 95 12 air",
            "minecraft:fill -8 89 -12 8 89 12 glass",
            "minecraft:tp " + username + " 0 91 -8 0 15",
            "minecraft:execute " + username + " ~ ~ ~ minecraft:kill @e[type=Cow,name=" + TEST_ENTITY_NAME + ",r=64]",
        ]
    commands = [
        "deop " + username,
        "minecraft:gamerule sendCommandFeedback false",
        "minecraft:gamerule commandBlockOutput false",
        "minecraft:gamerule doMobSpawning false",
        "minecraft:gamerule maxEntityCramming 0",
        "minecraft:time set day",
        "minecraft:weather clear",
        "minecraft:gamemode creative " + username,
        "minecraft:tp " + username + " 0 91 -8 0 15",
        "minecraft:fill -8 90 -12 8 95 12 minecraft:air",
        "minecraft:fill -8 89 -12 8 89 12 minecraft:glass",
        "minecraft:tp " + username + " 0 91 -8 0 15",
    ]
    if not is_folia(case):
        commands.append("minecraft:kill @e[type=minecraft:cow,name=" + TEST_ENTITY_NAME + "]")
    return commands


def dense_entity_position(index: int | None) -> tuple[int, int, int]:
    """返回半径内的网格生成位置，避免测试实体被原版拥挤伤害提前杀死。"""
    if index is None:
        return 0, 90, 0
    grid_size = 9
    x = (index % grid_size) - 4
    z = ((index // grid_size) % grid_size) - 4
    return x, 90, z


def summon_command(case: dict, index: int | None = None) -> str:
    """返回单只测试 cow 的 summon 命令。"""
    x, y, z = dense_entity_position(index)
    if is_legacy(case):
        return 'minecraft:summon Cow ' + str(x) + " " + str(y) + " " + str(z) + ' {NoAI:1b,PersistenceRequired:1b,CustomName:"' + TEST_ENTITY_NAME + '"}'
    return "minecraft:summon minecraft:cow " + str(x) + " " + str(y) + " " + str(z) + " {NoAI:1b,PersistenceRequired:1b,CustomName:'{\"text\":\"" + TEST_ENTITY_NAME + "\"}'}"


def cleanup_command(case: dict, username: str) -> str:
    """返回测试结束清理剩余 cow 的命令。"""
    if is_legacy(case):
        return "minecraft:kill @e[type=Cow,name=" + TEST_ENTITY_NAME + "]"
    if is_folia(case):
        return ""
    return "minecraft:kill @e[type=minecraft:cow,name=" + TEST_ENTITY_NAME + "]"


def run_console(process, command_log: Path, command: str) -> None:
    """发送控制台命令并短暂等待服务端处理。"""
    external.send_console_command(process, command, command_log)
    time.sleep(0.12)


def run_setup(case: dict, username: str, process, command_log: Path) -> None:
    """初始化玩家位置、权限和测试场景。"""
    for command in setup_commands(case, username):
        run_console(process, command_log, command)
    time.sleep(1.0)


def spawn_dense_entities(case: dict, process, command_log: Path, count: int) -> None:
    """生成指定数量的密集测试 cow。"""
    for index in range(count):
        run_console(process, command_log, summon_command(case, index))
        if index % 20 == 19:
            time.sleep(0.3)
    time.sleep(1.0)


def parse_density(text: str) -> dict:
    """解析 debugdensity 文本中的关键数字。"""
    plain = external.strip_ansi(text)
    result = {"raw": text}
    patterns = {
        "indexed": r"索引 chunk/实体:\s*(\d+)\D+(\d+)",
        "pending": r"候选队列/去重:\s*(\d+)\D+(\d+)",
        "candidates": r"候选创建/取出/完成:\s*(\d+)\D+(\d+)\D+(\d+)",
        "failures": r"候选过期/重试/丢弃:\s*(\d+)\D+(\d+)\D+(\d+)",
        "removals": r"删除成功/跳过:\s*(\d+)\D+(\d+)",
    }
    for key, pattern in patterns.items():
        match = re.search(pattern, plain)
        if match:
            result[key] = [int(item) for item in match.groups()]
    if "indexed" not in result or "pending" not in result or "candidates" not in result or "removals" not in result:
        slash_groups = []
        for match in re.finditer(r"(\d+)\s*/\s*(\d+)(?:\s*/\s*(\d+))?", plain):
            slash_groups.append([int(item) for item in match.groups() if item is not None])
        if len(slash_groups) >= 8:
            result.setdefault("loaded", slash_groups[0])
            result.setdefault("indexed", slash_groups[1])
            result.setdefault("pending", slash_groups[2])
            result.setdefault("snapshots", slash_groups[3])
            result.setdefault("candidates", slash_groups[4])
            result.setdefault("failures", slash_groups[5])
            result.setdefault("removals", slash_groups[6])
            result.setdefault("pruned", slash_groups[7])
    return result


def wait_density_effect(case: dict, process, server_log: Path, command_log: Path) -> dict:
    """等待密集实体限制生效并返回最后一次 debugdensity。"""
    deadline = time.time() + 90
    last = {}
    while time.time() < deadline:
        offset = external.log_text_offset(server_log)
        external.send_console_command(process, "blwtc debugdensity", command_log)
        time.sleep(2.0)
        text = external.read_text_since(server_log, offset)
        parsed = parse_density(text)
        if parsed:
            last = parsed
        pending = parsed.get("pending", [999, 999])
        removals = parsed.get("removals", [0, 0])
        candidates = parsed.get("candidates", [0, 0, 0])
        if removals[0] > 0 and candidates[0] > 0 and pending[0] == 0 and pending[1] == 0:
            return {
                "status": "PASS",
                "density": parsed,
                "condition": "removed>0,candidates>0,pending=0",
            }
        time.sleep(1.0)
    return {
        "status": "FAIL",
        "density": last,
        "condition": "timeout",
    }


def wait_client_density_notice(client_log: Path, offset: int) -> dict:
    """等待客户端聊天日志出现正式密集实体清理提示。"""
    deadline = time.time() + 15
    markers = ["密集实体清理", "密集實體清理", "Dense entity cleanup", "Limpieza de entidades densas"]
    last_text = ""
    while time.time() < deadline:
        text = external.read_text_since(client_log, offset)
        if text:
            last_text = text
        for marker in markers:
            if marker in text:
                return {
                    "status": "PASS",
                    "marker": marker,
                    "excerpt": text[-1200:],
                }
        time.sleep(0.5)
    return {
        "status": "FAIL",
        "marker": "",
        "excerpt": last_text[-1200:],
    }


def validate_notice_remove_count(notice_check: dict, remove_count: int) -> dict:
    """校验正式提示中的单次清理数量没有超过 remove-count。"""
    limit = max(1, remove_count)
    text = str(notice_check.get("excerpt", ""))
    values = [int(match.group(1)) for match in re.finditer(r"已清理\s*(\d+)", text)]
    too_large = [value for value in values if value > limit]
    if not values:
        return {"status": "FAIL", "limit": limit, "values": values, "reason": "未解析到已清理数量"}
    if too_large:
        return {"status": "FAIL", "limit": limit, "values": values, "tooLarge": too_large}
    return {"status": "PASS", "limit": limit, "values": values}


def wait_client_debugdensity(client_log: Path, offset: int) -> dict:
    """等待客户端聊天日志出现 debugdensity 输出，证明玩家命令真的发出。"""
    deadline = time.time() + 15
    markers = ["实体密度扫描统计", "實體密度掃描統計", "Entity density scan", "Densidad de entidades"]
    last_text = ""
    while time.time() < deadline:
        text = external.read_text_since(client_log, offset)
        if text:
            last_text = text
        for marker in markers:
            if marker in text:
                return {
                    "status": "PASS",
                    "marker": marker,
                    "excerpt": text[-1200:],
                }
        time.sleep(0.4)
    return {
        "status": "FAIL",
        "marker": "",
        "excerpt": last_text[-1200:],
    }


def copy_named_screenshot(case: dict, game_dir: Path, run_dir: Path, suffix: str) -> Path:
    """截取当前游戏内 F2 截图并保存成稳定文件名。"""
    source = base.capture_internal_screenshot(case, game_dir, run_dir)
    target = run_dir / "screenshots" / (case["id"] + "-" + suffix + ".png")
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)
    return target


def ensure_ingame_view(case: dict) -> None:
    """尽量把客户端从暂停菜单切回正常游戏画面。"""
    hwnd = base.find_minecraft_window(case["version"])
    rect = base.focus_window(hwnd)
    base.pyautogui.press("esc")
    time.sleep(0.2)
    base.click_game(hwnd, rect, 0.50, 0.34)
    time.sleep(0.4)


def send_ingame_command(case: dict, game_dir: Path, run_dir: Path, command: str, suffix: str,
                        wait_seconds: float = 1.0) -> dict:
    """在正常游戏画面里输入玩家命令并保留 F2 截图。"""
    hwnd = base.find_minecraft_window(case["version"])
    ensure_ingame_view(case)
    base.send_chat_line_by_window_message(hwnd, command)
    time.sleep(wait_seconds)
    screenshot = copy_named_screenshot(case, game_dir, run_dir, suffix)
    return {
        "name": suffix,
        "command": command,
        "status": "PASS",
        "screenshot": str(screenshot),
        "brightness": base.image_brightness(Image.open(screenshot)),
    }


def screenshot_info(path: Path) -> dict:
    """返回截图文件基础校验信息。"""
    data = path.read_bytes()
    image = Image.open(path)
    return {
        "path": str(path),
        "size": path.stat().st_size,
        "sha256": hashlib.sha256(data).hexdigest(),
        "pngMagic": data[:8].hex(),
        "dimensions": list(image.size),
        "brightness": base.image_brightness(image),
    }


def artifact_summary(result: dict) -> dict:
    """生成单个用例的截图和日志文件摘要。"""
    files = []
    for key in ("beforeScreenshot", "notifyScreenshot", "afterScreenshot", "failureScreenshot"):
        if result.get(key):
            files.append(screenshot_info(Path(result[key])))
    return {
        "id": result["id"],
        "label": result["label"],
        "status": result["status"],
        "screenshots": files,
        "artifact": result.get("artifact", {}),
    }


def run_case(case: dict, prepared_clients: dict, run_root: Path, spawn_count: int, remove_count: int) -> dict:
    """运行单个外部服务端密集实体截图测试。"""
    case = dict(case)
    case["runId"] = run_root.name
    run_dir = run_root / case["id"]
    run_dir.mkdir(parents=True, exist_ok=True)
    log("开始密集实体截图用例 " + case["id"] + " / " + case["label"])
    backups = []
    process = None
    client = None
    game_dir = None
    server_log = run_dir / "logs" / (case["id"] + "-server-console.log")
    command_log = run_dir / "logs" / (case["id"] + "-console-commands.log")
    result = {
        "id": case["id"],
        "sourceId": case.get("sourceId", ""),
        "label": case["label"],
        "version": case.get("displayVersion", case["version"]),
        "clientVersion": case["version"],
        "serverDir": str(case["serverDir"]),
        "plugin": case["plugin"],
        "spawnCount": spawn_count,
        "removeCount": max(1, remove_count),
        "status": "FAIL",
        "artifact": external.artifact_summary_for_plugin(case),
    }
    try:
        backup_dir = run_dir / "logs" / "config-backup"
        backups.append(backup_file(Path(case["serverDir"]) / "server.properties", backup_dir, "server.properties.before"))
        backups.append(backup_file(Path(case["serverDir"]) / "plugins" / "BlWorldTrashCan" / "entity-limits.yml", backup_dir, "entity-limits.yml.before"))
        backups.append(backup_file(Path(case["serverDir"]) / "plugins" / "BlWorldTrashCan" / "cleanup.yml", backup_dir, "cleanup.yml.before"))
        external.deploy_plugin(case)
        write_quiet_server_properties(case)
        write_quiet_cleanup_config(case)
        write_entity_config(case, False, remove_count)
        process = external.launch_server(case, run_dir)
        prepared = prepared_clients[case["version"]]
        client, username, game_dir = base.launch_client(case, prepared, run_dir)
        base.ACTIVE_CLIENT_PID = client.pid
        result["username"] = username
        result["clientPid"] = client.pid
        external.wait_player_online(case, username, server_log)
        offset = external.log_text_offset(server_log)
        run_console(process, command_log, "blwtc platform")
        external.wait_platform_command_accepted(server_log, offset)
        run_setup(case, username, process, command_log)
        spawn_dense_entities(case, process, command_log, spawn_count)
        ensure_ingame_view(case)
        before = copy_named_screenshot(case, game_dir, run_dir, "density-before-f2")
        result["beforeScreenshot"] = str(before)
        client_log = run_dir / "logs" / (case["id"] + "-client-stdout.log")
        client_notice_offset = external.log_text_offset(client_log)
        write_entity_config(case, True, remove_count)
        offset = external.log_text_offset(server_log)
        run_console(process, command_log, "blwtc reload")
        external.wait_command_markers(server_log, offset, ["[Message]"], 12, "blwtc reload")
        run_console(process, command_log, summon_command(case))
        density = wait_density_effect(case, process, server_log, command_log)
        result["densityCheck"] = density
        notice_check = wait_client_density_notice(client_log, client_notice_offset)
        result["noticeCheck"] = notice_check
        notice_count_check = validate_notice_remove_count(notice_check, remove_count)
        result["removeCountNoticeCheck"] = notice_count_check
        ensure_ingame_view(case)
        time.sleep(0.8)
        notify = copy_named_screenshot(case, game_dir, run_dir, "density-notify-f2")
        result["notifyScreenshot"] = str(notify)
        run_console(process, command_log, "op " + username)
        client_debug_offset = external.log_text_offset(client_log)
        after_command = send_ingame_command(case, game_dir, run_dir, "/blwtc debugdensity", "density-after-debugdensity-f2", 3.0)
        result["afterScreenshot"] = after_command["screenshot"]
        result["afterClientCommand"] = after_command
        debug_check = wait_client_debugdensity(client_log, client_debug_offset)
        result["debugCommandCheck"] = debug_check
        cleanup_offset = external.log_text_offset(server_log)
        cleanup = cleanup_command(case, username)
        if cleanup:
            run_console(process, command_log, cleanup)
            time.sleep(1.0)
            result["cleanupLogExcerpt"] = external.read_text_since(server_log, cleanup_offset)[-2000:]
        else:
            result["cleanupSkipped"] = "Folia vanilla entity selector can trip region thread checks; density limiter already reduced the sample."
        if density["status"] == "PASS" and notice_check["status"] == "PASS" and notice_count_check["status"] == "PASS" and debug_check["status"] == "PASS" and screenshot_info(before)["brightness"] > 3 and screenshot_info(notify)["brightness"] > 3 and screenshot_info(Path(result["afterScreenshot"]))["brightness"] > 3:
            result["status"] = "PASS"
    except Exception as error:
        result["error"] = repr(error)
        log("密集实体截图用例失败 " + case["id"] + ": " + repr(error))
        if game_dir is not None:
            try:
                result["failureScreenshot"] = str(copy_named_screenshot(case, game_dir, run_dir, "failure-f2"))
            except Exception as screenshot_error:
                result["failureScreenshotError"] = repr(screenshot_error)
    finally:
        if client is not None:
            external.stop_process(client)
        base.ACTIVE_CLIENT_PID = None
        if process is not None:
            external.stop_process(process, "stop")
        restore_backups(backups)
    write_json(run_dir / "result.json", result)
    write_json(run_dir / "artifact-summary.json", artifact_summary(result))
    return result


def make_contact_sheet(results: list[dict], run_root: Path) -> Path | None:
    """生成清理前、正式提示和调试统计截图总览图。"""
    rows = []
    for item in results:
        if item.get("status") != "PASS":
            continue
        before = Path(item["beforeScreenshot"])
        notify = Path(item["notifyScreenshot"])
        after = Path(item["afterScreenshot"])
        if not before.is_file() or not notify.is_file() or not after.is_file():
            continue
        row = []
        for label, path in (("before", before), ("notify", notify), ("debugdensity", after)):
            image = Image.open(path).convert("RGB")
            image.thumbnail((360, 210))
            canvas = Image.new("RGB", (390, 255), (24, 24, 24))
            canvas.paste(image, (15, 15))
            draw = ImageDraw.Draw(canvas)
            draw.text((15, 226), item["label"] + " " + label, fill=(255, 255, 255))
            row.append(canvas)
        rows.append(row)
    if not rows:
        return None
    sheet = Image.new("RGB", (1170, len(rows) * 255), (16, 16, 16))
    for index, row in enumerate(rows):
        sheet.paste(row[0], (0, index * 255))
        sheet.paste(row[1], (390, index * 255))
        sheet.paste(row[2], (780, index * 255))
    path = run_root / "entity-density-visual-contact-sheet.png"
    sheet.save(path)
    return path


def archive_evidence(run_root: Path, summary: dict) -> Path:
    """把本轮 build 证据复制到 docs/test-evidence。"""
    target = EVIDENCE_ROOT / ("entity-density-visual-" + run_root.name)
    if target.exists():
        shutil.rmtree(target)
    shutil.copytree(run_root, target, ignore=shutil.ignore_patterns("*.tmp"))
    write_json(target / "summary.json", summary)
    return target


def main() -> int:
    """运行外部服务端密集实体游戏内截图矩阵。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", default="")
    parser.add_argument("--spawn-count", type=int, default=80)
    parser.add_argument("--remove-count", type=int, default=80)
    args = parser.parse_args()
    run_id = time.strftime("%Y%m%d-%H%M%S")
    run_root = BUILD_ROOT / "runs" / run_id
    run_root.mkdir(parents=True, exist_ok=True)
    cases = selected_cases(args.case or None)
    prepared_clients = {}
    results = []
    for case in cases:
        prepared_clients.setdefault(case["version"], base.ensure_client(case["version"]))
        result = run_case(case, prepared_clients, run_root, args.spawn_count, args.remove_count)
        results.append(result)
        write_json(run_root / "summary.json", {"run": run_id, "results": results})
    contact_sheet = make_contact_sheet(results, run_root)
    summary = {
        "run": run_id,
        "spawnCount": args.spawn_count,
        "removeCount": max(1, args.remove_count),
        "results": results,
        "contactSheet": str(contact_sheet) if contact_sheet else "",
        "allPassed": all(item.get("status") == "PASS" for item in results),
    }
    write_json(run_root / "summary.json", summary)
    write_json(BUILD_ROOT / "last-summary.json", summary)
    evidence_dir = archive_evidence(run_root, summary)
    summary["evidenceDir"] = str(evidence_dir)
    write_json(run_root / "summary.json", summary)
    write_json(BUILD_ROOT / "last-summary.json", summary)
    write_json(evidence_dir / "summary.json", summary)
    failed = [item for item in results if item.get("status") != "PASS"]
    log("密集实体截图矩阵完成: total=" + str(len(results)) + " failed=" + str(len(failed)) + " evidence=" + str(evidence_dir))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
