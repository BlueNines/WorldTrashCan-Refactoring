import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile
from pathlib import Path

import pyautogui
from PIL import Image, ImageDraw, ImageFont

import run_cleanup_guard_visual_matrix as guard
import run_rgb_external_server_matrix as external
import run_rgb_visual_matrix as base


EVIDENCE_ROOT = base.REPO / "docs" / "test-evidence"
UNIVERSAL_TARGET = base.REPO / "bl-world-trashcan-plugin-universal" / "target" / "bl-world-trashcan-plugin-universal-7.0.0.jar"
UNIVERSAL_DIST = base.REPO / "dist" / "BLWorldTrashCan-universal.jar"
MANUAL_TRASH_ITEMS = [
    {"id": "resin_clump", "amount": 24},
    {"id": "creaking_heart", "amount": 2},
    {"id": "resin_bricks", "amount": 64},
    {"id": "chiseled_resin_bricks", "amount": 24},
    {"id": "copper_grate", "amount": 24},
    {"id": "copper_bulb", "amount": 17},
    {"id": "crafter", "amount": 64},
    {"id": "blackstone", "amount": 64},
    {"id": "heavy_core", "amount": 1},
]
CLEANUP_TRASH_ITEMS = [
    {"material": "POLISHED_BLACKSTONE", "amount": 5},
    {"material": "GILDED_BLACKSTONE", "amount": 4},
    {"material": "CRYING_OBSIDIAN", "amount": 3},
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


def new_material_case(case_id: str, label: str, version: str, server_dir: Path, server_jar: str, port: int) -> dict:
    """返回新材质入桶验收使用的服务端配置。"""
    return {
        "id": case_id,
        "label": label,
        "version": version,
        "serverDir": server_dir,
        "serverJar": server_jar,
        "port": port,
        "java": base.JAVA21,
        "plugin": "BLWorldTrashCan-universal.jar",
        "expect": "rgb",
        "quickPlay": True,
        "direct": False,
        "readyTimeout": 180,
        "joinTimeout": 120,
    }


def paper1214_case(case_id: str) -> dict:
    """返回本轮新材质验收使用的 Paper 1.21.4 测试服配置。"""
    return new_material_case(
        case_id,
        "paper-1.21.4-new-material-universal",
        "1.21.4",
        base.WORKSPACE / "paper-1.21.4-test-server",
        "paper-1.21.4-232.jar",
        25576,
    )


def read_server_port(server_dir: Path) -> int:
    """从 server.properties 读取服务端端口。"""
    properties = server_dir / "server.properties"
    if not properties.is_file():
        return 25565
    for line in properties.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if line.startswith("server-port="):
            return int(line.split("=", 1)[1])
    return 25565


def find_server_jar(server_dir: Path, version: str, explicit: str) -> str:
    """查找本轮验收要启动的服务端 jar。"""
    if explicit:
        return explicit
    candidates = sorted(server_dir.glob("*" + version + "*.jar"))
    if not candidates:
        candidates = sorted(server_dir.glob("*.jar"))
    if not candidates:
        raise RuntimeError("没有在服务端目录找到 jar: " + str(server_dir))
    return candidates[0].name


def build_case_from_args(args: argparse.Namespace) -> dict:
    """根据命令行参数构建验收用例。"""
    timestamp = time.strftime("%H%M%S")
    if not args.server_dir:
        return paper1214_case("new_mat_" + timestamp)
    server_dir = Path(args.server_dir)
    version_id = args.mc_version.replace(".", "")
    server_jar = find_server_jar(server_dir, args.mc_version, args.server_jar)
    port = int(args.port or read_server_port(server_dir))
    label = args.label or ("paper-" + args.mc_version + "-new-material-universal")
    return new_material_case("new_mat_" + version_id + "_" + timestamp, label, args.mc_version, server_dir, server_jar, port)


def sync_universal_dist() -> dict:
    """把刚构建的 universal jar 同步到 dist 并返回制品摘要。"""
    if not UNIVERSAL_TARGET.is_file():
        raise RuntimeError("缺少 universal 构建产物: " + str(UNIVERSAL_TARGET))
    UNIVERSAL_DIST.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(UNIVERSAL_TARGET, UNIVERSAL_DIST)
    data = UNIVERSAL_DIST.read_bytes()
    plugin_yml = ""
    with zipfile.ZipFile(UNIVERSAL_DIST) as archive:
        plugin_yml = archive.read("plugin.yml").decode("utf-8", errors="replace")
    return {
        "path": UNIVERSAL_DIST,
        "size": UNIVERSAL_DIST.stat().st_size,
        "sha256": hashlib.sha256(data).hexdigest(),
        "pluginYml": plugin_yml,
        "hasApiVersion113": "api-version: '1.13'" in plugin_yml or 'api-version: "1.13"' in plugin_yml,
    }


def backup_and_deploy_plugin(case: dict, run_dir: Path) -> dict:
    """临时禁用旧垃圾桶插件后部署本轮 universal 整包。"""
    artifact = sync_universal_dist()
    plugins_dir = Path(case["serverDir"]) / "plugins"
    backed_up = []
    patterns = ["BLWorldTrashCan*.jar", "WorldListTrashCan*.jar", "wtc.jar"]
    old_plugins = []
    for pattern in patterns:
        old_plugins.extend(sorted(plugins_dir.glob(pattern)))
    for old in sorted(set(old_plugins)):
        disabled = old.with_name(old.name + ".ai-disabled-" + case["id"])
        if os.path.lexists(disabled):
            disabled.unlink()
        old.rename(disabled)
        backed_up.append({"source": old, "disabled": disabled})
    target = plugins_dir / case["plugin"]
    shutil.copy2(UNIVERSAL_DIST, target)
    return {
        "artifact": artifact,
        "deployed": target,
        "backups": backed_up,
    }


def restore_deployed_plugins(deploy: dict) -> None:
    """恢复本轮部署前临时禁用的插件 jar。"""
    deployed = Path(deploy.get("deployed", ""))
    if os.path.lexists(deployed):
        deployed.unlink()
    for item in reversed(deploy.get("backups", [])):
        source = Path(item["source"])
        disabled = Path(item["disabled"])
        if os.path.lexists(disabled):
            if os.path.lexists(source):
                source.unlink()
            disabled.rename(source)


def launch_server_with_plugin(case: dict, run_dir: Path) -> tuple[subprocess.Popen, dict]:
    """部署插件、启动测试服并等待 ready。"""
    deploy = backup_and_deploy_plugin(case, run_dir)
    server_dir = Path(case["serverDir"])
    log_path = run_dir / "logs" / (case["id"] + "-server-console.log")
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_file = log_path.open("w", encoding="utf-8", errors="replace")
    command = external.server_command(case)
    write_json(run_dir / "logs" / (case["id"] + "-server-launch.json"), {
        "cwd": server_dir,
        "command": command,
        "plugin": deploy,
        "port": case["port"],
    })
    process = subprocess.Popen(
        command,
        cwd=str(server_dir),
        stdin=subprocess.PIPE,
        stdout=log_file,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    process._blwtc_log_file = log_file
    try:
        external.wait_server_ready(process, log_path, int(case["port"]), int(case.get("readyTimeout", 180)))
        return process, deploy
    except Exception:
        external.stop_process(process, "stop")
        log_file.close()
        raise


def close_server_process(process: subprocess.Popen) -> None:
    """停止服务端进程并关闭日志句柄。"""
    try:
        external.stop_process(process, "stop")
    finally:
        log_file = getattr(process, "_blwtc_log_file", None)
        if log_file is not None:
            log_file.close()


def backup_runtime_file(path: Path, backup_dir: Path) -> dict:
    """备份一个运行时配置文件。"""
    backup = backup_dir / (path.name + ".before")
    backup.parent.mkdir(parents=True, exist_ok=True)
    if path.is_file():
        shutil.copy2(path, backup)
        return {"target": path, "backup": backup, "existed": True}
    return {"target": path, "backup": backup, "existed": False}


def restore_runtime_files(backups: list[dict]) -> None:
    """恢复本轮修改过的运行时配置文件。"""
    for item in backups:
        target = Path(item["target"])
        backup = Path(item["backup"])
        if item.get("existed") and backup.is_file():
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(backup, target)
        elif not item.get("existed") and target.is_file():
            target.unlink()


def ensure_cleanup_guard_block(text: str) -> str:
    """确保 cleanup.yml 存在 guards 配置块。"""
    if "\nguards:" in text or text.startswith("guards:"):
        return text
    return text.rstrip() + (
        "\n# AI 新材质入桶测试临时补入，测试结束后恢复原文件。\n"
        "guards:\n"
        "  min-online-players: 1\n"
        "  min-total-entities: 0\n"
    )


def write_test_config(case: dict, run_dir: Path) -> list[dict]:
    """写入本轮新材质验收需要的最小配置。"""
    data_dir = Path(case["serverDir"]) / "plugins" / "BLWorldTrashCan"
    backup_dir = run_dir / "logs" / "config-backup"
    trash = data_dir / "trash.yml"
    cleanup = data_dir / "cleanup.yml"
    backups = [backup_runtime_file(trash, backup_dir), backup_runtime_file(cleanup, backup_dir)]
    if not trash.is_file() or not cleanup.is_file():
        raise RuntimeError("缺少 trash.yml 或 cleanup.yml，无法写入测试配置: " + str(data_dir))
    trash_text = trash.read_text(encoding="utf-8", errors="replace")
    trash.write_text(external.update_yaml_scalars(trash_text, {
        "world-trash.enabled": "false",
        "personal-trash.enabled": "false",
        "global-trash.enabled": "true",
        "global-trash.allow-player-put": "true",
        "global-trash.clear-every-cleanups": "-1",
        "global-trash.log-enabled": "true",
    }), encoding="utf-8")
    cleanup_text = ensure_cleanup_guard_block(cleanup.read_text(encoding="utf-8", errors="replace"))
    cleanup.write_text(external.update_yaml_scalars(cleanup_text, {
        "interval-seconds": "0",
        "guards.min-online-players": "1",
        "guards.min-total-entities": "0",
    }), encoding="utf-8")
    return backups


def send_console(process: subprocess.Popen, command_log: Path, command: str, wait: float = 0.25) -> None:
    """发送服务端控制台命令并短暂等待。"""
    external.send_console_command(process, command, command_log)
    time.sleep(wait)


def setup_player(case: dict, username: str, process: subprocess.Popen, command_log: Path) -> None:
    """初始化测试玩家状态和世界环境。"""
    commands = [
        "op " + username,
        "minecraft:gamerule sendCommandFeedback false",
        "minecraft:gamerule commandBlockOutput false",
        "minecraft:gamerule doMobSpawning false",
        "minecraft:gamerule fallDamage false",
        "minecraft:time set day",
        "minecraft:weather clear",
        "minecraft:gamemode creative " + username,
        "minecraft:tp " + username + " 0 91 -8 0 15",
        "minecraft:clear " + username,
        "minecraft:kill @e[type=minecraft:item]",
    ]
    for command in commands:
        send_console(process, command_log, command, 0.15)
    time.sleep(1.0)


def give_manual_items(username: str, process: subprocess.Popen, command_log: Path) -> None:
    """给玩家发放要通过真实 GUI 点击放入公共垃圾桶的物品。"""
    for item in MANUAL_TRASH_ITEMS:
        command = "minecraft:give " + username + " minecraft:" + item["id"] + " " + str(item["amount"])
        send_console(process, command_log, command, 0.2)
    time.sleep(0.8)


def slot_center(rect: tuple[int, int, int, int], slot: int, row: str) -> tuple[float, float]:
    """按 6 行箱子 GUI 估算指定槽位中心坐标。"""
    left, top, right, bottom = rect
    width = right - left
    height = bottom - top
    gui_width = 352.0
    gui_height = 444.0
    gui_left = (width - gui_width) / 2.0
    gui_top = (height - gui_height) / 2.0
    column = slot % 9
    row_index = slot // 9
    x = gui_left + 32.0 + 36.0 * column
    if row == "hotbar":
        y = gui_top + 412.0
    elif row == "content":
        y = gui_top + 52.0 + 36.0 * row_index
    else:
        raise ValueError("未知槽位行: " + row)
    return x, y


def click_client_point(hwnd: int, rect: tuple[int, int, int, int], x: float, y: float) -> None:
    """点击客户端窗口内指定像素坐标。"""
    left, top, right, bottom = rect
    base.click_game(hwnd, rect, x / float(right - left), y / float(bottom - top))


def move_client_point(rect: tuple[int, int, int, int], x: float, y: float) -> None:
    """移动鼠标到客户端窗口内指定像素坐标。"""
    left, top, _, _ = rect
    pyautogui.moveTo(int(left + x), int(top + y), duration=0.15)
    time.sleep(0.8)


def click_manual_hotbar_items(case: dict) -> None:
    """真实点击玩家热键栏，把所有手动验收物品放入公共垃圾桶。"""
    hwnd = base.find_minecraft_window(case["version"])
    rect = base.focus_window(hwnd)
    for slot in range(len(MANUAL_TRASH_ITEMS)):
        x, y = slot_center(rect, slot, "hotbar")
        click_client_point(hwnd, rect, x, y)
        time.sleep(0.45)


def capture_hovered_slot(case: dict, game_dir: Path, run_dir: Path, slot: int, suffix: str) -> Path:
    """悬停公共垃圾桶内容槽位并保存 F2 截图。"""
    hwnd = base.find_minecraft_window(case["version"])
    rect = base.focus_window(hwnd)
    x, y = slot_center(rect, slot, "content")
    move_client_point(rect, x, y)
    return guard.capture_named_screenshot(case, game_dir, run_dir, suffix)


def capture_current(case: dict, game_dir: Path, run_dir: Path, suffix: str) -> Path:
    """保存当前客户端画面的 F2 截图。"""
    return guard.capture_named_screenshot(case, game_dir, run_dir, suffix)


def wait_server_text(server_log: Path, offset: int, markers: list[str], timeout: float) -> str:
    """等待服务端日志出现指定标记并返回新增文本。"""
    text = external.wait_command_markers(server_log, offset, markers, timeout, "wait-new-material-markers")
    return text


def strip_ansi(text: str) -> str:
    """移除服务端控制台颜色码，避免颜色转义打断中文文本匹配。"""
    return re.sub(r"\x1b\[[0-9;]*m", "", text)


def wait_stock(server_log: Path, offset: int, expected_items: int, expected_stacks: int, timeout: float = 12.0) -> dict:
    """等待 debugstock 输出指定公共垃圾桶数量。"""
    deadline = time.time() + timeout
    latest = ""
    while time.time() < deadline:
        latest = external.read_text_since(server_log, offset)
        normalized = strip_ansi(latest)
        item_match = re.search(r"公共垃圾桶物品:\s*(\d+)", normalized)
        stack_match = re.search(r"堆叠\s*(\d+)", normalized)
        if item_match and stack_match:
            item_count = int(item_match.group(1))
            stack_count = int(stack_match.group(1))
            if item_count == expected_items and stack_count == expected_stacks:
                return {
                    "status": "PASS",
                    "text": latest,
                    "normalizedText": normalized,
                    "items": item_count,
                    "stacks": stack_count,
                }
        time.sleep(0.4)
    return {"status": "FAIL", "text": latest, "normalizedText": strip_ansi(latest)}


def spawn_cleanup_items(username: str, process: subprocess.Popen, command_log: Path) -> None:
    """生成要通过扫地进入公共垃圾桶的新版本掉落物。"""
    for item in CLEANUP_TRASH_ITEMS:
        command = "blwtc debugdrop " + username + " " + item["material"] + " " + str(item["amount"])
        send_console(process, command_log, command, 0.35)
    time.sleep(1.0)


def item_suffix(item_id: str) -> str:
    """把物品 ID 转为截图文件名可读后缀。"""
    return item_id.lower().replace("_", "-")


def total_amount(items: list[dict]) -> int:
    """统计验收物品总数量。"""
    return sum(int(item["amount"]) for item in items)


def render_text_image(text: str, target: Path, title: str) -> Path:
    """把关键日志片段渲染为 PNG 证据图。"""
    target.parent.mkdir(parents=True, exist_ok=True)
    font = guard.font()
    lines = [title, ""]
    for line in text.splitlines():
        lines.extend(wrap_line(line, 118))
    width = 1500
    line_height = 25
    height = max(260, line_height * (len(lines) + 2))
    image = Image.new("RGB", (width, height), (15, 23, 42))
    draw = ImageDraw.Draw(image)
    y = 18
    for index, line in enumerate(lines):
        color = (250, 204, 21) if index == 0 else (226, 232, 240)
        draw.text((22, y), line, fill=color, font=font)
        y += line_height
    image.save(target)
    return target


def wrap_line(line: str, max_chars: int) -> list[str]:
    """按固定字符宽度折行。"""
    if len(line) <= max_chars:
        return [line]
    result = []
    rest = line
    while len(rest) > max_chars:
        result.append(rest[:max_chars])
        rest = "  " + rest[max_chars:]
    result.append(rest)
    return result


def make_contact_sheet(paths: list[Path], target: Path) -> Path:
    """生成本轮客户端截图联系表。"""
    if not paths:
        return Path("")
    thumbs = []
    for path in paths:
        image = Image.open(path).convert("RGB")
        image.thumbnail((420, 240))
        canvas = Image.new("RGB", (440, 280), (15, 23, 42))
        canvas.paste(image, ((440 - image.width) // 2, 10))
        draw = ImageDraw.Draw(canvas)
        draw.text((10, 250), path.name[:56], fill=(226, 232, 240), font=guard.font())
        thumbs.append(canvas)
    columns = 3
    rows = (len(thumbs) + columns - 1) // columns
    sheet = Image.new("RGB", (columns * 440, rows * 280), (2, 6, 23))
    for index, thumb in enumerate(thumbs):
        sheet.paste(thumb, ((index % columns) * 440, (index // columns) * 280))
    target.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(target)
    return target


def run_case(case: dict, evidence_root: Path) -> dict:
    """运行新版本物品入桶的真实客户端截图验收。"""
    run_dir = evidence_root / case["id"]
    run_dir.mkdir(parents=True, exist_ok=True)
    server_log = run_dir / "logs" / (case["id"] + "-server-console.log")
    command_log = run_dir / "logs" / (case["id"] + "-console-commands.log")
    result = {"id": case["id"], "label": case["label"], "status": "FAIL", "screenshots": []}
    process = None
    client = None
    deploy = None
    config_backups = []
    try:
        process, deploy = launch_server_with_plugin(case, run_dir)
        result["deploy"] = deploy
        config_backups = write_test_config(case, run_dir)
        send_console(process, command_log, "blwtc reload", 1.0)
        prepared = base.ensure_client(case["version"])
        client, username, game_dir = base.launch_client(case, prepared, run_dir)
        base.ACTIVE_CLIENT_PID = client.pid
        result["username"] = username
        external.wait_player_online(case, username, server_log)
        setup_player(case, username, process, command_log)
        give_manual_items(username, process, command_log)
        open_screenshot = guard.send_command_and_screenshot(case, game_dir, run_dir, "/blwtc global", "manual-open-global-f2", 1.2)
        result["screenshots"].append(Path(open_screenshot))
        click_manual_hotbar_items(case)
        stock_offset = external.log_text_offset(server_log)
        send_console(process, command_log, "blwtc debugstock", 0.6)
        manual_expected_items = total_amount(MANUAL_TRASH_ITEMS)
        manual_expected_stacks = len(MANUAL_TRASH_ITEMS)
        cleanup_expected_items = total_amount(CLEANUP_TRASH_ITEMS)
        cleanup_expected_stacks = len(CLEANUP_TRASH_ITEMS)
        final_expected_items = manual_expected_items + cleanup_expected_items
        final_expected_stacks = manual_expected_stacks + cleanup_expected_stacks
        result["expected"] = {
            "manualItems": manual_expected_items,
            "manualStacks": manual_expected_stacks,
            "cleanupItems": cleanup_expected_items,
            "cleanupStacks": cleanup_expected_stacks,
            "finalItems": final_expected_items,
            "finalStacks": final_expected_stacks,
        }
        manual_stock = wait_stock(server_log, stock_offset, manual_expected_items, manual_expected_stacks)
        result["manualStock"] = manual_stock
        manual_full = capture_current(case, game_dir, run_dir, "manual-after-clicks-full-f2")
        result["screenshots"].append(manual_full)
        for slot, item in enumerate(MANUAL_TRASH_ITEMS):
            screenshot = capture_hovered_slot(
                case,
                game_dir,
                run_dir,
                slot,
                "manual-hover-" + item_suffix(item["id"]) + "-f2",
            )
            result["screenshots"].append(screenshot)
        pyautogui.press("esc")
        time.sleep(0.8)
        spawn_cleanup_items(username, process, command_log)
        clear_offset = external.log_text_offset(server_log)
        clear_screenshot = guard.send_command_and_screenshot(case, game_dir, run_dir, "/blwtc clear", "cleanup-clear-command-f2", 2.2)
        result["screenshots"].append(Path(clear_screenshot))
        cleanup_marker = "itemsRouted=" + str(cleanup_expected_items)
        cleanup_log = wait_server_text(server_log, clear_offset, ["[Cleanup]", cleanup_marker], 16.0)
        result["cleanupLogExcerpt"] = cleanup_log[-2400:]
        open_after = guard.send_command_and_screenshot(case, game_dir, run_dir, "/blwtc global", "cleanup-open-global-f2", 1.2)
        result["screenshots"].append(Path(open_after))
        for index, item in enumerate(CLEANUP_TRASH_ITEMS):
            screenshot = capture_hovered_slot(
                case,
                game_dir,
                run_dir,
                manual_expected_stacks + index,
                "cleanup-hover-" + item_suffix(item["material"]) + "-f2",
            )
            result["screenshots"].append(screenshot)
        final_stock_offset = external.log_text_offset(server_log)
        send_console(process, command_log, "blwtc debugstock", 0.6)
        final_stock = wait_stock(server_log, final_stock_offset, final_expected_items, final_expected_stacks)
        result["finalStock"] = final_stock
        server_text = external.read_text(server_log)
        result["legacyWarningForBLWorldTrashCan"] = "Legacy plugin BLWorldTrashCan" in server_text
        result["serverEvidenceScreenshot"] = render_text_image(
            "manualStock:\n" + manual_stock.get("text", "")[-1600:]
            + "\n\ncleanupLog:\n" + cleanup_log[-2000:]
            + "\n\nfinalStock:\n" + final_stock.get("text", "")[-1600:],
            run_dir / "server-screenshots" / (case["id"] + "-new-material-server-log.png"),
            "BLWorldTrashCan 新版本物品公共垃圾桶验收日志",
        )
        contact_sheet = make_contact_sheet([Path(item) for item in result["screenshots"]],
                                           evidence_root / "new-material-trash-contact-sheet.png")
        result["contactSheet"] = contact_sheet
        result["status"] = "PASS" if (
            manual_stock.get("status") == "PASS"
            and final_stock.get("status") == "PASS"
            and cleanup_marker in cleanup_log
            and not result["legacyWarningForBLWorldTrashCan"]
            and bool(result["screenshots"])
        ) else "FAIL"
    except Exception as exc:
        result["status"] = "FAIL"
        result["error"] = repr(exc)
        if "game_dir" in locals():
            try:
                failure = capture_current(case, game_dir, run_dir, "failure-f2")
                result.setdefault("screenshots", []).append(failure)
            except Exception as screenshot_error:
                result["failureScreenshotError"] = repr(screenshot_error)
        log("新材质验收失败: " + repr(exc))
    finally:
        if client is not None:
            external.stop_process(client)
        base.ACTIVE_CLIENT_PID = None
        restore_runtime_files(config_backups)
        if process is not None:
            close_server_process(process)
        if deploy is not None:
            restore_deployed_plugins(deploy)
    write_json(run_dir / "result.json", result)
    return result


def main() -> int:
    """运行新版本物品入桶真实客户端截图验收。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence-name", default="")
    parser.add_argument("--mc-version", default="1.21.4")
    parser.add_argument("--server-dir", default="")
    parser.add_argument("--server-jar", default="")
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--label", default="")
    args = parser.parse_args()
    run_id = args.evidence_name or ("new-material-trash-visual-" + time.strftime("%Y%m%d-%H%M%S"))
    evidence_root = EVIDENCE_ROOT / run_id
    evidence_root.mkdir(parents=True, exist_ok=True)
    case = build_case_from_args(args)
    result = run_case(case, evidence_root)
    summary = {"run": run_id, "result": result}
    write_json(evidence_root / "summary.json", summary)
    log("新材质入桶验收完成: status=" + result.get("status", "FAIL") + " evidence=" + str(evidence_root))
    return 0 if result.get("status") == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
