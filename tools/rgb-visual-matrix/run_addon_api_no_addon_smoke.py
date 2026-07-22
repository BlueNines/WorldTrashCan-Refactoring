import hashlib
import json
import shutil
import subprocess
import time
import zipfile
from pathlib import Path

import run_legacy_migration_matrix as legacy


REPO = legacy.REPO
BUILD_ROOT = REPO / "build" / "addon-api-no-addon-smoke"
EVIDENCE_ROOT = REPO / "docs" / "test-evidence"
UNIVERSAL_JAR = legacy.UNIVERSAL_JAR
JAVA8 = Path(r"C:\Program Files\Java\jdk-1.8\bin\java.exe")
JAVA21 = Path(r"C:\Program Files\Java\jdk-21\bin\java.exe")
JAVA25 = REPO / "build" / "tools" / "jre-25.0.3+9" / "bin" / "java.exe"
SPIGOT2612_JAR = Path(r"E:\server_work\spigot-26.1.2-test-server\spigot-26.1.2.jar")
FOLIA1218_JAR = Path(r"E:\server_work\folia1.21.8\folia-1.21.8-6.jar")


def log(message: str) -> None:
    """输出带时间戳的脚本日志。"""
    print(time.strftime("[%H:%M:%S] ") + message, flush=True)


def sha256_file(path: Path) -> str:
    """计算文件 SHA256。"""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def database_driver_entries(path: Path) -> list[str]:
    """返回插件 Jar 内不应出现的数据库驱动类。"""
    prefixes = ("org/sqlite/", "com/mysql/", "org/mariadb/", "com/zaxxer/hikari/")
    with zipfile.ZipFile(path) as archive:
        return [name for name in archive.namelist() if name.startswith(prefixes)]


def cases() -> list[dict]:
    """返回无附属插件退化回归的三个真实服务端用例。"""
    return [
        {
            "id": "paper1122",
            "label": "Paper 1.12.2",
            "serverJar": legacy.PAPER1122_JAR,
            "java": JAVA8,
            "expectedPlatform": "legacy-1.12",
            "copyRuntime": True,
            "javaArgs": [],
        },
        {
            "id": "spigot2612",
            "label": "Spigot 26.1.2",
            "serverJar": SPIGOT2612_JAR,
            "java": JAVA25,
            "expectedPlatform": "paper-1.16-1.20",
            "copyRuntime": False,
            "javaArgs": [
                "--add-opens", "java.base/java.net=ALL-UNNAMED",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:-UseAESCTRIntrinsics",
            ],
        },
        {
            "id": "folia1218",
            "label": "Folia 1.21.8",
            "serverJar": FOLIA1218_JAR,
            "java": JAVA21,
            "expectedPlatform": "folia-1.20",
            "copyRuntime": True,
            "javaArgs": [],
        },
    ]


def ensure_inputs(test_cases: list[dict]) -> None:
    """确认被测 Jar、服务端核心和 Java 运行时存在。"""
    paths = [UNIVERSAL_JAR]
    for case in test_cases:
        paths.extend((case["serverJar"], case["java"]))
    missing = [str(path) for path in paths if not path.is_file()]
    if missing:
        raise RuntimeError("缺少无附属插件 smoke 输入: " + "; ".join(missing))


def copy_runtime(case: dict, server_dir: Path) -> None:
    """复制已有服务端运行缓存，避免重复下载且不触碰原测试服。"""
    if not case["copyRuntime"]:
        return
    source_root = Path(case["serverJar"]).parent
    for name in ("cache", "libraries", "versions"):
        source = source_root / name
        if source.is_dir():
            shutil.copytree(source, server_dir / name, dirs_exist_ok=True)


