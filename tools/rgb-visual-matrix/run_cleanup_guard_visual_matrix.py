import argparse
import hashlib
import json
import shutil
import sys
import time
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

import run_rgb_external_server_matrix as external
import run_rgb_visual_matrix as base


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
    """按参数选择本轮要跑的服务端用例。"""
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
    raise RuntimeError("未知扫地门禁测试用例: " + case_id)


def backup_file(path: Path, backup_dir: Path) -> dict:
    """备份一个可能存在的文件。"""
    backup = backup_dir / (path.name + ".before")
    backup.parent.mkdir(parents=True, exist_ok=True)
    if path.is_file():
        shutil.copy2(path, backup)
        return {"target": path, "backup": backup, "existed": True}
    return {"target": path, "backup": backup, "existed": False}


def restore_backups(backups: list[dict]) -> None:
    """恢复本轮测试修改过的文件。"""
    for item in backups:
        target = Path(item["target"])
        backup = Path(item["backup"])
        if item.get("existed") and backup.is_file():
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(backup, target)
        elif not item.get("existed") and target.is_file():
            target.unlink()


def ensure_cleanup_guard_block(text: str) -> str:
    """确保 cleanup.yml 中存在 guards 配置块。"""
    if "\nguards:" in text or text.startswith("guards:"):
        return text
    block = (
        "\n# AI 扫地门禁测试临时补入，测试结束后恢复原文件。\n"
        "guards:\n"
        "  min-online-players: 1\n"
        "  min-total-entities: 400\n"
    )
    return text.rstrip() + "\n" + block


def write_cleanup_guard_config(case: dict, min_online: int, min_entities: int) -> None:
    """写入本轮测试需要的扫地门禁配置。"""
    target = Path(case["serverDir"]) / "plugins" / "BLWorldTrashCan" / "cleanup.yml"
    if not target.is_file():
        raise RuntimeError("cleanup.yml 不存在，无法写入门禁测试配置: " + str(target))
    original = ensure_cleanup_guard_block(target.read_text(encoding="utf-8", errors="replace"))
    updated = external.update_yaml_scalars(original, {
        "interval-seconds": "0",
        "guards.min-online-players": str(min_online),
        "guards.min-total-entities": str(min_entities),
    })
    target.write_text(updated, encoding="utf-8")


def is_legacy(case: dict) -> bool:
    """判断当前客户端是否是 1.12.2。"""
    return str(case["version"]) == "1.12.2"


def is_folia(case: dict) -> bool:
    """判断当前服务端是否是 Folia。"""
    label = str(case.get("label", "")).lower()
    source = str(case.get("sourceId", "")).lower()
    return "folia" in label or "folia" in source


def run_console(process, command_log: Path, command: str, wait: float = 0.25) -> None:
    """发送服务端控制台命令并短暂等待。"""
    external.send_console_command(process, command, command_log)
    time.sleep(wait)


def setup_player(case: dict, username: str, process, command_log: Path) -> None:
    """初始化玩家权限、位置和基础游戏规则。"""
    commands = [
        "op " + username,
        "minecraft:gamerule sendCommandFeedback false",
        "minecraft:gamerule commandBlockOutput false",
        "minecraft:gamerule doMobSpawning false",
        "minecraft:time set day",
        "minecraft:weather clear",
    ]
    if is_legacy(case):
        commands.extend([
            "minecraft:gamemode 1 " + username,
            "minecraft:tp " + username + " 0 91 -8 0 15",
            "minecraft:kill @e[type=Item]",
        ])
    else:
        commands.extend([
            "minecraft:gamemode creative " + username,
            "minecraft:tp " + username + " 0 91 -8 0 15",
            "minecraft:kill @e[type=minecraft:item]",
        ])
    for command in commands:
        run_console(process, command_log, command, 0.15)
    time.sleep(1.0)


def reload_plugin(process, command_log: Path) -> None:
    """重载插件配置并等待其生效。"""
    run_console(process, command_log, "blwtc reload", 1.0)


