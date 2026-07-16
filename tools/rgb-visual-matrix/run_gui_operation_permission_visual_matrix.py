import argparse
import hashlib
import json
import shutil
import time
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

import run_permission_negative_visual_matrix as perm
import run_rgb_external_server_matrix as external
import run_rgb_visual_matrix as base
import run_trash_gui_click_visual_matrix as gui


EVIDENCE_ROOT = base.REPO / "docs" / "test-evidence"
TARGET_CASE_IDS = [
    "managed_paper1122",
    "external_spigot2612",
    "external_folia1218",
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
    """按参数选择本轮要跑的 GUI 操作权限用例。"""
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
    raise RuntimeError("未知 GUI 操作权限测试用例: " + case_id)


def sha256_file(path: Path) -> str:
    """计算文件 SHA256。"""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def backup_file(path: Path, backup_dir: Path) -> dict:
    """备份一个可能存在的文件。"""
    backup = backup_dir / (path.name + ".before")
    backup.parent.mkdir(parents=True, exist_ok=True)
    if path.is_file():
        shutil.copy2(path, backup)
        return {"target": path, "backup": backup, "existed": True}
    return {"target": path, "backup": backup, "existed": False}


def restore_backups(backups: list[dict]) -> None:
    """恢复本轮测试改动过的文件。"""
    for item in backups:
        target = Path(item["target"])
        backup = Path(item["backup"])
        if item.get("existed") and backup.is_file():
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(backup, target)
        elif not item.get("existed") and target.is_file():
            target.unlink()


def screenshot_info(path: Path) -> dict:
    """读取截图基础信息。"""
    image = Image.open(path).convert("RGB")
    data = path.read_bytes()
    return {
        "path": str(path),
        "size": path.stat().st_size,
        "sha256": hashlib.sha256(data).hexdigest(),
        "dimensions": [image.width, image.height],
        "brightness": base.image_brightness(image),
    }


def capture_named_screenshot(case: dict, game_dir: Path, run_dir: Path, name: str) -> dict:
    """截取 F2 截图并返回基础信息。"""
    path = gui.capture_named_screenshot(case, game_dir, run_dir, name)
    return screenshot_info(path)


def run_console(process, command_log: Path, command: str, wait: float = 0.3) -> None:
    """向服务端控制台发送命令。"""
    external.send_console_command(process, command, command_log)
    time.sleep(wait)


def run_console_capture(process, server_log: Path, command_log: Path, command: str, wait: float = 0.8) -> str:
    """执行控制台命令并返回新增日志。"""
    offset = external.log_text_offset(server_log)
    run_console(process, command_log, command, wait)
    return external.strip_ansi(external.read_text_since(server_log, offset))


def run_console_expect(process, server_log: Path, command_log: Path, command: str,
                       markers: list[str], timeout: float = 10.0) -> str:
    """执行控制台命令并等待指定输出。"""
    offset = external.log_text_offset(server_log)
    run_console(process, command_log, command, 0.35)
    return external.strip_ansi(external.wait_command_markers(server_log, offset, markers, timeout, command))


def wait_client_marker_sets(client_log: Path, offset: int, marker_sets: list[list[str]], timeout: float) -> dict:
    """等待真实客户端日志命中任意一组聊天标记。"""
    return perm.wait_client_marker_sets(client_log, offset, marker_sets, timeout)


def assert_pass(check: dict, label: str) -> None:
    """断言检查结果为 PASS。"""
    if check.get("status") != "PASS":
        raise AssertionError(label + " 未通过: " + str(check))


def deny_permissions(process, server_log: Path, command_log: Path, username: str, permissions: list[str]) -> str:
    """给玩家挂 false 权限附件。"""
    command = "permfixture deny " + username + " " + " ".join(permissions)
    return run_console_expect(process, server_log, command_log, command, ["AI_PERMFIXTURE_DENY"])


def clear_permissions(process, server_log: Path, command_log: Path, username: str) -> str:
    """清理玩家权限附件。"""
    return run_console_expect(process, server_log, command_log, "permfixture clear " + username, ["AI_PERMFIXTURE_CLEAR"])


def debug_summary_amounts(text: str) -> dict:
    """解析 debugsummary 中的公共和个人垃圾桶物品数。"""
    plain = external.strip_ansi(text)
    result = {"global": -1, "personal": -1}
    for line in plain.splitlines():
        if "公共垃圾桶物品:" in line:
            result["global"] = parse_first_int_after_marker(line, "公共垃圾桶物品:")
        if "个人垃圾桶物品:" in line:
            result["personal"] = parse_first_int_after_marker(line, "个人垃圾桶物品:")
    if result["global"] < 0 or result["personal"] < 0:
        fallback = debug_summary_amounts_by_order(plain)
        if result["global"] < 0:
            result["global"] = fallback["global"]
        if result["personal"] < 0:
            result["personal"] = fallback["personal"]
    return result


def debug_summary_amounts_by_order(text: str) -> dict:
    """兼容 1.12 控制台中文乱码时按 debugsummary 固定行序解析库存。"""
    values = []
    in_summary = False
    for line in text.splitlines():
        if "BlWorldTrashCan debug summary" in line:
            in_summary = True
            values = []
            continue
        if not in_summary:
            continue
        if "- " not in line:
            continue
        values.append(parse_first_int_after_last_colon(line))
    if len(values) >= 5:
        return {"global": values[-2], "personal": values[-1]}
    return {"global": -1, "personal": -1}


def parse_first_int_after_marker(line: str, marker: str) -> int:
    """解析指定标记后的第一个整数。"""
    text = line.split(marker, 1)[1] if marker in line else line
    digits = ""
    for char in text:
        if char.isdigit():
            digits += char
        elif digits:
            break
    return int(digits) if digits else -1


def parse_first_int_after_last_colon(line: str) -> int:
    """解析最后一个冒号后的第一个整数，避开日志时间戳。"""
    text = line.rsplit(":", 1)[1] if ":" in line else line
    digits = ""
    for char in text:
        if char.isdigit():
            digits += char
        elif digits:
            break
    return int(digits) if digits else -1


def snapshot_global_logs(case: dict) -> dict:
    """记录公共垃圾桶日志当前位置。"""
    log_dir = Path(case["serverDir"]) / "plugins" / "BlWorldTrashCan" / "logs"
    snapshot = {}
    for path in sorted(log_dir.glob("global-trash-*.log")):
        snapshot[str(path)] = path.stat().st_size
    return snapshot


def read_global_logs_since(case: dict, snapshot: dict, username: str) -> str:
    """读取公共垃圾桶操作日志增量中当前玩家相关行。"""
    log_dir = Path(case["serverDir"]) / "plugins" / "BlWorldTrashCan" / "logs"
    lines = []
    for path in sorted(log_dir.glob("global-trash-*.log")):
        start = snapshot.get(str(path), 0)
        with path.open("r", encoding="utf-8", errors="replace") as handle:
            handle.seek(start)
            text = handle.read()
        for line in text.splitlines():
            if username in line:
                lines.append(path.name + " " + line)
    return "\n".join(lines)


def render_text_screenshot(text: str, target: Path, title: str) -> dict:
    """把关键日志渲染成 PNG 证据。"""
    target.parent.mkdir(parents=True, exist_ok=True)
    used_font = font()
    lines = [title, ""] + external.strip_ansi(text).splitlines()[-42:]
    width = 1600
    line_height = 26
    height = max(260, line_height * (len(lines) + 2))
    image = Image.new("RGB", (width, height), (15, 23, 42))
    draw = ImageDraw.Draw(image)
    y = 18
    for index, line in enumerate(lines):
        color = (255, 209, 102) if index == 0 else (226, 232, 240)
        draw.text((22, y), line[:190], fill=color, font=used_font)
        y += line_height
    image.save(target)
    return screenshot_info(target)


def font() -> ImageFont.ImageFont:
    """返回支持中文的截图字体。"""
    for path in (Path(r"C:\Windows\Fonts\msyh.ttc"), Path(r"C:\Windows\Fonts\simsun.ttc")):
        if path.is_file():
            return ImageFont.truetype(str(path), 18)
    return ImageFont.load_default()


def prepare_player(case: dict, username: str, process, command_log: Path) -> None:
    """初始化玩家位置、背包和权限状态。"""
    gamemode = "minecraft:gamemode 1 " + username if gui.is_legacy(case) else "minecraft:gamemode creative " + username
    for command in (
        "op " + username,
        "minecraft:gamerule sendCommandFeedback false",
        "minecraft:gamerule commandBlockOutput false",
        "minecraft:gamerule doMobSpawning false",
        "minecraft:gamerule keepInventory true",
        "minecraft:gamerule fallDamage false",
        "minecraft:time set day",
        "minecraft:weather clear",
        gamemode,
        "minecraft:fill -3 90 -11 3 90 -5 stone",
        "minecraft:tp " + username + " 0 91 -8 0 15",
        "deop " + username,
        "minecraft:clear " + username,
    ):
        run_console(process, command_log, command, 0.4)


def open_trash_gui(case: dict, username: str, process, command_log: Path,
                   run_dir: Path, game_dir: Path, kind: str, screenshot_name: str) -> dict:
    """通过后台测试入口打开真实客户端 GUI 并截图。"""
    run_console(process, command_log, "blwtc debugopen " + username + " " + kind, 1.0)
    return capture_named_screenshot(case, game_dir, run_dir, screenshot_name)


def route_item(process, server_log: Path, command_log: Path, username: str,
               route: str, material: str, amount: int) -> str:
    """用 debugroute 准备垃圾桶库存。"""
    command = "blwtc debugroute " + username + " " + route + " " + material + " " + str(amount)
    return run_console_expect(process, server_log, command_log, command, ["[Debug] debugRoute", "routed=true"], 12.0)


def give_item(process, command_log: Path, username: str, material: str, amount: int) -> None:
    """给玩家准备背包物品。"""
    run_console(process, command_log, "minecraft:clear " + username, 0.25)
    run_console(process, command_log, "minecraft:give " + username + " " + material + " " + str(amount), 0.8)


def run_global_take_denied(case: dict, username: str, process, server_log: Path,
                           command_log: Path, run_dir: Path, game_dir: Path) -> dict:
    """验证公共垃圾桶取出权限 deny 后无法取出。"""
    clear_permissions(process, server_log, command_log, username)
    route_item(process, server_log, command_log, username, "global", "COBBLESTONE", 7)
    run_console(process, command_log, "minecraft:clear " + username, 0.25)
    deny_permissions(process, server_log, command_log, username, [
        "blworldtrashcan.global.take",
        "WorldListTrashCan.GlobalTrashTakeItem",
    ])
    client_log = run_dir / "logs" / (case["id"] + "-client-stdout.log")
    open_result = open_trash_gui(case, username, process, command_log, run_dir, game_dir, "global", "global-take-denied-open-f2")
    offset = external.log_text_offset(client_log)
    log_snapshot = snapshot_global_logs(case)
    gui.click_slot(case, "top", 0, 0)
    time.sleep(1.0)
    screenshot = capture_named_screenshot(case, game_dir, run_dir, "global-take-denied-after-click-f2")
    client_check = wait_client_marker_sets(client_log, offset, [
        ["不能从公共垃圾桶取出物品"],
        ["没有权限从公共垃圾桶取出物品"],
        ["你没有权限从公共垃圾桶取出物品"],
        ["权限不足", "公共垃圾桶"],
        ["no tienes permiso", "global"],
        ["permission", "global"],
    ], 8.0)
    gui.close_gui(case)
    summary = run_console_capture(process, server_log, command_log, "blwtc debugsummary " + username, 0.8)
    amounts = debug_summary_amounts(summary)
    log_delta = read_global_logs_since(case, log_snapshot, username)
    status = "PASS" if client_check["status"] == "PASS" and amounts["global"] == 7 and "-global" not in log_delta else "FAIL"
    return {
        "name": "global-take-denied",
        "status": status,
        "openScreenshot": open_result,
        "clickScreenshot": screenshot,
        "clientLog": client_check,
        "summaryAmounts": amounts,
        "summaryExcerpt": summary[-1800:],
        "globalLogDelta": log_delta,
    }


def run_global_put_denied(case: dict, username: str, process, server_log: Path,
                          command_log: Path, run_dir: Path, game_dir: Path) -> dict:
    """验证公共垃圾桶放入权限 deny 后无法放入。"""
    clear_permissions(process, server_log, command_log, username)
    deny_permissions(process, server_log, command_log, username, [
        "blworldtrashcan.global.put",
        "WorldListTrashCan.GlobalTrashPutItem",
    ])
    before = run_console_capture(process, server_log, command_log, "blwtc debugsummary " + username, 0.8)
    before_amounts = debug_summary_amounts(before)
    give_item(process, command_log, username, "stone", 5)
    log_snapshot = snapshot_global_logs(case)
    open_result = open_trash_gui(case, username, process, command_log, run_dir, game_dir, "global", "global-put-denied-open-f2")
    gui.click_slot(case, "hotbar", 0, 0)
    time.sleep(1.0)
    screenshot = capture_named_screenshot(case, game_dir, run_dir, "global-put-denied-after-click-f2")
    gui.close_gui(case)
    after = run_console_capture(process, server_log, command_log, "blwtc debugsummary " + username, 0.8)
    after_amounts = debug_summary_amounts(after)
    log_delta = read_global_logs_since(case, log_snapshot, username)
    status = "PASS" if after_amounts["global"] == before_amounts["global"] and "+global" not in log_delta else "FAIL"
    return {
        "name": "global-put-denied",
        "status": status,
        "openScreenshot": open_result,
        "clickScreenshot": screenshot,
        "beforeAmounts": before_amounts,
        "afterAmounts": after_amounts,
        "summaryExcerpt": after[-1800:],
        "globalLogDelta": log_delta,
    }


def run_personal_take_denied(case: dict, username: str, process, server_log: Path,
                             command_log: Path, run_dir: Path, game_dir: Path) -> dict:
    """验证个人垃圾桶取出权限 deny 后无法取出。"""
    clear_permissions(process, server_log, command_log, username)
    route_item(process, server_log, command_log, username, "personal", "DIRT", 3)
    run_console(process, command_log, "minecraft:clear " + username, 0.25)
    deny_permissions(process, server_log, command_log, username, [
        "blworldtrashcan.personal.take",
        "WorldListTrashCan.PersonalTrashTakeItem",
    ])
    client_log = run_dir / "logs" / (case["id"] + "-client-stdout.log")
    open_result = open_trash_gui(case, username, process, command_log, run_dir, game_dir, "personal", "personal-take-denied-open-f2")
    offset = external.log_text_offset(client_log)
    gui.click_slot(case, "top", 0, 0)
    time.sleep(1.0)
    screenshot = capture_named_screenshot(case, game_dir, run_dir, "personal-take-denied-after-click-f2")
    client_check = wait_client_marker_sets(client_log, offset, [
        ["不能从个人垃圾桶取出物品"],
        ["没有权限从个人垃圾桶取出物品"],
        ["你没有权限从个人垃圾桶取出物品"],
        ["权限不足", "个人垃圾桶"],
        ["permission", "personal"],
        ["permiso", "personal"],
    ], 8.0)
    gui.close_gui(case)
    summary = run_console_capture(process, server_log, command_log, "blwtc debugsummary " + username, 0.8)
    amounts = debug_summary_amounts(summary)
    status = "PASS" if client_check["status"] == "PASS" and amounts["personal"] == 3 else "FAIL"
    return {
        "name": "personal-take-denied",
        "status": status,
        "openScreenshot": open_result,
        "clickScreenshot": screenshot,
        "clientLog": client_check,
        "summaryAmounts": amounts,
        "summaryExcerpt": summary[-1800:],
    }


def run_personal_put_denied(case: dict, username: str, process, server_log: Path,
                            command_log: Path, run_dir: Path, game_dir: Path) -> dict:
    """验证个人垃圾桶放入权限 deny 后无法放入。"""
    clear_permissions(process, server_log, command_log, username)
    deny_permissions(process, server_log, command_log, username, [
        "blworldtrashcan.personal.put",
        "WorldListTrashCan.PersonalTrashPutItem",
    ])
    before = run_console_capture(process, server_log, command_log, "blwtc debugsummary " + username, 0.8)
    before_amounts = debug_summary_amounts(before)
    give_item(process, command_log, username, "stone", 4)
    open_result = open_trash_gui(case, username, process, command_log, run_dir, game_dir, "personal", "personal-put-denied-open-f2")
    gui.click_slot(case, "hotbar", 0, 0)
    time.sleep(1.0)
    screenshot = capture_named_screenshot(case, game_dir, run_dir, "personal-put-denied-after-click-f2")
    gui.close_gui(case)
    after = run_console_capture(process, server_log, command_log, "blwtc debugsummary " + username, 0.8)
    after_amounts = debug_summary_amounts(after)
    status = "PASS" if after_amounts["personal"] == before_amounts["personal"] else "FAIL"
    return {
        "name": "personal-put-denied",
        "status": status,
        "openScreenshot": open_result,
        "clickScreenshot": screenshot,
        "beforeAmounts": before_amounts,
        "afterAmounts": after_amounts,
        "summaryExcerpt": after[-1800:],
    }


def run_case(case: dict, prepared_clients: dict, evidence_root: Path) -> dict:
    """运行单个真实客户端 GUI 操作权限用例。"""
    case = dict(case)
    case["runId"] = evidence_root.name
    run_dir = evidence_root / case["id"]
    run_dir.mkdir(parents=True, exist_ok=True)
    server_log = run_dir / "logs" / (case["id"] + "-server-console.log")
    command_log = run_dir / "logs" / (case["id"] + "-commands.log")
    process = None
    client = None
    username = ""
    game_dir = None
    backups = []
    result = {
        "id": case["id"],
        "label": case["label"],
        "version": case["version"],
        "serverDir": str(case["serverDir"]),
        "status": "FAIL",
        "plugin": case["plugin"],
        "artifact": external.artifact_summary_for_plugin(case),
        "checks": [],
    }
    try:
        process = external.launch_server(case, run_dir)
        backup_dir = run_dir / "logs" / "config-backup"
        trash_file = Path(case["serverDir"]) / "plugins" / "BlWorldTrashCan" / "trash.yml"
        backups.append(backup_file(trash_file, backup_dir))
        gui.patch_trash_config(case)
        copy_runtime_file(trash_file, run_dir / "logs" / "trash-after-patch.yml")
        gui.reload_plugin(process, command_log, server_log)
        prepared = prepared_clients[case["version"]]
        client, username, game_dir = base.launch_client(case, prepared, run_dir)
        base.ACTIVE_CLIENT_PID = client.pid
        external.wait_player_online(case, username, server_log)
        result["username"] = username
        result["clientPid"] = client.pid
        prepare_player(case, username, process, command_log)
        result["checks"].append(run_global_take_denied(case, username, process, server_log, command_log, run_dir, game_dir))
        result["checks"].append(run_global_put_denied(case, username, process, server_log, command_log, run_dir, game_dir))
        result["checks"].append(run_personal_take_denied(case, username, process, server_log, command_log, run_dir, game_dir))
        result["checks"].append(run_personal_put_denied(case, username, process, server_log, command_log, run_dir, game_dir))
        failed = [item["name"] for item in result["checks"] if item.get("status") != "PASS"]
        blank = []
        for item in result["checks"]:
            for key in ("clickScreenshot",):
                value = item.get(key)
                if isinstance(value, dict) and value.get("brightness", 4) <= 3:
                    blank.append(item["name"] + ":" + key)
        result["failedChecks"] = failed
        result["blankScreenshots"] = blank
        result["status"] = "PASS" if not failed and not blank else "FAIL"
        result["commandsScreenshot"] = render_text_screenshot(
            command_log.read_text(encoding="utf-8", errors="replace"),
            run_dir / "server-screenshots" / (case["id"] + "-commands.png"),
            case["label"] + " / GUI operation permission commands",
        )
    except Exception as error:
        result["error"] = repr(error)
        if client is not None and game_dir is not None:
            try:
                result["failureScreenshot"] = capture_named_screenshot(case, game_dir, run_dir, "failure-f2")
            except Exception as screenshot_error:
                result["failureScreenshotError"] = repr(screenshot_error)
        raise
    finally:
        if username and process is not None:
            try:
                clear_permissions(process, server_log, command_log, username)
                run_console(process, command_log, "op " + username, 0.2)
            except Exception:
                pass
        if client is not None:
            external.stop_process(client)
        base.ACTIVE_CLIENT_PID = None
        if process is not None:
            restore_backups(backups)
            external.stop_process(process, "stop")
        copy_runtime_evidence(case, run_dir)
        write_json(run_dir / "result.json", result)
    return result


def copy_runtime_file(source: Path, target: Path) -> str:
    """归档一个运行时文件，文件不存在时返回空字符串。"""
    if not source.is_file():
        return ""
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)
    return str(target)


