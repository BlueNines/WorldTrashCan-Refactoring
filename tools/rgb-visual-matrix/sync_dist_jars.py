import argparse
import hashlib
import json
import shutil
import xml.etree.ElementTree as ET
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
DIST = REPO / "dist"
ARTIFACTS = [
    (
        "legacy-1.12",
        "bl-world-trashcan-plugin-legacy-1_12",
        "bl-world-trashcan-plugin-legacy-1_12",
        DIST / "WorldListTrashCan-legacy-1.12.jar",
    ),
    (
        "bukkit-1.13-1.15",
        "bl-world-trashcan-plugin-bukkit-1_13_1_15",
        "bl-world-trashcan-plugin-bukkit-1_13_1_15",
        DIST / "WorldListTrashCan-bukkit-1.13-1.15.jar",
    ),
    (
        "paper-1.16-1.20",
        "bl-world-trashcan-plugin-paper-1_16_1_20",
        "bl-world-trashcan-plugin-paper-1_16_1_20",
        DIST / "WorldListTrashCan-paper-1.16-1.20.jar",
    ),
    (
        "folia-1.20",
        "bl-world-trashcan-plugin-folia-1_20",
        "bl-world-trashcan-plugin-folia-1_20",
        DIST / "WorldListTrashCan-folia-1.20.jar",
    ),
    (
        "universal",
        "bl-world-trashcan-plugin-universal",
        "bl-world-trashcan-plugin-universal",
        DIST / "WorldListTrashCan-universal.jar",
    ),
]


def project_version() -> str:
    """从根 pom.xml 读取当前项目版本。"""
    root = ET.parse(REPO / "pom.xml").getroot()
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    version = root.findtext("m:version", namespaces=namespace)
    if version:
        return version.strip()
    parent_version = root.findtext("m:parent/m:version", namespaces=namespace)
    if parent_version:
        return parent_version.strip()
    raise RuntimeError("无法从根 pom.xml 读取项目版本")


def target_jar(module_name: str, artifact_id: str, version: str) -> Path:
    """按 Maven 标准命名计算 target jar 路径。"""
    return REPO / module_name / "target" / (artifact_id + "-" + version + ".jar")


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
    version = project_version()
    for name, module_name, artifact_id, target in ARTIFACTS:
        source = target_jar(module_name, artifact_id, version)
        artifacts.append(sync_artifact(name, source, target, dry_run))
    return {
        "version": version,
        "artifactCount": len(artifacts),
        "changedCount": sum(1 for item in artifacts if item["changed"]),
        "artifacts": artifacts,
    }


def main() -> int:
    """命令行入口。"""
    parser = argparse.ArgumentParser(description="把 Maven 最新 target jar 同步到 WorldListTrashCan dist 目录。")
    parser.add_argument("--dry-run", action="store_true", help="只比较哈希，不复制文件。")
    parser.add_argument("--json", action="store_true", help="输出机器可读 JSON。")
    args = parser.parse_args()
    result = run_sync(args.dry_run)
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print("version:", result["version"])
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
