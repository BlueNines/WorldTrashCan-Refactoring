import argparse
import json
import re
import shutil
import sys
import time
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

import run_cleanup_guard_visual_matrix as guard
import run_rgb_external_server_matrix as external
import run_rgb_visual_matrix as base


EVIDENCE_ROOT = base.REPO / "docs" / "test-evidence"


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


def ensure_cleanup_guard_block(text: str) -> str:
    """确保 cleanup.yml 中存在 guards 配置块。"""
    if "\nguards:" in text or text.startswith("guards:"):
        return text
    return text.rstrip() + (
        "\n# AI 公共垃圾桶刷新测试临时补入，测试结束后恢复原文件。\n"
        "guards:\n"
        "  min-online-players: 1\n"
        "  min-total-entities: 0\n"
    )


def write_refresh_test_config(case: dict) -> None:
    """写入公共垃圾桶三轮刷新测试配置。"""
    data_dir = Path(case["serverDir"]) / "plugins" / "BlWorldTrashCan"
    cleanup = data_dir / "cleanup.yml"
    trash = data_dir / "trash.yml"
    if not cleanup.is_file() or not trash.is_file():
        raise RuntimeError("缺少 cleanup.yml 或 trash.yml，无法写入刷新测试配置: " + str(data_dir))
    cleanup_text = ensure_cleanup_guard_block(cleanup.read_text(encoding="utf-8", errors="replace"))
    cleanup.write_text(external.update_yaml_scalars(cleanup_text, {
        "interval-seconds": "0",
        "guards.min-online-players": "1",
        "guards.min-total-entities": "0",
    }), encoding="utf-8")
    trash_text = trash.read_text(encoding="utf-8", errors="replace")
    trash.write_text(external.update_yaml_scalars(trash_text, {
        "world-trash.enabled": "false",
        "personal-trash.enabled": "false",
        "global-trash.enabled": "true",
        "global-trash.clear-every-cleanups": "3",
    }), encoding="utf-8")


def client_stdout_path(case: dict, run_dir: Path) -> Path:
    """返回当前客户端 stdout 日志路径。"""
    return run_dir / "logs" / (case["id"] + "-client-stdout.log")


def wait_client_stock(stdout_path: Path, offset: int, expected_items: int, timeout: float = 12.0) -> tuple[str, int]:
    """等待客户端日志出现 debugstock 输出并解析公共垃圾桶物品数。"""
    deadline = time.time() + timeout
    latest = ""
    pattern = re.compile(r"公共垃圾桶(?:当前)?物品[:：]\s*(\d+)")
    while time.time() < deadline:
        latest = external.read_text_since(stdout_path, offset)
        matches = pattern.findall(latest)
        if matches:
            value = int(matches[-1])
            if value == expected_items:
                return latest, value
        time.sleep(0.4)
    matches = pattern.findall(latest)
    value = int(matches[-1]) if matches else -1
    return latest, value


def font() -> ImageFont.ImageFont:
    """返回支持中文的截图字体。"""
    return guard.font()


def wrap_line(line: str, max_chars: int) -> list[str]:
    """把长日志行按固定字符数换行。"""
    if len(line) <= max_chars:
        return [line]
    result = []
    rest = line
    while len(rest) > max_chars:
        result.append(rest[:max_chars])
        rest = "  " + rest[max_chars:]
    result.append(rest)
    return result


def render_text_screenshot(text: str, target: Path, title: str) -> Path:
    """把关键日志渲染成自动换行的 PNG 截图。"""
    target.parent.mkdir(parents=True, exist_ok=True)
    used_font = font()
    lines = [title, ""]
    for source_line in text.splitlines():
        lines.extend(wrap_line(source_line, 118))
    width = 1500
    line_height = 25
    height = max(260, line_height * (len(lines) + 2))
    image = Image.new("RGB", (width, height), (15, 23, 42))
    draw = ImageDraw.Draw(image)
    y = 18
    for index, line in enumerate(lines):
        color = (250, 204, 21) if index == 0 else (226, 232, 240)
        draw.text((22, y), line, fill=color, font=used_font)
        y += line_height
    image.save(target)
    return target


def render_round_screenshot(case: dict, round_result: dict, target: Path) -> Path:
    """把单轮服务端和客户端关键文本渲染成服务端侧证据图。"""
    text = (
        "配置: global-trash.clear-every-cleanups=3, world-trash.enabled=false, personal-trash.enabled=false\n"
        "期望库存: " + str(round_result["expectedStock"]) + "\n"
        "解析库存: " + str(round_result["actualStock"]) + "\n\n"
        "服务端清理日志:\n" + round_result["serverExcerpt"] + "\n\n"
        "客户端 debugstock 日志:\n" + round_result["clientExcerpt"]
    )
    return render_text_screenshot(
        text,
        target,
        case["label"] + " / 第 " + str(round_result["round"]) + " 轮公共垃圾桶刷新",
    )


