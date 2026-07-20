import argparse
import json
import shutil
import sys
import time
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

import run_cleanup_guard_visual_matrix as guard
import run_rgb_external_server_matrix as external
import run_rgb_visual_matrix as base


EVIDENCE_ROOT = base.REPO / "docs" / "test-evidence"
TARGET_CASE_IDS = [
    "managed_paper1122",
    "external_folia1218",
    "external_paper2612",
    "external_spigot2612",
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
    """按参数选择本轮要跑的服务端用例，并强制使用 universal 整包。"""
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
    raise RuntimeError("未知手动扫地 guards 覆盖测试用例: " + case_id)


def scenarios() -> list[dict]:
    """返回默认、显式 true、显式 false 三个核心验收场景。"""
    return [
        {
            "id": "default-bypass",
            "name": "/wtc clear 默认忽略 guards",
            "command": "/wtc clear",
            "expectGuard": False,
        },
        {
            "id": "explicit-true-bypass",
            "name": "/wtc clear true 忽略 guards",
            "command": "/wtc clear true",
            "expectGuard": False,
        },
        {
            "id": "explicit-false-guard",
            "name": "/wtc clear false 遵守 guards",
            "command": "/wtc clear false",
            "expectGuard": True,
        },
    ]


def write_strict_guard_config(case: dict) -> None:
    """写入足以证明手动覆盖效果的严格 guards 配置。"""
    guard.write_cleanup_guard_config(case, min_online=2, min_entities=400)


def purge_test_items(case: dict, username: str, process, command_log: Path) -> None:
    """清掉玩家附近或世界内的测试掉落物，避免场景之间串扰。"""
    if str(case["version"]) == "1.12.2":
        commands = [
            "minecraft:kill @e[type=Item]",
            "minecraft:tp " + username + " 0 91 -8 0 15",
        ]
    else:
        commands = [
            "minecraft:kill @e[type=minecraft:item]",
            "minecraft:tp " + username + " 0 91 -8 0 15",
        ]
    for command in commands:
        guard.run_console(process, command_log, command, 0.15)


def expected_markers(case: dict, expect_guard: bool) -> list[str]:
    """返回服务端日志需要出现的断言标记。"""
    if expect_guard:
        return ["skippedByGuard=true"]
    return ["skippedByGuard=false", "itemsRouted=1"]


def render_server_log_screenshot(text: str, target: Path, title: str) -> Path:
    """把服务端关键日志渲染为 PNG 截图证据。"""
    target.parent.mkdir(parents=True, exist_ok=True)
    used_font = font()
    lines = [title, ""]
    useful = []
    for line in text.splitlines():
        if "Cleanup" in line or "FoliaCleanup" in line or "issued server command" in line or "扫地" in line or "清理" in line:
            useful.append(line)
    lines.extend(useful[-38:] if useful else text.splitlines()[-38:])
    width = 1560
    line_height = 26
    height = max(240, line_height * (len(lines) + 2))
    image = Image.new("RGB", (width, height), (15, 23, 42))
    draw = ImageDraw.Draw(image)
    y = 18
    for index, line in enumerate(lines):
        color = (250, 204, 21) if index == 0 else (226, 232, 240)
        draw.text((22, y), line[:190], fill=color, font=used_font)
        y += line_height
    image.save(target)
    return target


def font() -> ImageFont.ImageFont:
    """返回支持中文的截图字体。"""
    for path in (Path(r"C:\Windows\Fonts\msyh.ttc"), Path(r"C:\Windows\Fonts\simsun.ttc")):
        if path.is_file():
            return ImageFont.truetype(str(path), 18)
    return ImageFont.load_default()


def screenshot_info(path: Path) -> dict:
    """返回截图文件基础校验信息。"""
    return guard.screenshot_info(path)


def run_scenario(case: dict, username: str, process, game_dir: Path, run_dir: Path,
                 server_log: Path, command_log: Path, scenario: dict) -> dict:
    """运行一个手动扫地 guards 覆盖场景并返回证据。"""
    log(case["id"] + " 场景 " + scenario["id"])
    write_strict_guard_config(case)
    guard.reload_plugin(process, command_log)
    purge_test_items(case, username, process, command_log)
    guard.spawn_test_drops(username, process, command_log, 1)
    offset = external.log_text_offset(server_log)
    clear_shot = guard.send_command_and_screenshot(
        case, game_dir, run_dir, scenario["command"], scenario["id"] + "-clear", 2.8
    )
    if guard.is_folia(case):
        time.sleep(4.0)
    stats_shot = guard.send_command_and_screenshot(
        case, game_dir, run_dir, "/wtc stats", scenario["id"] + "-stats", 1.6
    )
    markers = expected_markers(case, bool(scenario["expectGuard"]))
    text = guard.wait_server_marker(server_log, offset, markers, 22.0)
    config_snapshot = guard.copy_scenario_config(case, run_dir, scenario["id"])
    server_shot = render_server_log_screenshot(
        "严格 guards 配置: min-online-players=2, min-total-entities=400\n"
        + "客户端命令: " + scenario["command"] + "\n\n"
        + text,
        run_dir / "server-screenshots" / (case["id"] + "-" + scenario["id"] + "-server-log.png"),
        case["label"] + " / " + scenario["name"],
    )
    status = "PASS" if all(marker in text for marker in markers) else "FAIL"
    return {
        "id": scenario["id"],
        "name": scenario["name"],
        "command": scenario["command"],
        "status": status,
        "expectedMarkers": markers,
        "serverExcerpt": text[-2600:],
        "configSnapshot": str(config_snapshot),
        "clientScreenshots": [screenshot_info(clear_shot), screenshot_info(stats_shot)],
        "serverScreenshot": screenshot_info(server_shot),
    }


def run_case(case: dict, prepared_clients: dict, evidence_root: Path) -> dict:
    """运行单个服务端的真实客户端手动扫地验收。"""
    case = dict(case)
    run_dir = evidence_root / case["id"]
    run_dir.mkdir(parents=True, exist_ok=True)
    case["runId"] = evidence_root.name
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
        backups.append(guard.backup_file(Path(case["serverDir"]) / "plugins" / "WorldListTrashCan" / "cleanup.yml", backup_dir))
        prepared = prepared_clients[case["version"]]
        client, username, game_dir = base.launch_client(case, prepared, run_dir)
        base.ACTIVE_CLIENT_PID = client.pid
        result["username"] = username
        external.wait_player_online(case, username, server_log)
        guard.setup_player(case, username, process, command_log)
        platform_offset = external.log_text_offset(server_log)
        guard.run_console(process, command_log, "wtc platform", 0.8)
        guard.wait_platform_output(server_log, platform_offset)
        for scenario in scenarios():
            scenario_result = run_scenario(case, username, process, game_dir, run_dir, server_log, command_log, scenario)
            result["scenarios"].append(scenario_result)
            write_json(run_dir / "result.json", result)
        failed = [item for item in result["scenarios"] if item["status"] != "PASS"]
        result["status"] = "PASS" if not failed else "FAIL"
    except Exception as exc:
        result["status"] = "FAIL"
        result["error"] = repr(exc)
        if "game_dir" in locals():
            try:
                result["failureScreenshot"] = str(guard.capture_named_screenshot(case, game_dir, run_dir, "failure"))
            except Exception:
                pass
        log("用例失败 " + case["id"] + ": " + repr(exc))
    finally:
        if client is not None:
            external.stop_process(client)
        base.ACTIVE_CLIENT_PID = None
        if process is not None:
            guard.restore_backups(backups)
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
    target = evidence_root / "manual-clear-guard-override-contact-sheet.png"
    sheet.save(target)
    return target


def main() -> int:
    """运行手动扫地 guards 覆盖真实客户端截图验收。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", default="")
    args = parser.parse_args()
    run_id = "manual-clear-guard-override-visual-" + time.strftime("%Y%m%d-%H%M%S")
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
    log("手动扫地 guards 覆盖验收完成: total=" + str(len(results))
        + " failed=" + str(len(failed)) + " evidence=" + str(evidence_root))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