def prepare_server(case: dict, run_root: Path) -> Path:
    """准备仅安装主插件 universal Jar 的隔离测试服。"""
    server_dir = run_root / case["id"] / "server"
    plugins_dir = server_dir / "plugins"
    plugins_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(case["serverJar"], server_dir / Path(case["serverJar"]).name)
    shutil.copy2(UNIVERSAL_JAR, plugins_dir / "WorldListTrashCan-universal.jar")
    copy_runtime(case, server_dir)
    (server_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    case["port"] = legacy.find_free_port()
    case["rcon"] = legacy.find_free_port()
    properties = legacy.make_server_properties(case["port"], case["rcon"])
    properties += (
        "spawn-animals=false\n"
        "spawn-monsters=false\n"
        "generate-structures=false\n"
        "allow-nether=false\n"
        "enforce-secure-profile=false\n"
        "level-type=default\n"
    )
    (server_dir / "server.properties").write_text(properties, encoding="utf-8")
    return server_dir


def wait_for_rcon(port: int, timeout_seconds: int = 360) -> None:
    """等待隔离服务端 RCON 就绪。"""
    deadline = time.time() + timeout_seconds
    last_error = None
    while time.time() < deadline:
        try:
            legacy.rcon_command(port, "list")
            return
        except Exception as error:
            last_error = error
            time.sleep(1.0)
    raise RuntimeError("等待 RCON 超时: " + repr(last_error))


def write_command_log(path: Path, responses: dict[str, str]) -> None:
    """按 UTF-8 保存完整 RCON 命令与响应。"""
    entries = []
    for command, response in responses.items():
        entries.append("> " + command + "\n" + response.rstrip())
    path.write_text("\n\n".join(entries) + "\n", encoding="utf-8")


def assert_case(case: dict, server_dir: Path, stdout_text: str,
                stderr_text: str, responses: dict[str, str]) -> dict:
    """断言未安装附属插件时主插件启动、帮助和清理行为均正常。"""
    combined = stdout_text + "\n" + stderr_text
    help_text = responses["wtc help"].lower()
    clear_text = responses["wtc clear true"].lower()
    plugins_text = responses["plugins"].lower()
    platform_text = responses["wtc platform"].lower()
    audit_dir = server_dir / "plugins" / "WorldListTrashCanAudit"
    checks = {
        "pluginEnabled": "worldlisttrashcan" in plugins_text
                         and "enabling worldlisttrashcan v7.0.0" in combined.lower(),
        "platformSelected": case["expectedPlatform"] in platform_text
                            or case["expectedPlatform"] in combined,
        "helpHasNoAudit": "audit" not in help_text,
        "clearAccepted": "unknown" not in clear_text
                         and "未知" not in clear_text
                         and "usage" not in clear_text,
        "serverStayedAlive": "disabling worldlisttrashcan" not in combined.lower(),
        "noAuditDirectory": not audit_dir.exists(),
        "noAuditRuntime": "worldlisttrashcanaudit" not in combined.lower(),
        "noDatabaseDriversInPluginJar": not database_driver_entries(UNIVERSAL_JAR),
        "noPluginError": not any(marker in combined for marker in (
            "[WorldListTrashCan] [Universal] 启动失败",
            "Could not load 'plugins\\WorldListTrashCan-universal.jar'",
            "Error occurred while enabling WorldListTrashCan",
        )),
    }
    failed = [name for name, passed in checks.items() if not passed]
    if failed:
        raise AssertionError(case["id"] + " 断言失败: " + ", ".join(failed))
    return {
        "id": case["id"],
        "label": case["label"],
        "expectedPlatform": case["expectedPlatform"],
        "checks": checks,
        "passed": True,
    }


def run_case(case: dict, run_root: Path, evidence_dir: Path) -> dict:
    """启动一个真实服务端并执行无附属插件回归。"""
    log("启动 " + case["label"])
    server_dir = prepare_server(case, run_root)
    case_evidence = evidence_dir / case["id"]
    case_evidence.mkdir(parents=True, exist_ok=True)
    stdout_path = case_evidence / "server-stdout.log"
    stderr_path = case_evidence / "server-stderr.log"
    responses = {}
    launch = [
        str(case["java"]), "-Dfile.encoding=UTF-8", "-Xms512M", "-Xmx1536M",
    ] + case["javaArgs"] + [
        "-jar", Path(case["serverJar"]).name, "nogui",
    ]
    with stdout_path.open("w", encoding="utf-8", errors="replace") as stdout, \
            stderr_path.open("w", encoding="utf-8", errors="replace") as stderr:
        process = subprocess.Popen(launch, cwd=server_dir, stdout=stdout, stderr=stderr)
        try:
            wait_for_rcon(case["rcon"])
            for command in ("plugins", "wtc platform", "wtc help", "wtc clear true", "wtc stats"):
                responses[command] = legacy.rcon_command(case["rcon"], command)
                time.sleep(0.5)
            if process.poll() is not None:
                raise RuntimeError(case["id"] + " 在 smoke 命令期间异常退出")
            time.sleep(1.5)
            stdout.flush()
            stderr.flush()
            result = assert_case(
                case,
                server_dir,
                stdout_path.read_text(encoding="utf-8", errors="replace"),
                stderr_path.read_text(encoding="utf-8", errors="replace"),
                responses,
            )
            legacy.stop_server(process, case["rcon"])
        finally:
            legacy.terminate_server(process)
    write_command_log(case_evidence / "rcon-commands.log", responses)
    latest = server_dir / "logs" / "latest.log"
    if latest.is_file():
        shutil.copy2(latest, case_evidence / "latest.log")
    return result


def write_summary(evidence_dir: Path, results: list[dict]) -> dict:
    """保存 JSON 汇总和面向交接的简短 README。"""
    summary = {
        "testedJar": str(UNIVERSAL_JAR),
        "jarSha256": sha256_file(UNIVERSAL_JAR),
        "addonInstalled": False,
        "allPassed": all(result["passed"] for result in results),
        "results": results,
    }
    (evidence_dir / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    lines = [
        "# 附属插件 API 无附属插件退化回归",
        "",
        "- 被测产物: `dist/WorldListTrashCan-universal.jar`",
        "- SHA256: `" + summary["jarSha256"] + "`",
        "- 附属插件: 未安装",
        "- 结论: " + ("PASS" if summary["allPassed"] else "FAIL"),
        "",
        "## 覆盖",
        "",
        "- Paper 1.12.2",
        "- Spigot 26.1.2",
        "- Folia 1.21.8",
        "- 主插件启动、平台分支、`/wtc help` 无 `audit`、`/wtc clear true`、无审计目录和数据库运行迹象",
        "",
        "每个服务端目录保留 `server-stdout.log`、`server-stderr.log`、`latest.log` 和 `rcon-commands.log`。",
        "隔离测试服及其 `world*`、`cache`、`libraries` 保留在仓库 `build/addon-api-no-addon-smoke/`。",
        "",
    ]
    (evidence_dir / "README.md").write_text("\n".join(lines), encoding="utf-8")
    return summary


def main() -> None:
    """执行三端真实服务端无附属插件退化回归。"""
    test_cases = cases()
    ensure_inputs(test_cases)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    run_root = BUILD_ROOT / stamp
    evidence_dir = EVIDENCE_ROOT / ("addon-api-no-addon-smoke-" + stamp)
    run_root.mkdir(parents=True, exist_ok=True)
    evidence_dir.mkdir(parents=True, exist_ok=True)
    results = []
    for case in test_cases:
        results.append(run_case(case, run_root, evidence_dir))
    summary = write_summary(evidence_dir, results)
    log("无附属插件 smoke " + ("PASS" if summary["allPassed"] else "FAIL"))
    log("证据目录: " + str(evidence_dir))


if __name__ == "__main__":
    main()