def run_refresh_round(case: dict, username: str, process, game_dir: Path, run_dir: Path,
                      server_log: Path, command_log: Path, round_index: int) -> dict:
    """运行一轮 debugdrop -> clear -> debugstock 并保存截图。"""
    expected_stock = 1 if round_index == 3 else round_index
    expected_refresh = "true" if round_index == 3 else "false"
    log(case["id"] + " 第 " + str(round_index) + " 轮刷新测试")
    guard.run_console(process, command_log, "blwtc debugdrop " + username + " STONE 1", 0.8)
    if guard.is_folia(case):
        time.sleep(1.5)
    server_offset = external.log_text_offset(server_log)
    clear_shot = guard.send_command_and_screenshot(
        case, game_dir, run_dir, "/blwtc clear", "round-" + str(round_index) + "-clear", 4.5 if guard.is_folia(case) else 2.0
    )
    if guard.is_folia(case):
        time.sleep(4.0)
    server_text = guard.wait_server_marker(
        server_log,
        server_offset,
        ["itemsRouted=1", "globalTrashRefreshed=" + expected_refresh],
        24.0,
    )
    stock_offset = external.log_text_offset(client_stdout_path(case, run_dir))
    stock_shot = guard.send_command_and_screenshot(
        case, game_dir, run_dir, "/blwtc debugstock", "round-" + str(round_index) + "-debugstock", 1.5
    )
    client_text, actual_stock = wait_client_stock(client_stdout_path(case, run_dir), stock_offset, expected_stock, 12.0)
    round_result = {
        "round": round_index,
        "status": "PASS" if actual_stock == expected_stock
        and "itemsRouted=1" in server_text
        and ("globalTrashRefreshed=" + expected_refresh) in server_text else "FAIL",
        "expectedStock": expected_stock,
        "actualStock": actual_stock,
        "expectedGlobalTrashRefreshed": expected_refresh,
        "serverExcerpt": server_text[-2400:],
        "clientExcerpt": client_text[-1600:],
        "clientScreenshots": [guard.screenshot_info(clear_shot), guard.screenshot_info(stock_shot)],
    }
    server_shot = render_round_screenshot(
        case,
        round_result,
        run_dir / "server-screenshots" / (case["id"] + "-round-" + str(round_index) + "-server-log.png"),
    )
    round_result["serverScreenshot"] = guard.screenshot_info(server_shot)
    return round_result


def run_case(case: dict, prepared_clients: dict, evidence_root: Path) -> dict:
    """运行单个服务端公共垃圾桶三轮刷新验收。"""
    case = dict(case)
    run_dir = evidence_root / case["id"]
    run_dir.mkdir(parents=True, exist_ok=True)
    case["runId"] = evidence_root.name
    log("开始公共垃圾桶刷新用例 " + case["id"] + " / " + case["label"])
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
        "rounds": [],
    }
    try:
        process = external.launch_server(case, run_dir)
        backup_dir = run_dir / "logs" / "config-backup"
        backups.append(guard.backup_file(Path(case["serverDir"]) / "plugins" / "BlWorldTrashCan" / "cleanup.yml", backup_dir))
        backups.append(guard.backup_file(Path(case["serverDir"]) / "plugins" / "BlWorldTrashCan" / "trash.yml", backup_dir))
        prepared = prepared_clients[case["version"]]
        client, username, game_dir = base.launch_client(case, prepared, run_dir)
        base.ACTIVE_CLIENT_PID = client.pid
        result["username"] = username
        external.wait_player_online(case, username, server_log)
        guard.setup_player(case, username, process, command_log)
        write_refresh_test_config(case)
        guard.reload_plugin(process, command_log)
        platform_offset = external.log_text_offset(server_log)
        guard.run_console(process, command_log, "blwtc platform", 0.8)
        guard.wait_platform_output(server_log, platform_offset)
        for round_index in (1, 2, 3):
            round_result = run_refresh_round(
                case, username, process, game_dir, run_dir, server_log, command_log, round_index
            )
            result["rounds"].append(round_result)
            write_json(run_dir / "result.json", result)
        failed = [item for item in result["rounds"] if item["status"] != "PASS"]
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
        for round_result in result.get("rounds", []):
            for item in round_result.get("clientScreenshots", []):
                screenshots.append(Path(item["path"]))
            if round_result.get("serverScreenshot"):
                screenshots.append(Path(round_result["serverScreenshot"]["path"]))
    if not screenshots:
        return Path("")
    thumbs = []
    for path in screenshots:
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
    target = evidence_root / "global-refresh-contact-sheet.png"
    sheet.save(target)
    return target


def main() -> int:
    """运行公共垃圾桶三轮刷新真实客户端截图矩阵。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", default="")
    args = parser.parse_args()
    run_id = "global-refresh-visual-" + time.strftime("%Y%m%d-%H%M%S")
    evidence_root = EVIDENCE_ROOT / run_id
    evidence_root.mkdir(parents=True, exist_ok=True)
    cases = guard.selected_cases(args.case or None)
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
    log("公共垃圾桶刷新矩阵完成: total=" + str(len(results)) + " failed=" + str(len(failed))
        + " evidence=" + str(evidence_root))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
