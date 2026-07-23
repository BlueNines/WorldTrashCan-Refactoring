import hashlib
import json
import shutil
import subprocess
import time
from pathlib import Path

import run_legacy_migration_matrix as legacy


EVIDENCE_ROOT = legacy.REPO / "docs" / "test-evidence"
BUILD_ROOT = legacy.REPO / "build" / "legacy-migration-platform-matrix"
JAVA25 = legacy.REPO / "build" / "tools" / "jre-25.0.3+9" / "bin" / "java.exe"
SPIGOT2612 = Path(r"E:\server_work\spigot-26.1.2-test-server\spigot-26.1.2.jar")
FOLIA1218 = Path(r"E:\server_work\folia1.21.8\folia-1.21.8-6.jar")


def log(message: str) -> None:
    """输出带时间戳的测试进度。"""
    print(time.strftime("[%H:%M:%S] ") + message, flush=True)


def sha256_file(path: Path) -> str:
    """流式计算文件 SHA-256。"""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path: Path, value) -> None:
    """按 UTF-8 写入 JSON 证据。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def cases() -> list[dict]:
    """返回现代 Spigot 和 Folia 迁移用例。"""
    return [
        {
            "id": "spigot2612",
            "label": "Spigot 26.1.2",
            "serverJar": SPIGOT2612,
            "java": JAVA25,
            "expectedPlatform": "paper-1.16-1.20",
            "port": legacy.find_free_port(),
            "rcon": legacy.find_free_port(),
        },
        {
            "id": "folia1218",
            "label": "Folia 1.21.8",
            "serverJar": FOLIA1218,
            "java": JAVA25,
            "expectedPlatform": "folia-1.20",
            "port": legacy.find_free_port(),
            "rcon": legacy.find_free_port(),
        },
    ]


def ensure_inputs() -> None:
    """确认跨平台迁移所需输入存在。"""
    required = [JAVA25, SPIGOT2612, FOLIA1218, legacy.UNIVERSAL_JAR,
                legacy.LEGACY_RESOURCES / "config.yml",
                legacy.LEGACY_RESOURCES / "data" / "data.yml"]
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise RuntimeError("缺少跨平台迁移测试输入: " + "; ".join(missing))


def prepare_server(case: dict, run_root: Path) -> Path:
    """准备只包含最终整包和旧版原始配置的隔离服务端。"""
    server_dir = run_root / case["id"] / "server"
    if server_dir.exists():
        shutil.rmtree(server_dir)
    plugin_root = server_dir / "plugins" / "WorldListTrashCan"
    (plugin_root / "data").mkdir(parents=True)
    shutil.copy2(case["serverJar"], server_dir / Path(case["serverJar"]).name)
    shutil.copy2(legacy.UNIVERSAL_JAR, server_dir / "plugins" / "WorldListTrashCan-universal.jar")
    shutil.copy2(legacy.LEGACY_RESOURCES / "config.yml", plugin_root / "config.yml")
    shutil.copy2(legacy.LEGACY_RESOURCES / "data" / "data.yml", plugin_root / "data" / "data.yml")
    shutil.copytree(legacy.LEGACY_RESOURCES / "message", plugin_root / "message")
    (plugin_root / "logs").mkdir()
    (plugin_root / "logs" / "legacy-platform.log").write_text(
        "legacy-platform-log-must-be-kept\n", encoding="utf-8")
    (plugin_root / "custom-platform.yml").write_text(
        "custom-platform: keep\n", encoding="utf-8")
    (server_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (server_dir / "server.properties").write_text(
        legacy.make_server_properties(case["port"], case["rcon"]), encoding="utf-8")
    return server_dir


def run_phase(case: dict, server_dir: Path, case_dir: Path, phase: str) -> dict[str, str]:
    """启动一次目标服务端并保存命令和完整控制台日志。"""
    phase_dir = case_dir / "logs" / phase
    phase_dir.mkdir(parents=True, exist_ok=True)
    stdout_path = phase_dir / "server-stdout.log"
    stderr_path = phase_dir / "server-stderr.log"
    with stdout_path.open("w", encoding="utf-8", errors="replace") as stdout, \
            stderr_path.open("w", encoding="utf-8", errors="replace") as stderr:
        process = subprocess.Popen(
            [str(case["java"]), "-Xms512M", "-Xmx1536M", "-jar",
             Path(case["serverJar"]).name, "nogui"],
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
            responses = {}
            entries = []
            for command in ("plugins", "wtc platform", "wtc stats", "wtc debugnotify 0"):
                body = legacy.rcon_command(case["rcon"], command)
                responses[command] = body
                entries.append("> " + command + "\n" + body.rstrip())
                time.sleep(0.5)
            (phase_dir / "rcon-commands.log").write_text(
                "\n\n".join(entries) + "\n", encoding="utf-8")
            legacy.stop_server(process, case["rcon"])
        finally:
            legacy.terminate_server(process)
    source_latest = server_dir / "logs" / "latest.log"
    if source_latest.is_file():
        shutil.copy2(source_latest, phase_dir / "latest.log")
    return responses


def assert_case(case: dict, server_dir: Path, first: dict[str, str], second: dict[str, str],
                marker_before: bytes) -> dict:
    """断言跨平台迁移结果、备份和重启幂等性。"""
    data_dir = server_dir / "plugins" / "WorldListTrashCan"
    backup = data_dir / "old-version-config"
    marker = backup / "migration-complete.yml"
    expected_files = [
        data_dir / "config.yml", data_dir / "cleanup.yml", data_dir / "trash.yml",
        data_dir / "protections.yml", data_dir / "entity-limits.yml",
        data_dir / "data" / "worlds.yml", marker, backup / "migration-report.md",
        backup / "logs" / "legacy-platform.log", backup / "custom-platform.yml",
    ]
    missing = [str(path) for path in expected_files if not path.is_file()]
    if missing:
        raise AssertionError(case["id"] + " 迁移后缺少文件: " + "; ".join(missing))
    if marker.read_bytes() != marker_before:
        raise AssertionError(case["id"] + " 第二次启动改写了完成标记")
    for source in [legacy.LEGACY_RESOURCES / "config.yml",
                   legacy.LEGACY_RESOURCES / "data" / "data.yml"]:
        archived = backup / source.relative_to(legacy.LEGACY_RESOURCES)
        if sha256_file(source) != sha256_file(archived):
            raise AssertionError(case["id"] + " 旧配置备份字节不一致: " + source.name)
    for source in (legacy.LEGACY_RESOURCES / "message").glob("*.yml"):
        archived = backup / "message" / source.name
        if not archived.is_file() or sha256_file(source) != sha256_file(archived):
            raise AssertionError(case["id"] + " 旧语言文件备份字节不一致: " + source.name)
    for responses in (first, second):
        if "WorldListTrashCan" not in responses.get("plugins", ""):
            raise AssertionError(case["id"] + " 插件未启用: " + responses.get("plugins", ""))
        platform = responses.get("wtc platform", "")
        if case["expectedPlatform"] not in platform or "universal" not in platform:
            raise AssertionError(case["id"] + " 平台分支错误: " + platform)
        stats = responses.get("wtc stats", "")
        if "公共垃圾桶页数" not in stats or "5" not in stats:
            raise AssertionError(case["id"] + " 未加载迁移后的 MaxPage=5: " + stats)
    report = (backup / "migration-report.md").read_text(encoding="utf-8")
    if report.count("新版语言键结构已变化") != 8:
        raise AssertionError(case["id"] + " 旧语言文件报告数量不是 8")
    marker_text = marker.read_text(encoding="utf-8")
    for needle in ("status: complete", "target-config-schema-version: 2", "target-plugin-version: \"7.0.0\""):
        if needle not in marker_text:
            raise AssertionError(case["id"] + " 完成标记缺少 " + needle)
    return {
        "id": case["id"],
        "label": case["label"],
        "passed": True,
        "platform": case["expectedPlatform"],
        "messageFiles": 8,
        "markerUnchanged": True,
    }


def run_case(case: dict, run_root: Path, evidence_dir: Path) -> dict:
    """执行一个现代平台旧配置迁移与重启用例。"""
    log("准备跨平台迁移用例 " + case["id"])
    server_dir = prepare_server(case, run_root)
    case_dir = evidence_dir / case["id"]
    first = run_phase(case, server_dir, case_dir, "01-first-start")
    marker = server_dir / "plugins" / "WorldListTrashCan" / "old-version-config" / "migration-complete.yml"
    if not marker.is_file():
        raise AssertionError(case["id"] + " 首次启动没有生成完成标记")
    marker_before = marker.read_bytes()
    second = run_phase(case, server_dir, case_dir, "02-marker-restart")
    result = assert_case(case, server_dir, first, second, marker_before)
    generated = case_dir / "generated-plugin-data"
    if generated.exists():
        shutil.rmtree(generated)
    shutil.copytree(server_dir / "plugins" / "WorldListTrashCan", generated)
    result["commands"] = {"first": first, "restart": second}
    return result


def write_readme(evidence_dir: Path, summary: dict) -> None:
    """写入跨平台旧配置迁移证据说明。"""
    lines = [
        "# WorldListTrashCan 旧配置跨平台迁移矩阵",
        "",
        "- 被测产物: `dist/WorldListTrashCan-universal.jar`",
        "- SHA256: `" + summary["jarSha256"] + "`",
        "- 输入: 旧版 6.9.8 原始 `config.yml`、`data/data.yml` 和 8 份语言文件",
        "- 验收: 首次迁移、字节级备份、运行时读取、完成标记、第二次启动幂等",
        "- 结论: " + ("PASS" if summary["allPassed"] else "FAIL"),
        "",
    ]
    for result in summary["results"]:
        lines.extend([
            "## " + result["label"],
            "",
            "- 平台分支: `" + result["platform"] + " (universal)`",
            "- 完成标记重启未改写: `true`",
            "- 旧语言文件备份及报告: `8/8`",
            "- 证据: `" + result["id"] + "/`",
            "",
        ])
    (evidence_dir / "README.md").write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    """执行现代 Spigot 与 Folia 旧配置迁移矩阵。"""
    ensure_inputs()
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    run_root = BUILD_ROOT / timestamp
    evidence_dir = EVIDENCE_ROOT / ("legacy-migration-platform-" + timestamp)
    run_root.mkdir(parents=True, exist_ok=True)
    evidence_dir.mkdir(parents=True, exist_ok=True)
    results = [run_case(case, run_root, evidence_dir) for case in cases()]
    summary = {
        "timestamp": timestamp,
        "allPassed": all(result["passed"] for result in results),
        "jar": str(legacy.UNIVERSAL_JAR),
        "jarSha256": sha256_file(legacy.UNIVERSAL_JAR),
        "results": results,
    }
    write_json(evidence_dir / "summary.json", summary)
    write_readme(evidence_dir, summary)
    log("跨平台旧配置迁移矩阵完成: " + str(evidence_dir))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
