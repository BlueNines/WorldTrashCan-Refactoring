#!/usr/bin/env python3
"""用真实 1.12.2 客户端验证旧配置迁移后的玩家可见行为。"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
PROJECT_ROOT = REPO.parents[2]
SERVER = PROJECT_ROOT / "paper-1.12.2-test-server"
PLUGINS = SERVER / "plugins"
PLUGIN_DATA = PLUGINS / "WorldListTrashCan"
PLUGIN_JAR = PLUGINS / "WorldListTrashCan-universal.jar"
AUDIT_JAR = PLUGINS / "WorldListTrashCanAudit.jar"
UNIVERSAL_JAR = REPO / "dist" / "WorldListTrashCan-universal.jar"
LEGACY_RESOURCES = (REPO.parent / "WorldListTrashCan旧版本" / "WorldListTrashCan"
                    / "src" / "main" / "resources")
RUNNER_WORKSPACE = PROJECT_ROOT / "客户端自动化测试工作区"
RUNNER_SRC = RUNNER_WORKSPACE / "runner" / "src"
RUNNER_RUNS = RUNNER_WORKSPACE / "runs"
STOP_SCRIPT = RUNNER_WORKSPACE / "scripts" / "stop_paper_test_server.ps1"
VISUAL_MAX_PAGES = 7
VISUAL_CHAT_MARKER = "LEGACY_MIGRATION_CHAT_OK"


def sha256_file(path: Path) -> str:
    """计算文件 SHA-256。"""
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def replace_once(text: str, old: str, new: str) -> str:
    """只替换一次明确的旧配置片段，避免误改同名配置。"""
    count = text.count(old)
    if count != 1:
        raise RuntimeError("旧配置片段数量不是 1: " + old + " count=" + str(count))
    return text.replace(old, new, 1)


def replace_first(text: str, old: str, new: str) -> str:
    """替换当前文本中首次出现的旧配置片段。"""
    if old not in text:
        raise RuntimeError("旧配置缺少片段: " + old)
    return text.replace(old, new, 1)


def customize_legacy_config(path: Path) -> None:
    """把真实旧版配置改成截图中容易识别的迁移样本。"""
    text = path.read_text(encoding="utf-8")
    text = replace_once(text, "    MaxPage: 5", "    MaxPage: 7")
    text = replace_first(text, '#        Material: "ARROW"', '        Material: "FEATHER"')
    text = replace_first(text, '#        Material: "ARROW"', '        Material: "STICK"')
    text = replace_once(text, '#        Material: "BLACK_STAINED_GLASS_PANE"',
                        '        Material: "STAINED_GLASS_PANE"')
    section_pattern = re.compile(
        r"(?ms)(^  ChatMessageForCount:[ \t]*$.*?)(?=^  [A-Za-z][^\r\n]*:[ \t]*$)"
    )
    section_match = section_pattern.search(text)
    if section_match is None:
        raise RuntimeError("旧配置缺少 ChatMessageForCount 段")
    section = section_match.group(1)
    replacement = "    - 0;&a" + VISUAL_CHAT_MARKER + " pages=" + str(VISUAL_MAX_PAGES)
    next_section, replaced = re.subn(r"(?m)^    - 0;.*$", replacement, section, count=1)
    if replaced != 1:
        raise RuntimeError("旧 ChatMessageForCount 缺少 0 秒消息")
    text = text[:section_match.start(1)] + next_section + text[section_match.end(1):]
    path.write_text(text, encoding="utf-8", newline="\n")


def ensure_inputs() -> None:
    """确认客户端迁移验收依赖均存在且 25565 端口空闲。"""
    required = [
        UNIVERSAL_JAR,
        LEGACY_RESOURCES / "config.yml",
        LEGACY_RESOURCES / "data" / "data.yml",
        LEGACY_RESOURCES / "message",
        RUNNER_SRC,
        STOP_SCRIPT,
    ]
    missing = [str(path) for path in required if not path.exists()]
    if missing:
        raise RuntimeError("缺少客户端迁移验收输入: " + "; ".join(missing))
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.settimeout(0.5)
        if probe.connect_ex(("127.0.0.1", 25565)) == 0:
            raise RuntimeError("25565 已被占用，拒绝改动测试服状态")


def move_if_exists(source: Path, target: Path, moved: list[tuple[Path, Path]]) -> None:
    """把现有测试服文件完整移入本轮暂存区。"""
    if not source.exists():
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(source), str(target))
    moved.append((source, target))


def stage_legacy_server(staging: Path, evidence: Path,
                        moved: list[tuple[Path, Path]]) -> None:
    """暂存当前插件状态并部署真实旧版配置与本轮 universal Jar。"""
    original = staging / "original-server-state"
    move_if_exists(PLUGIN_DATA, original / "WorldListTrashCan", moved)
    move_if_exists(PLUGIN_JAR, original / PLUGIN_JAR.name, moved)
    move_if_exists(AUDIT_JAR, original / AUDIT_JAR.name, moved)

    PLUGIN_DATA.mkdir(parents=True, exist_ok=True)
    (PLUGIN_DATA / "data").mkdir(parents=True, exist_ok=True)
    shutil.copy2(LEGACY_RESOURCES / "config.yml", PLUGIN_DATA / "config.yml")
    shutil.copy2(LEGACY_RESOURCES / "data" / "data.yml", PLUGIN_DATA / "data" / "data.yml")
    shutil.copytree(LEGACY_RESOURCES / "message", PLUGIN_DATA / "message")
    customize_legacy_config(PLUGIN_DATA / "config.yml")
    shutil.copy2(UNIVERSAL_JAR, PLUGIN_JAR)

    source_snapshot = evidence / "legacy-input-before-start"
    shutil.copytree(PLUGIN_DATA, source_snapshot)
    manifest = {
        "source": str(LEGACY_RESOURCES),
        "jar": str(UNIVERSAL_JAR),
        "jarSha256": sha256_file(UNIVERSAL_JAR),
        "configSha256": sha256_file(PLUGIN_DATA / "config.yml"),
        "dataSha256": sha256_file(PLUGIN_DATA / "data" / "data.yml"),
        "visualMaxPages": VISUAL_MAX_PAGES,
        "visualChatMarker": VISUAL_CHAT_MARKER,
        "disabledDuringIsolation": [str(AUDIT_JAR)] if (original / AUDIT_JAR.name).exists() else [],
    }
    (evidence / "legacy-input-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )


def run_command(command: list[str], cwd: Path, log_file: Path,
                env: dict[str, str] | None = None, timeout: int = 900) -> subprocess.CompletedProcess[str]:
    """执行命令并把完整标准输出与错误输出保存为 UTF-8。"""
    result = subprocess.run(
        command,
        cwd=str(cwd),
        env=env,
        text=True,
        encoding="utf-8",
        errors="replace",
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=timeout,
        check=False,
    )
    log_file.parent.mkdir(parents=True, exist_ok=True)
    log_file.write_text(result.stdout or "", encoding="utf-8")
    return result


def run_idle_preflight(evidence: Path) -> None:
    """通过 Runner 门禁确认测试服、客户端和资源锁均空闲。"""
    env = os.environ.copy()
    env["PYTHONPATH"] = str(RUNNER_SRC)
    command = [
        sys.executable, "-m", "ai_client_lab.cli", "runner-idle-environment-canary",
        "--wait-timeout", "15", "--poll-interval", "3",
    ]
    result = run_command(command, RUNNER_WORKSPACE, evidence / "runner-idle-preflight.log", env, 180)
    if result.returncode != 0:
        raise RuntimeError("Runner 空闲环境门禁未通过，详见 runner-idle-preflight.log")


def run_client_canary(evidence: Path, previous_runs: set[str]) -> Path:
    """让 Runner 启动真实客户端并验证迁移后的聊天与公共垃圾桶 GUI。"""
    env = os.environ.copy()
    env["PYTHONPATH"] = str(RUNNER_SRC)
    command = [
        sys.executable, "-m", "ai_client_lab.cli", "client-instance-server-chat-canary",
        "--server-resource", "server:paper-1.12.2-test-server",
        "--client-resource", "client:legacy-migration-visual-a",
        "--client-id", "legacy-migration-visual-a",
        "--chat-action", "chat-sequence",
        "--chat-command", "/wtc stats||/wtc debugnotify 0||/wtc globaltrash",
        "--expected-screen-simple-name", "GuiChest",
        "--required-server-marker", "[Migration]",
        "--required-server-marker", "old-version-config/migration-report.md",
        "--required-client-chat", "公共垃圾桶页数: " + str(VISUAL_MAX_PAGES),
        "--required-client-chat", VISUAL_CHAT_MARKER,
        "--min-screenshots", "5",
        "--skip-advanced-utf8-assertion",
        "--start-timeout", "300",
        "--client-timeout", "420",
        "--stop-timeout", "120",
    ]
    result = run_command(command, RUNNER_WORKSPACE, evidence / "runner-client-canary.log", env, 900)
    new_runs = [path for path in RUNNER_RUNS.iterdir()
                if path.is_dir() and path.name not in previous_runs]
    if not new_runs:
        raise RuntimeError("Runner 未生成新的客户端 run")
    run = max(new_runs, key=lambda path: path.stat().st_mtime)
    if result.returncode != 0:
        raise RuntimeError("真实客户端 canary 未通过，run=" + str(run))
    return run


def assert_contains(path: Path, needles: list[str], checks: list[dict]) -> None:
    """断言 UTF-8 文件包含全部关键迁移值。"""
    if not path.is_file():
        raise RuntimeError("缺少迁移产物: " + str(path))
    text = path.read_text(encoding="utf-8", errors="replace")
    for needle in needles:
        passed = needle in text
        checks.append({"file": str(path), "needle": needle, "passed": passed})
        if not passed:
            raise RuntimeError("迁移产物缺少内容: " + str(path) + " -> " + needle)


def validate_migration(evidence: Path, runner_run: Path) -> list[dict]:
    """验证备份哈希、运行配置、客户端聊天和 GUI 截图均对应本轮迁移。"""
    checks: list[dict] = []
    input_manifest = json.loads((evidence / "legacy-input-manifest.json").read_text(encoding="utf-8"))
    backup = PLUGIN_DATA / "old-version-config"
    archived_config = backup / "config.yml"
    archived_data = backup / "data" / "data.yml"
    if sha256_file(archived_config) != input_manifest["configSha256"]:
        raise RuntimeError("旧 config.yml 备份 SHA-256 与启动前输入不一致")
    if sha256_file(archived_data) != input_manifest["dataSha256"]:
        raise RuntimeError("旧 data.yml 备份 SHA-256 与启动前输入不一致")
    checks.extend([
        {"check": "legacyConfigBackupSha256", "passed": True},
        {"check": "legacyDataBackupSha256", "passed": True},
    ])
    assert_contains(PLUGIN_DATA / "trash.yml", [
        "max-pages: " + str(VISUAL_MAX_PAGES),
        "- FEATHER",
        "- STICK",
        "- STAINED_GLASS_PANE",
    ], checks)
    assert_contains(PLUGIN_DATA / "cleanup.yml", [VISUAL_CHAT_MARKER], checks)
    assert_contains(backup / "migration-complete.yml", [
        "status: complete", "target-config-schema-version: 2", "source-sha256:"
    ], checks)
    assert_contains(backup / "migration-report.md", [
        "old-version-config", "新版语言键结构已变化"
    ], checks)

    runner_logs = runner_run / "logs"
    assert_contains(runner_logs / "client-chat.log", [
        "公共垃圾桶页数: " + str(VISUAL_MAX_PAGES), VISUAL_CHAT_MARKER
    ], checks)
    assert_contains(runner_logs / "client-instance-server-chat-assertion.txt", [
        "status=PASS"
    ], checks)
    assert_contains(runner_logs / "client-instance-player-chat-command-assertion.txt", [
        "status=PASS", "/wtc stats", "/wtc debugnotify 0", "/wtc globaltrash"
    ], checks)
    screenshots = list((runner_run / "screenshots").glob("*.png"))
    gui_screenshots = [path for path in screenshots if "expected_screen_GuiChest" in path.name]
    if len(screenshots) < 5 or len(gui_screenshots) < 1:
        raise RuntimeError("客户端截图不足: total=" + str(len(screenshots))
                           + " gui=" + str(len(gui_screenshots)))
    checks.append({"check": "clientScreenshots", "passed": True, "count": len(screenshots)})
    checks.append({"check": "clientGuiScreenshots", "passed": True,
                   "count": len(gui_screenshots)})
    return checks


def preserve_test_state(evidence: Path, runner_run: Path) -> None:
    """把本轮迁移后的插件目录、Runner run 和服务端日志完整移入证据目录。"""
    target_data = evidence / "server-plugin-data-after-client"
    if PLUGIN_DATA.exists():
        shutil.move(str(PLUGIN_DATA), str(target_data))
    deployed = evidence / "deployed-artifact"
    deployed.mkdir(parents=True, exist_ok=True)
    if PLUGIN_JAR.exists():
        shutil.move(str(PLUGIN_JAR), str(deployed / PLUGIN_JAR.name))
    runner_target = evidence / "runner-run"
    shutil.copytree(runner_run, runner_target)
    latest_log = SERVER / "logs" / "latest.log"
    if latest_log.is_file():
        shutil.copy2(latest_log, evidence / "server-latest.log")


def stop_server(evidence: Path) -> None:
    """调用 Runner 受控停服脚本，避免恢复配置时仍有服务端进程。"""
    command = [
        "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
        "-File", str(STOP_SCRIPT), "-TimeoutSeconds", "120",
    ]
    try:
        run_command(command, RUNNER_WORKSPACE, evidence / "server-stop-finally.log", timeout=180)
    except (OSError, subprocess.SubprocessError) as exception:
        (evidence / "server-stop-finally-error.txt").write_text(
            type(exception).__name__ + ": " + str(exception), encoding="utf-8"
        )


def restore_server(moved: list[tuple[Path, Path]]) -> None:
    """恢复测试前存在的主插件数据、Jar 和审计附属插件 Jar。"""
    for original, backup in reversed(moved):
        if not backup.exists():
            raise RuntimeError("测试服恢复源丢失: " + str(backup))
        original.parent.mkdir(parents=True, exist_ok=True)
        if original.exists():
            raise RuntimeError("测试服恢复目标已存在，拒绝覆盖: " + str(original))
        shutil.move(str(backup), str(original))


def write_summary(evidence: Path, status: str, checks: list[dict],
                  runner_run: Path | None, error: str) -> None:
    """写出机器可读结果和面向人工复核的证据索引。"""
    summary = {
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "status": status,
        "jar": str(UNIVERSAL_JAR),
        "jarSha256": sha256_file(UNIVERSAL_JAR) if UNIVERSAL_JAR.is_file() else "",
        "runnerRun": str(runner_run or ""),
        "checks": checks,
        "error": error,
        "manualScreenshotReviewRequired": True,
    }
    (evidence / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    lines = [
        "# 旧配置迁移真实客户端验收",
        "",
        "- 自动断言: `" + status + "`",
        "- universal Jar SHA-256: `" + summary["jarSha256"] + "`",
        "- 旧配置可辨识值: `MaxPage=7`、`FEATHER/STICK/STAINED_GLASS_PANE`、`"
        + VISUAL_CHAT_MARKER + "`",
        "- Runner 原始 run: `runner-run/`",
        "- 迁移后服务端目录: `server-plugin-data-after-client/`",
        "- 下一步: 必须逐张打开 `runner-run/screenshots/*.png`，确认聊天与 GuiChest 画面真实可见后，才能把人工截图验收改为通过。",
    ]
    if error:
        lines.extend(["", "- 错误: `" + error.replace("`", "'") + "`"])
    (evidence / "README.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    """执行客户端迁移验收并在任何结果下恢复测试服原状态。"""
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    evidence = REPO / "docs" / "test-evidence" / ("legacy-migration-client-" + timestamp)
    staging = REPO / "build" / "legacy-migration-client" / timestamp
    evidence.mkdir(parents=True, exist_ok=False)
    staging.mkdir(parents=True, exist_ok=False)
    moved: list[tuple[Path, Path]] = []
    checks: list[dict] = []
    runner_run: Path | None = None
    server_staged = False
    error = ""
    exit_code = 2
    try:
        ensure_inputs()
        run_idle_preflight(evidence)
        previous_runs = {path.name for path in RUNNER_RUNS.iterdir() if path.is_dir()}
        server_staged = True
        stage_legacy_server(staging, evidence, moved)
        runner_run = run_client_canary(evidence, previous_runs)
        checks = validate_migration(evidence, runner_run)
        preserve_test_state(evidence, runner_run)
        exit_code = 0
    except Exception as exception:
        error = type(exception).__name__ + ": " + str(exception)
    finally:
        stop_server(evidence)
        if server_staged and PLUGIN_DATA.exists():
            failed_data = evidence / "server-plugin-data-after-failure"
            if not failed_data.exists():
                shutil.move(str(PLUGIN_DATA), str(failed_data))
        if server_staged and PLUGIN_JAR.exists():
            failed_artifact = evidence / "deployed-artifact-after-failure"
            failed_artifact.mkdir(parents=True, exist_ok=True)
            shutil.move(str(PLUGIN_JAR), str(failed_artifact / PLUGIN_JAR.name))
        try:
            restore_server(moved)
        except Exception as restore_exception:
            restore_error = type(restore_exception).__name__ + ": " + str(restore_exception)
            error = (error + "; " if error else "") + "RESTORE " + restore_error
            exit_code = 3
        write_summary(evidence, "PASS" if exit_code == 0 else "FAIL", checks,
                      runner_run, error)
    print("evidence=" + str(evidence))
    print("status=" + ("PASS" if exit_code == 0 else "FAIL"))
    if error:
        print("error=" + error)
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
