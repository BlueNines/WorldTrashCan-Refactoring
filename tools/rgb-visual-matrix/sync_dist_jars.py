import argparse
import hashlib
import json
import shutil
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
DIST = REPO / "dist"
ARTIFACTS = [
    (
        "legacy-1.12",
        REPO / "bl-world-trashcan-plugin-legacy-1_12" / "target" / "bl-world-trashcan-plugin-legacy-1_12-7.0.0.jar",
        DIST / "BLWorldTrashCan-legacy-1.12.jar",
    ),
    (
        "bukkit-1.13-1.15",
        REPO / "bl-world-trashcan-plugin-bukkit-1_13_1_15" / "target" / "bl-world-trashcan-plugin-bukkit-1_13_1_15-7.0.0.jar",
        DIST / "BLWorldTrashCan-bukkit-1.13-1.15.jar",
    ),
    (
        "paper-1.16-1.20",
        REPO / "bl-world-trashcan-plugin-paper-1_16_1_20" / "target" / "bl-world-trashcan-plugin-paper-1_16_1_20-7.0.0.jar",
        DIST / "BLWorldTrashCan-paper-1.16-1.20.jar",
    ),
    (
        "folia-1.20",
        REPO / "bl-world-trashcan-plugin-folia-1_20" / "target" / "bl-world-trashcan-plugin-folia-1_20-7.0.0.jar",
        DIST / "BLWorldTrashCan-folia-1.20.jar",
    ),
    (
        "universal",
        REPO / "bl-world-trashcan-plugin-universal" / "target" / "bl-world-trashcan-plugin-universal-7.0.0.jar",
        DIST / "BLWorldTrashCan-universal.jar",
    ),
]


def sha256(path: Path) -> str:
    """计算文件 SHA256。"""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sync_artifact(name: str, source: Path, target: Path, dry_run: bool) -> dict:
    """同步单个 Maven target 产物到 dist。"""
    if not source.is_file():
        raise FileNotFoundError("缺少 Maven target 产物，请先运行 mvn clean package: " + str(source))
    target.parent.mkdir(parents=True, exist_ok=True)
    source_hash = sha256(source)
    before_hash = sha256(target) if target.is_file() else ""
    if not dry_run:
        shutil.copy2(source, target)
    after_hash = sha256(target) if target.is_file() else source_hash
    return {
        "name": name,
        "source": source.relative_to(REPO).as_posix(),
        "target": target.relative_to(REPO).as_posix(),
        "sourceSha256": source_hash,
        "beforeSha256": before_hash,
        "afterSha256": after_hash,
        "changed": before_hash != after_hash,
        "dryRun": dry_run,
    }


def run_sync(dry_run: bool) -> dict:
    """同步全部交付 jar。"""
    artifacts = []
    for name, source, target in ARTIFACTS:
        artifacts.append(sync_artifact(name, source, target, dry_run))
    return {
        "artifactCount": len(artifacts),
        "changedCount": sum(1 for item in artifacts if item["changed"]),
        "artifacts": artifacts,
    }


def main() -> int:
    """命令行入口。"""
    parser = argparse.ArgumentParser(description="把 Maven 最新 target jar 同步到 BLWorldTrashCan dist 目录。")
    parser.add_argument("--dry-run", action="store_true", help="只比较哈希，不复制文件。")
    parser.add_argument("--json", action="store_true", help="输出机器可读 JSON。")
    args = parser.parse_args()
    result = run_sync(args.dry_run)
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print("artifacts:", result["artifactCount"])
        print("changed:", result["changedCount"])
        for item in result["artifacts"]:
            marker = "CHANGED" if item["changed"] else "OK"
            if item["dryRun"] and item["changed"]:
                marker = "WOULD_CHANGE"
            print(marker + " " + item["target"] + " " + item["afterSha256"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