def spawn_test_drops(username: str, process, command_log: Path, count: int) -> None:
    """用插件调试命令生成指定数量的可清理掉落物实体。"""
    for _ in range(count):
        run_console(process, command_log, "blwtc debugdrop " + username + " STONE 1", 0.2)
    time.sleep(1.0)


def focus_game(case: dict) -> int:
    """聚焦 Minecraft 窗口并返回窗口句柄。"""
    hwnd = base.find_minecraft_window(case["version"])
    rect = base.focus_window(hwnd)
    base.click_game(hwnd, rect, 0.50, 0.34)
    time.sleep(0.4)
    return hwnd


def send_client_command(case: dict, command: str) -> None:
    """从真实客户端聊天框发送玩家命令。"""
    hwnd = focus_game(case)
    base.send_chat_line_by_window_message(hwnd, command)


def capture_named_screenshot(case: dict, game_dir: Path, run_dir: Path, name: str) -> Path:
    """截取 F2 截图并复制为稳定文件名。"""
    source = base.capture_internal_screenshot(case, game_dir, run_dir)
    target = run_dir / "screenshots" / (case["id"] + "-" + name + ".png")
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)
    return target


def send_command_and_screenshot(case: dict, game_dir: Path, run_dir: Path, command: str,
                                name: str, wait: float = 1.5) -> Path:
    """发送客户端命令并截图。"""
    send_client_command(case, command)
    time.sleep(wait)
    return capture_named_screenshot(case, game_dir, run_dir, name)


def read_since(path: Path, offset: int) -> str:
    """读取日志偏移后的新增文本。"""
    return external.read_text_since(path, offset)


def wait_server_marker(server_log: Path, offset: int, markers: list[str], timeout: float = 12.0) -> str:
    """等待服务端日志出现指定标记并返回新增文本。"""
    deadline = time.time() + timeout
    last = ""
    while time.time() < deadline:
        text = read_since(server_log, offset)
        if text:
            last = text
        if all(marker in text for marker in markers):
            return text
        time.sleep(0.4)
    return last


def wait_platform_output(server_log: Path, offset: int) -> None:
    """等待 platform 命令输出，兼容 Folia 控制台 ANSI 颜色。"""
    deadline = time.time() + 12
    while time.time() < deadline:
        text = read_since(server_log, offset)
        if "Unknown or incomplete command" in text or "Unknown command" in text:
            raise RuntimeError("blwtc platform 未被服务端识别: " + str(server_log))
        if "rgb-message" in text or "scheduler-region" in text or "当前平台" in text:
            return
        time.sleep(0.4)
    raise TimeoutError("未看到 blwtc platform 的插件输出: " + str(server_log))


def font() -> ImageFont.ImageFont:
    """返回支持中文的截图字体。"""
    for path in (Path(r"C:\Windows\Fonts\msyh.ttc"), Path(r"C:\Windows\Fonts\simsun.ttc")):
        if path.is_file():
            return ImageFont.truetype(str(path), 18)
    return ImageFont.load_default()


def render_server_log_screenshot(text: str, target: Path, title: str) -> Path:
    """把服务端关键日志渲染为 PNG 截图证据。"""
    target.parent.mkdir(parents=True, exist_ok=True)
    used_font = font()
    lines = [title, ""]
    useful = []
    for line in text.splitlines():
        if "Cleanup" in line or "BLWorldTrashCan" in line or "blwtc" in line or "扫地" in line:
            useful.append(line)
    lines.extend(useful[-34:] if useful else text.splitlines()[-34:])
    width = 1500
    line_height = 26
    height = max(220, line_height * (len(lines) + 2))
    image = Image.new("RGB", (width, height), (15, 23, 42))
    draw = ImageDraw.Draw(image)
    y = 18
    for index, line in enumerate(lines):
        color = (250, 204, 21) if index == 0 else (226, 232, 240)
        draw.text((22, y), line[:180], fill=color, font=used_font)
        y += line_height
    image.save(target)
    return target


