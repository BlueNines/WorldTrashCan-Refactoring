import argparse
import json
import subprocess
import zipfile
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
DIST = REPO / "dist"
PLUGIN_NAME = "WorldListTrashCan"
DIST_ARTIFACTS = [
    "WorldListTrashCan-legacy-1.12.jar",
    "WorldListTrashCan-bukkit-1.13-1.15.jar",
    "WorldListTrashCan-paper-1.16-1.20.jar",
    "WorldListTrashCan-folia-1.20.jar",
    "WorldListTrashCan-universal.jar",
]
TEXT_SUFFIXES = {
    ".java", ".yml", ".yaml", ".xml", ".md", ".py", ".json",
    ".properties", ".txt", ".ps1", ".bat", ".cmd",
}


def forbidden_tokens() -> list[str]:
    """返回禁止重新出现在公开源码和交付物中的旧品牌大小写。"""
    return ["B" + "LWorldTrashCan", "B" + "LWtc", "B" + "LWTC"]


def tracked_files() -> list[Path]:
    """读取 Git 当前跟踪的文件列表。"""
    result = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=str(REPO),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.decode("utf-8", errors="replace"))
    paths = []
    for raw_path in result.stdout.split(b"\0"):
        if raw_path:
            paths.append(REPO / raw_path.decode("utf-8", errors="strict"))
    return paths


def check_tracked_sources(errors: list[str]) -> int:
    """检查公开源码、默认资源、工具和文档中的品牌大小写。"""
    checked = 0
    forbidden = forbidden_tokens()
    for path in tracked_files():
        relative = path.relative_to(REPO).as_posix()
        if relative.startswith("docs/test-evidence/"):
            continue
        if relative == "docs/重构执行记录.md":
            continue
        if any(token in relative for token in forbidden):
            errors.append(relative + ": 文件名仍包含旧品牌大小写")
        if path.suffix.lower() not in TEXT_SUFFIXES or not path.is_file():
            continue
        checked += 1
        text = path.read_text(encoding="utf-8", errors="replace")
        for token in forbidden:
            if token in text:
                errors.append(relative + ": 内容仍包含旧品牌大小写 " + token)
    return checked


def check_dist_artifacts(errors: list[str]) -> int:
    """检查 dist 文件名和 jar 内插件元数据。"""
    checked = 0
    forbidden = forbidden_tokens()
    for path in DIST.glob("*.jar"):
        if any(token in path.name for token in forbidden):
            errors.append("dist/" + path.name + ": 仍保留旧品牌大小写产物")
    for artifact in DIST_ARTIFACTS:
        path = DIST / artifact
        if not path.is_file():
            errors.append("dist/" + artifact + ": 缺少当前品牌大小写交付包")
            continue
        checked += 1
        with zipfile.ZipFile(path, "r") as archive:
            plugin_yml = archive.read("plugin.yml").decode("utf-8", errors="replace")
        if "name: " + PLUGIN_NAME not in plugin_yml:
            errors.append("dist/" + artifact + ": plugin.yml name 不是 " + PLUGIN_NAME)
        for token in forbidden:
            if token in plugin_yml:
                errors.append("dist/" + artifact + ": plugin.yml 仍包含旧品牌大小写 " + token)
    return checked


def run_checks() -> dict:
    """执行品牌大小写审计。"""
    errors = []
    source_count = check_tracked_sources(errors)
    artifact_count = check_dist_artifacts(errors)
    return {
        "pluginName": PLUGIN_NAME,
        "sourceFiles": source_count,
        "distArtifacts": artifact_count,
        "errorCount": len(errors),
        "errors": errors,
    }


def main() -> int:
    """命令行入口。"""
    parser = argparse.ArgumentParser(description="检查 WorldListTrashCan 公开品牌大小写。")
    parser.add_argument("--json", action="store_true", help="输出机器可读 JSON。")
    args = parser.parse_args()
    result = run_checks()
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print("plugin name:", result["pluginName"])
        print("source files:", result["sourceFiles"])
        print("dist artifacts:", result["distArtifacts"])
        print("errors:", result["errorCount"])
        for error in result["errors"]:
            print("- " + error)
    return 1 if result["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
