import hashlib
import json
import re
import shutil
import subprocess
import time
from pathlib import Path

import run_legacy_migration_matrix as legacy


REPO = Path(__file__).resolve().parents[2]
WORKSPACE = REPO.parents[2]
SOURCE_SERVER = WORKSPACE / "paper-1.21.4-test-server"
PAPER_JAR = SOURCE_SERVER / "paper-1.21.4-232.jar"
JAVA21 = Path(r"C:\Program Files\Java\jdk-21\bin\java.exe")
UNIVERSAL_JAR = REPO / "dist" / "WorldListTrashCan-universal.jar"
BUILD_ROOT = REPO / "build" / "sheep-clear-animals-paper1214"
EVIDENCE_ROOT = REPO / "docs" / "test-evidence"
SHEEP_SELECTOR = "@e[type=minecraft:sheep,tag=AI_WTC_SHEEP]"


def log(message: str) -> None:
    """输出带时间戳的专项进度。"""
    print(time.strftime("[%H:%M:%S] ") + message, flush=True)


def sha256_file(path: Path) -> str:
    """计算文件 SHA-256。"""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path: Path, value) -> None:
    """按 UTF-8 写入机器可读 JSON。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def ensure_inputs() -> None:
    """确认 Paper、Java 和最终 universal Jar 均存在。"""
    required = (PAPER_JAR, JAVA21, UNIVERSAL_JAR)
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise RuntimeError("缺少 Paper 1.21.4 羊清理专项输入: " + "; ".join(missing))


def map_repository() -> None:
    """把中文仓库路径映射到 ASCII 盘符供 Paperclip 使用。"""
    mapping = subprocess.run(["subst"], check=True, capture_output=True, text=True)
    if "Q:\\" in mapping.stdout.upper():
        raise RuntimeError("Q: 已被占用，无法创建 Paper 1.21.4 专项路径映射")
    subprocess.run(["subst", "Q:", str(REPO)], check=True)


def unmap_repository() -> None:
    """解除本轮 Paper 1.21.4 专项使用的临时盘符。"""
    subprocess.run(["subst", "Q:", "/D"], check=False)


def ascii_repo_path(path: Path) -> Path:
    """把仓库内路径转换成 Q 盘符下的等价路径。"""
    return Path("Q:/") / path.resolve().relative_to(REPO.resolve())


def prepare_server(run_root: Path, port: int, rcon_port: int) -> Path:
    """创建不会修改长期测试服的隔离 Paper 1.21.4 运行目录。"""
    server_dir = run_root / "server"
    plugins = server_dir / "plugins"
    plugins.mkdir(parents=True, exist_ok=True)
    shutil.copy2(PAPER_JAR, server_dir / PAPER_JAR.name)
    shutil.copy2(UNIVERSAL_JAR, plugins / "WorldListTrashCan-universal.jar")
    for name in ("cache", "libraries", "versions"):
        source = SOURCE_SERVER / name
        if source.is_dir():
            shutil.copytree(source, server_dir / name, dirs_exist_ok=True)
    (server_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    properties = legacy.make_server_properties(port, rcon_port)
    properties += (
        "spawn-animals=false\nspawn-monsters=false\ngenerate-structures=false\n"
        "allow-nether=false\nenforce-secure-profile=false\n"
    )
    (server_dir / "server.properties").write_text(properties, encoding="utf-8")
    return server_dir


def set_clear_animals(cleanup_file: Path, enabled: bool) -> str:
    """只切换 entities.clear-animals，并校验实体总开关保持开启。"""
    text = cleanup_file.read_text(encoding="utf-8")
    false_line = "  clear-animals: false"
    true_line = "  clear-animals: true"
    matches = text.count(false_line) + text.count(true_line)
    if matches != 1:
        raise RuntimeError("cleanup.yml 中 clear-animals 标量数量异常: " + str(matches))
    text = text.replace(true_line if not enabled else false_line,
                        true_line if enabled else false_line, 1)
    if "  enabled: true" not in text:
        raise RuntimeError("entities.enabled 未保持 true，不能验证 clear-animals 独立语义")
    cleanup_file.write_text(text, encoding="utf-8")
    return text


def run_command(rcon_port: int, command: str, entries: list[str]) -> str:
    """执行并记录一条完整 RCON 命令响应。"""
    response = legacy.rcon_command(rcon_port, command)
    entries.append("> " + command + "\n" + response.rstrip())
    return response


def probe_sheep(rcon_port: int, entries: list[str]) -> int:
    """通过 scoreboard 返回带专项 Tag 的羊是否仍存在。"""
    run_command(rcon_port, "scoreboard players set sheep_probe ai_wtc_sheep 0", entries)
    run_command(rcon_port, "execute if entity " + SHEEP_SELECTOR
                + " run scoreboard players set sheep_probe ai_wtc_sheep 1", entries)
    response = run_command(rcon_port, "scoreboard players get sheep_probe ai_wtc_sheep", entries)
    match = re.search(r"\b([01])\b", response)
    if match is None:
        raise RuntimeError("无法从 scoreboard 响应读取羊存活值: " + response)
    return int(match.group(1))


def wait_cleanup_line(latest_log: Path, offset: int) -> str:
    """等待本轮正式清理摘要写入 latest.log。"""
    deadline = time.time() + 30
    while time.time() < deadline:
        if latest_log.is_file():
            text = latest_log.read_text(encoding="utf-8", errors="replace")
            added = text[offset:] if offset < len(text) else ""
            lines = [line for line in added.splitlines() if "[Cleanup]" in line]
            if lines:
                return lines[-1]
        time.sleep(0.2)
    raise RuntimeError("等待 WorldListTrashCan 清理摘要超时")


def parse_removed_count(cleanup_line: str) -> int:
    """从清理摘要读取实际移除实体数。"""
    match = re.search(r"entitiesRemoved=(\d+)", cleanup_line)
    if match is None:
        raise RuntimeError("清理摘要缺少 entitiesRemoved: " + cleanup_line)
    return int(match.group(1))


def copy_evidence(server_dir: Path, evidence_dir: Path) -> None:
    """复制服务端 latest.log 和最终配置到证据目录。"""
    latest = server_dir / "logs" / "latest.log"
    if latest.is_file():
        shutil.copy2(latest, evidence_dir / "logs" / "latest.log")
    cleanup = server_dir / "plugins" / "WorldListTrashCan" / "cleanup.yml"
    if cleanup.is_file():
        shutil.copy2(cleanup, evidence_dir / "config" / "cleanup-final.yml")


def run_acceptance(run_root: Path, evidence_dir: Path, port: int, rcon_port: int) -> dict:
    """执行羊在 clear-animals false/true 下的真实清理对照。"""
    server_dir = prepare_server(run_root, port, rcon_port)
    stdout_path = evidence_dir / "logs" / "server-stdout.log"
    stderr_path = evidence_dir / "logs" / "server-stderr.log"
    command_path = evidence_dir / "logs" / "rcon-commands.log"
    stdout_path.parent.mkdir(parents=True, exist_ok=True)
    (evidence_dir / "config").mkdir(parents=True, exist_ok=True)
    entries = []
    result = {}
    with stdout_path.open("w", encoding="utf-8", errors="replace") as stdout, \
            stderr_path.open("w", encoding="utf-8", errors="replace") as stderr:
        process = subprocess.Popen(
            [str(JAVA21), "-Xms512M", "-Xmx1536M", "-jar", PAPER_JAR.name, "nogui"],
            cwd=ascii_repo_path(server_dir),
            stdin=subprocess.PIPE,
            stdout=stdout,
            stderr=stderr,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        try:
            legacy.wait_for_rcon(rcon_port)
            plugins = run_command(rcon_port, "plugins", entries)
            platform = run_command(rcon_port, "wtc platform", entries)
            cleanup_file = server_dir / "plugins" / "WorldListTrashCan" / "cleanup.yml"
            if not cleanup_file.is_file():
                raise RuntimeError("插件启动后未生成 cleanup.yml")
            shutil.copy2(cleanup_file, evidence_dir / "config" / "cleanup-default.yml")

            disabled_text = set_clear_animals(cleanup_file, False)
            (evidence_dir / "config" / "cleanup-clear-animals-false.yml").write_text(
                disabled_text, encoding="utf-8")
            run_command(rcon_port, "wtc reload", entries)
            run_command(rcon_port, "scoreboard objectives add ai_wtc_sheep dummy", entries)
            run_command(rcon_port, "minecraft:forceload add 0 0", entries)
            run_command(rcon_port, "minecraft:kill " + SHEEP_SELECTOR, entries)
            summon = (
                "minecraft:summon minecraft:sheep 0 100 0 "
                "{NoAI:1b,PersistenceRequired:1b,Invulnerable:1b,Tags:[\"AI_WTC_SHEEP\"]}"
            )
            run_command(rcon_port, summon, entries)
            before = probe_sheep(rcon_port, entries)

            latest_log = server_dir / "logs" / "latest.log"
            false_offset = len(latest_log.read_text(encoding="utf-8", errors="replace"))
            clear_false_response = run_command(rcon_port, "wtc clear true", entries)
            false_line = wait_cleanup_line(latest_log, false_offset)
            after_false = probe_sheep(rcon_port, entries)

            enabled_text = set_clear_animals(cleanup_file, True)
            (evidence_dir / "config" / "cleanup-clear-animals-true.yml").write_text(
                enabled_text, encoding="utf-8")
            run_command(rcon_port, "wtc reload", entries)
            true_offset = len(latest_log.read_text(encoding="utf-8", errors="replace"))
            clear_true_response = run_command(rcon_port, "wtc clear true", entries)
            true_line = wait_cleanup_line(latest_log, true_offset)
            after_true = probe_sheep(rcon_port, entries)
            stats = run_command(rcon_port, "wtc stats", entries)
            run_command(rcon_port, "minecraft:forceload remove 0 0", entries)

            removed_true = parse_removed_count(true_line)
            checks = {
                "pluginsLoaded": "WorldListTrashCan" in plugins,
                "paperUniversalPlatform": "paper-1.16-1.20" in platform and "universal" in platform,
                "entityCleanupEnabled": "  enabled: true" in enabled_text,
                "beforeSheepAlive": before == 1,
                "falseKeepsSheep": after_false == 1,
                "trueRemovesSheep": after_true == 0,
                "trueCleanupRemovedEntity": removed_true >= 1,
            }
            failed = [name for name, passed in checks.items() if not passed]
            if failed:
                raise AssertionError("Paper 1.21.4 羊清理断言失败: " + ", ".join(failed))
            result = {
                "passed": True,
                "checks": checks,
                "counts": {"before": before, "afterFalse": after_false, "afterTrue": after_true},
                "clearFalseResponse": clear_false_response,
                "clearTrueResponse": clear_true_response,
                "falseCleanupLine": false_line,
                "trueCleanupLine": true_line,
                "trueEntitiesRemoved": removed_true,
                "stats": stats,
            }
            legacy.stop_server(process, rcon_port)
        finally:
            legacy.terminate_server(process)
            command_path.write_text("\n\n".join(entries) + "\n", encoding="utf-8")
    copy_evidence(server_dir, evidence_dir)
    return result


def write_readme(evidence_dir: Path, summary: dict) -> None:
    """写入面向人工复核的专项证据索引。"""
    counts = summary["result"]["counts"]
    lines = [
        "# Paper 1.21.4 羊清理专项",
        "",
        "- 被测产物: `WorldListTrashCan-universal.jar`",
        "- SHA-256: `" + summary["jarSha256"] + "`",
        "- 服务端: `Paper 1.21.4 build 232`",
        "- 验收方式: 同一只无自定义名 Sheep，依次执行 `clear-animals: false` 和 `true` 的正式 `/wtc clear true` 对照。",
        "- 存活计数: 生成后 `" + str(counts["before"]) + "`，false 清理后 `"
        + str(counts["afterFalse"]) + "`，true 清理后 `" + str(counts["afterTrue"]) + "`。",
        "- 结论: `PASS`，`clear-animals: true` 会清理羊；false 时同一只羊保留。",
        "",
        "证据文件：",
        "",
        "- `logs/rcon-commands.log`：完整命令、reload、clear 和 scoreboard 响应。",
        "- `logs/latest.log`：插件启动与两轮 `[Cleanup]` 摘要。",
        "- `logs/server-stdout.log`、`logs/server-stderr.log`：完整服务端输出。",
        "- `config/cleanup-default.yml`、`cleanup-clear-animals-false.yml`、`cleanup-clear-animals-true.yml`：三阶段配置。",
        "- `summary.json`：机器可读断言和实体计数。",
    ]
    (evidence_dir / "README.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    """执行 Paper 1.21.4 clear-animals 羊清理专项。"""
    ensure_inputs()
    map_repository()
    try:
        stamp = time.strftime("%Y%m%d-%H%M%S")
        run_root = BUILD_ROOT / stamp
        evidence_dir = EVIDENCE_ROOT / ("sheep-clear-animals-paper1214-" + stamp)
        run_root.mkdir(parents=True, exist_ok=True)
        evidence_dir.mkdir(parents=True, exist_ok=True)
        result = run_acceptance(
            run_root, evidence_dir, legacy.find_free_port(), legacy.find_free_port())
        summary = {
            "timestamp": stamp,
            "allPassed": result["passed"],
            "paperJar": str(PAPER_JAR),
            "jar": str(UNIVERSAL_JAR),
            "jarSha256": sha256_file(UNIVERSAL_JAR),
            "result": result,
            "evidenceDir": str(evidence_dir),
        }
        write_json(evidence_dir / "summary.json", summary)
        write_readme(evidence_dir, summary)
        log("Paper 1.21.4 羊清理专项 PASS: " + str(evidence_dir))
    finally:
        unmap_repository()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