def screenshot_info(path: Path) -> dict:
    """返回截图文件基础校验信息。"""
    data = path.read_bytes()
    image = Image.open(path)
    return {
        "path": str(path),
        "size": path.stat().st_size,
        "sha256": hashlib.sha256(data).hexdigest(),
        "dimensions": list(image.size),
        "brightness": base.image_brightness(image),
    }


def run_guard_scenario(case: dict, username: str, process, game_dir: Path, run_dir: Path,
                       server_log: Path, command_log: Path, scenario: dict) -> dict:
    """运行一个扫地门禁场景并返回证据。"""
    log(case["id"] + " 场景 " + scenario["id"])
    write_cleanup_guard_config(case, scenario["minOnline"], scenario["minEntities"])
    reload_plugin(process, command_log)
    if scenario.get("drops", 0) > 0:
        spawn_test_drops(username, process, command_log, int(scenario["drops"]))
    offset = external.log_text_offset(server_log)
    clear_shot = send_command_and_screenshot(case, game_dir, run_dir, "/blwtc clear",
                                             scenario["id"] + "-clear", scenario.get("clearWait", 2.5))
    if is_folia(case):
        time.sleep(4.0)
    stats_shot = send_command_and_screenshot(case, game_dir, run_dir, "/blwtc stats",
                                             scenario["id"] + "-stats", 1.5)
    markers = ["skippedByGuard=true"] if scenario["expectGuard"] else ["skippedByGuard=false"]
    text = wait_server_marker(server_log, offset, markers, 18.0)
    server_shot = render_server_log_screenshot(
        text,
        run_dir / "server-screenshots" / (case["id"] + "-" + scenario["id"] + "-server-log.png"),
        case["label"] + " / " + scenario["name"],
    )
    return {
        "id": scenario["id"],
        "name": scenario["name"],
        "status": "PASS" if all(marker in text for marker in markers) else "FAIL",
        "expectedMarkers": markers,
        "serverExcerpt": text[-2400:],
        "clientScreenshots": [screenshot_info(clear_shot), screenshot_info(stats_shot)],
        "serverScreenshot": screenshot_info(server_shot),
    }


def scenarios() -> list[dict]:
    """返回本轮三个核心扫地门禁测试场景。"""
    return [
        {
            "id": "online-guard",
            "name": "在线人数不足跳过",
            "minOnline": 2,
            "minEntities": 0,
            "drops": 0,
            "expectGuard": True,
        },
        {
            "id": "entity-guard",
            "name": "目标实体数量不足跳过",
            "minOnline": 1,
            "minEntities": 400,
            "drops": 3,
            "expectGuard": True,
        },
        {
            "id": "guard-pass-clean",
            "name": "达到低阈值后正常清理",
            "minOnline": 1,
            "minEntities": 1,
            "drops": 1,
            "expectGuard": False,
            "clearWait": 3.0,
        },
    ]


