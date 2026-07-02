import argparse
import hashlib
import json
import re
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
DIST = REPO / "dist"
ARTIFACTS = [
    "BLWorldTrashCan-legacy-1.12.jar",
    "BLWorldTrashCan-bukkit-1.13-1.15.jar",
    "BLWorldTrashCan-paper-1.16-1.20.jar",
    "BLWorldTrashCan-folia-1.20.jar",
    "BLWorldTrashCan-universal.jar",
]


def sha256(path: Path) -> str:
    """计算文件 SHA256。"""
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def current_hashes() -> dict[str, str]:
    """读取当前 dist 目录五个交付 jar 的 SHA256。"""
    hashes = {}
    for name in ARTIFACTS:
        path = DIST / name
        if not path.is_file():
            raise FileNotFoundError("缺少 dist 交付 jar: " + name)
        hashes[name] = sha256(path)
    return hashes


def read_text(path: Path) -> str:
    """按 UTF-8 读取文本文件。"""
    return path.read_text(encoding="utf-8", errors="replace")


def check_long_term_doc(hashes: dict[str, str], errors: list[str]) -> None:
    """检查长期硬化清单里的当前 universal SHA。"""
    path = REPO / "docs" / "长期硬化缺口清单.md"
    text = read_text(path)
    match = re.search(r"当前 universal jar SHA256：`([0-9a-f]{64})`", text)
    if not match:
        errors.append(path.relative_to(REPO).as_posix() + ": 缺少当前 universal jar SHA256 行")
        return
    actual = hashes["BLWorldTrashCan-universal.jar"]
    if match.group(1) != actual:
        errors.append(path.relative_to(REPO).as_posix() + ": 当前 universal SHA 不是 " + actual)


def check_readme(hashes: dict[str, str], errors: list[str]) -> None:
    """检查 README 是否写入当前 universal SHA。"""
    path = REPO / "README.md"
    text = read_text(path)
    actual = hashes["BLWorldTrashCan-universal.jar"]
    if actual not in text:
        errors.append(path.relative_to(REPO).as_posix() + ": 未包含当前 universal SHA " + actual)


def check_execution_log(hashes: dict[str, str], errors: list[str]) -> None:
    """检查执行记录是否写入当前五个 dist SHA。"""
    path = REPO / "docs" / "重构执行记录.md"
    text = read_text(path)
    for name, digest in hashes.items():
        if digest not in text:
            errors.append(path.relative_to(REPO).as_posix() + ": 未包含当前 " + name + " SHA " + digest)


def run_checks() -> dict:
    """执行当前 dist 哈希文档审计。"""
    hashes = current_hashes()
    errors = []
    check_long_term_doc(hashes, errors)
    check_readme(hashes, errors)
    check_execution_log(hashes, errors)
    return {
        "artifactCount": len(hashes),
        "errorCount": len(errors),
        "hashes": hashes,
        "errors": errors,
    }


def main() -> int:
    """命令行入口。"""
    parser = argparse.ArgumentParser(description="检查当前 dist SHA 是否写入交付文档。")
    parser.add_argument("--json", action="store_true", help="输出机器可读 JSON。")
    args = parser.parse_args()
    result = run_checks()
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print("artifacts:", result["artifactCount"])
        print("errors:", result["errorCount"])
        for name, digest in result["hashes"].items():
            print(name + " " + digest)
        for error in result["errors"]:
            print("- " + error)
    return 1 if result["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
