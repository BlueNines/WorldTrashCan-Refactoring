import argparse
import json
import re
import subprocess
import zipfile
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
RESOURCE_SEPARATOR = "/src/main/resources/"
CONFIG_RESOURCE_NAMES = {
    "config.yml",
    "cleanup.yml",
    "trash.yml",
    "entity-limits.yml",
    "protections.yml",
    "platform.yml",
    "data/worlds.yml",
}
KEY_PATTERN = re.compile(r"^(\s*)([A-Za-z0-9_.-]+):(?:\s|$)")
CHINESE_PATTERN = re.compile(r"[\u4e00-\u9fff]")


def git_tracked_files() -> list[str]:
    """返回仓库中已被 Git 跟踪的文件。"""
    result = subprocess.run(
        ["git", "ls-files"],
        cwd=str(REPO),
        text=True,
        encoding="utf-8",
        errors="replace",
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip())
    return [line for line in result.stdout.splitlines() if line.strip()]


def config_resource_files() -> list[Path]:
    """找出四个平台产物中的默认配置资源文件。"""
    paths = []
    for item in git_tracked_files():
        normalized = item.replace("\\", "/")
        if not normalized.startswith("bl-world-trashcan-plugin-"):
            continue
        if RESOURCE_SEPARATOR not in normalized:
            continue
        resource_name = normalized.split(RESOURCE_SEPARATOR, 1)[1]
        if resource_name in CONFIG_RESOURCE_NAMES:
            paths.append(REPO / item)
    return sorted(paths)


def dist_jar_files() -> list[Path]:
    """找出现有 dist 目录中的交付 jar。"""
    dist = REPO / "dist"
    if not dist.is_dir():
        return []
    return sorted(dist.glob("WorldListTrashCan-*.jar"))


def read_lines(path: Path) -> list[str]:
    """按 UTF-8 读取文件行。"""
    return path.read_text(encoding="utf-8", errors="replace").splitlines()


def is_yaml_key_line(line: str) -> tuple[bool, int]:
    """判断当前行是否是需要注释的 YAML 键。"""
    stripped = line.lstrip()
    if not stripped or stripped.startswith("#") or stripped.startswith("-"):
        return False, 0
    match = KEY_PATTERN.match(line)
    if not match:
        return False, 0
    return True, len(match.group(1))


def has_nearby_chinese_comment(lines: list[str], line_index: int, indent: int) -> bool:
    """检查键上方是否有同级或父级中文注释。"""
    index = line_index - 1
    blank_count = 0
    while index >= 0 and blank_count < 2:
        previous = lines[index]
        stripped = previous.strip()
        if not stripped:
            blank_count += 1
            index -= 1
            continue
        previous_indent = len(previous) - len(previous.lstrip(" "))
        if stripped.startswith("#"):
            if previous_indent <= indent and CHINESE_PATTERN.search(stripped):
                return True
            index -= 1
            continue
        if previous_indent < indent:
            return False
        return False
    return False


def check_lines(label: str, lines: list[str]) -> tuple[int, list[str]]:
    """检查一组 YAML 文本行的键注释。"""
    errors = []
    checked_keys = 0
    for index, line in enumerate(lines):
        is_key, indent = is_yaml_key_line(line)
        if not is_key:
            continue
        checked_keys += 1
        if not has_nearby_chinese_comment(lines, index, indent):
            errors.append(label + ":" + str(index + 1) + ": 配置项缺少上方中文注释: " + line.strip())
    return checked_keys, errors


def check_file(path: Path) -> tuple[int, list[str]]:
    """检查单个默认配置文件的键注释。"""
    return check_lines(path.relative_to(REPO).as_posix(), read_lines(path))


def check_dist_jar(path: Path) -> tuple[int, int, list[str]]:
    """检查单个 dist jar 内默认配置资源的键注释。"""
    errors = []
    checked_keys = 0
    checked_resources = 0
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        for resource_name in sorted(CONFIG_RESOURCE_NAMES):
            if resource_name not in names:
                errors.append(path.relative_to(REPO).as_posix() + ": 缺少默认配置资源: " + resource_name)
                continue
            checked_resources += 1
            data = archive.read(resource_name)
            lines = data.decode("utf-8", errors="replace").splitlines()
            label = path.relative_to(REPO).as_posix() + "!" + resource_name
            count, resource_errors = check_lines(label, lines)
            checked_keys += count
            errors.extend(resource_errors)
    return checked_resources, checked_keys, errors


def run_checks() -> dict:
    """执行默认配置中文注释审计。"""
    files = config_resource_files()
    jars = dist_jar_files()
    errors = []
    checked_keys = 0
    checked_dist_resources = 0
    for path in files:
        count, file_errors = check_file(path)
        checked_keys += count
        errors.extend(file_errors)
    for path in jars:
        resource_count, key_count, jar_errors = check_dist_jar(path)
        checked_dist_resources += resource_count
        checked_keys += key_count
        errors.extend(jar_errors)
    return {
        "fileCount": len(files),
        "distJarCount": len(jars),
        "distResourceCount": checked_dist_resources,
        "checkedKeyCount": checked_keys,
        "errorCount": len(errors),
        "errors": errors,
    }


def main() -> int:
    """命令行入口。"""
    parser = argparse.ArgumentParser(description="检查 WorldListTrashCan 默认配置资源的中文注释。")
    parser.add_argument("--json", action="store_true", help="输出机器可读 JSON。")
    args = parser.parse_args()
    result = run_checks()
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print("config files:", result["fileCount"])
        print("dist jars:", result["distJarCount"])
        print("dist resources:", result["distResourceCount"])
        print("checked keys:", result["checkedKeyCount"])
        print("errors:", result["errorCount"])
        for error in result["errors"]:
            print("- " + error)
    return 1 if result["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