def run_case(case: dict, prepared_clients: dict, evidence_root: Path) -> dict:
    """运行单个服务端的扫地门禁测试。"""
    case = dict(case)
    run_dir = evidence_root / case["id"]
    run_dir.mkdir(parents=True, exist_ok=True)
    case["runId"] = evidence_root.name
    log("开始扫地门禁用例 " + case["id"] + " / " + case["label"])
    process = None
    client = None
    backups = []
    server_log = run_dir / "logs" / (case["id"] + "-server-console.log")
    command_log = run_dir / "logs" / (case["id"] + "-console-commands.log")
    result = {
        "id": case["id"],
        "label": case["label"],
        "version": case.get("displayVersion", case["version"]),
        "serverDir": str(case["serverDir"]),
        "plugin": case["plugin"],
        "status": "FAIL",
        "artifact": external.artifact_summary_for_plugin(case),
        "scenarios": [],
    }
    try:
        process = external.launch_server(case, run_dir)
        backup_dir = run_dir / "logs" / "config-backup"
        backups.append(backup_file(Path(case["serverDir"]) / "plugins" / "BLWorldTrashCan" / "cleanup.yml", backup_dir))
        prepared = prepared_clients[case["version"]]
        client, username, game_dir = base.launch_client(case, prepared, run_dir)
        base.ACTIVE_CLIENT_PID = client.pid
        result["username"] = username
        external.wait_player_online(case, username, server_log)
        setup_player(case, username, process, command_log)
        platform_offset = external.log_text_offset(server_log)
        run_console(process, command_log, "blwtc platform", 0.8)
        wait_platform_output(server_log, platform_offset)
        for scenario in scenarios():
            scenario_result = run_guard_scenario(
                case, username, process, game_dir, run_dir, server_log, command_log, scenario
            )
            result["scenarios"].append(scenario_result)
            write_json(run_dir / "result.json", result)
        failed = [item for item in result["scenarios"] if item["status"] != "PASS"]
        result["status"] = "PASS" if not failed else "FAIL"
    except Exception as exc:
        result["status"] = "FAIL"
        result["error"] = repr(exc)
        if "game_dir" in locals():
            try:
                result["failureScreenshot"] = str(capture_named_screenshot(case, game_dir, run_dir, "failure"))
            except Exception:
                pass
        log("用例失败 " + case["id"] + ": " + repr(exc))
    finally:
        if client is not None:
            external.stop_process(client)
        base.ACTIVE_CLIENT_PID = None
        if process is not None:
            restore_backups(backups)
            external.stop_process(process, "stop")
    write_json(run_dir / "result.json", result)
    return result


def make_contact_sheet(results: list[dict], evidence_root: Path) -> Path:
    """生成所有客户端与服务端截图的联系表。"""
    screenshots = []
    for result in results:
        for scenario in result.get("scenarios", []):
            for item in scenario.get("clientScreenshots", []):
                screenshots.append(Path(item["path"]))
            if scenario.get("serverScreenshot"):
                screenshots.append(Path(scenario["serverScreenshot"]["path"]))
    if not screenshots:
        return Path("")
    thumbs = []
    for path in screenshots:
        image = Image.open(path).convert("RGB")
        image.thumbnail((420, 240))
        canvas = Image.new("RGB", (440, 280), (15, 23, 42))
        canvas.paste(image, ((440 - image.width) // 2, 10))
        draw = ImageDraw.Draw(canvas)
        draw.text((10, 250), path.name[:56], fill=(226, 232, 240), font=font())
        thumbs.append(canvas)
    columns = 3
    rows = (len(thumbs) + columns - 1) // columns
    sheet = Image.new("RGB", (columns * 440, rows * 280), (2, 6, 23))
    for index, thumb in enumerate(thumbs):
        sheet.paste(thumb, ((index % columns) * 440, (index // columns) * 280))
    target = evidence_root / "cleanup-guard-contact-sheet.png"
    sheet.save(target)
    return target


def main() -> int:
    """运行扫地门禁真实客户端截图矩阵。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", default="")
    args = parser.parse_args()
    run_id = "cleanup-guard-visual-" + time.strftime("%Y%m%d-%H%M%S")
    evidence_root = EVIDENCE_ROOT / run_id
    evidence_root.mkdir(parents=True, exist_ok=True)
    cases = selected_cases(args.case or None)
    prepared_clients = {}
    results = []
    for case in cases:
        prepared_clients.setdefault(case["version"], base.ensure_client(case["version"]))
        results.append(run_case(case, prepared_clients, evidence_root))
        write_json(evidence_root / "summary.json", {"run": run_id, "results": results})
    contact_sheet = make_contact_sheet(results, evidence_root)
    summary = {"run": run_id, "results": results, "contactSheet": str(contact_sheet)}
    write_json(evidence_root / "summary.json", summary)
    failed = [item for item in results if item.get("status") != "PASS"]
    log("扫地门禁矩阵完成: total=" + str(len(results)) + " failed=" + str(len(failed))
        + " evidence=" + str(evidence_root))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