def copy_runtime_evidence(case: dict, run_dir: Path) -> None:
    """复制本轮运行态证据。"""
    server_dir = Path(case["serverDir"])
    plugin_dir = server_dir / "plugins" / "BlWorldTrashCan"
    copy_runtime_file(server_dir / "logs" / "latest.log", run_dir / "logs" / "latest.log")
    copy_runtime_file(plugin_dir / "trash.yml", run_dir / "config" / "trash-after-restore.yml")
    copy_runtime_file(plugin_dir / "messages" / "message_zh.yml", run_dir / "config" / "message_zh.yml")
    copy_runtime_file(plugin_dir / "config.yml", run_dir / "config" / "config.yml")


def make_contact_sheet(results: list[dict], evidence_root: Path) -> str:
    """生成 GUI 操作权限截图总览图。"""
    screenshots = []
    for result in results:
        for item in result.get("checks", []):
            value = item.get("clickScreenshot")
            if isinstance(value, dict) and value.get("path"):
                screenshots.append((result["label"] + " " + item["name"], Path(value["path"])))
    if not screenshots:
        return ""
    used_font = font()
    tiles = []
    for label, path in screenshots:
        if not path.is_file():
            continue
        image = Image.open(path).convert("RGB")
        image.thumbnail((420, 240))
        canvas = Image.new("RGB", (440, 300), (15, 23, 42))
        canvas.paste(image, ((440 - image.width) // 2, 10))
        draw = ImageDraw.Draw(canvas)
        draw.text((10, 252), label[:58], fill=(226, 232, 240), font=used_font)
        draw.text((10, 274), path.name[:58], fill=(148, 163, 184), font=used_font)
        tiles.append(canvas)
    if not tiles:
        return ""
    columns = 2
    rows = (len(tiles) + columns - 1) // columns
    sheet = Image.new("RGB", (columns * 440, rows * 300), (2, 6, 23))
    for index, tile in enumerate(tiles):
        sheet.paste(tile, ((index % columns) * 440, (index // columns) * 300))
    target = evidence_root / "gui-operation-permission-contact-sheet.png"
    sheet.save(target)
    return str(target)


def write_readme(evidence_root: Path, summary: dict) -> None:
    """生成证据目录 README。"""
    environments = "、".join(
        item["label"] + " + 真实 " + str(item["version"]) + " 客户端"
        for item in summary["results"]
    )
    lines = [
        "# GUI 取放权限真实客户端专项",
        "",
        "- 被测 jar: `dist/BlWorldTrashCan-universal.jar`",
        "- SHA256: `" + summary.get("jarSha256", "") + "`",
        "- 验收方式: " + environments + " + 临时 PermissionDenyFixture。",
        "- 覆盖: 公共取出 deny、公共放入 deny、个人取出 deny、个人放入 deny。",
        "- 通过标准: 真实点击后取出权限必须出现客户端无权限提示；放入权限静默拒绝但垃圾桶库存和公共操作日志不能变化。",
        "- 结论: " + summary["status"],
        "",
        "| 服务端 | 版本 | 状态 | 玩家 |",
        "| --- | --- | --- | --- |",
    ]
    for item in summary["results"]:
        lines.append("| " + item["label"] + " | " + str(item["version"]) + " | " + item["status"] + " | " + item.get("username", "") + " |")
    lines.extend([
        "",
        "## 证据",
        "",
        "- `summary.json`: 机器可读结果。",
        "- `*/result.json`: 单端详细断言。",
        "- `*/screenshots/*after-click-f2.png`: 真实客户端点击后的 F2 截图。",
        "- `*/logs/*client-stdout.log`: 客户端聊天和系统日志。",
        "- `*/logs/*server-console.log`、`*/logs/latest.log`: 服务端运行日志。",
        "- `gui-operation-permission-contact-sheet.png`: 点击截图总览。",
        "",
    ])
    (evidence_root / "README.md").write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    """运行 GUI 取放权限真实客户端专项。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", default=None)
    parser.add_argument("--evidence-name", default="gui-operation-permission-visual-" + time.strftime("%Y%m%d-%H%M%S"))
    args = parser.parse_args()
    fixture_jar = perm.build_permission_fixture()
    cases = selected_cases(args.case)
    for case in cases:
        extras = list(case.get("extraPlugins", []))
        extras.append(fixture_jar)
        case["extraPlugins"] = extras
    evidence_root = EVIDENCE_ROOT / args.evidence_name
    evidence_root.mkdir(parents=True, exist_ok=True)
    prepared_clients = {}
    results = []
    for case in cases:
        log("运行 GUI 取放权限专项: " + case["label"])
        prepared_clients.setdefault(case["version"], base.ensure_client(case["version"]))
        results.append(run_case(case, prepared_clients, evidence_root))
        write_json(evidence_root / "summary.json", {"status": "RUNNING", "results": results})
    contact_sheet = make_contact_sheet(results, evidence_root)
    jar_path = base.REPO / "dist" / "BlWorldTrashCan-universal.jar"
    summary = {
        "status": "PASS" if all(item["status"] == "PASS" for item in results) else "FAIL",
        "jar": str(jar_path),
        "jarSha256": sha256_file(jar_path),
        "fixtureJar": str(fixture_jar),
        "fixtureSha256": sha256_file(fixture_jar),
        "results": results,
        "contactSheet": contact_sheet,
    }
    write_json(evidence_root / "summary.json", summary)
    write_readme(evidence_root, summary)
    log("GUI 取放权限专项完成: " + str(evidence_root))
    return 0 if summary["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
