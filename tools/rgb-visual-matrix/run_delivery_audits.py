import argparse
import json
import subprocess
import sys
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
PYTHON_AUDITS = [
    ("dist-sync-dry-run", [sys.executable, "tools/rgb-visual-matrix/sync_dist_jars.py", "--dry-run"]),
    ("dist-package-integrity", [sys.executable, "tools/rgb-visual-matrix/check_dist_package_integrity.py"]),
    ("current-dist-hash-docs", [sys.executable, "tools/rgb-visual-matrix/check_current_dist_hash_docs.py"]),
    ("resource-yaml-comments", [sys.executable, "tools/rgb-visual-matrix/check_resource_yaml_comments.py"]),
    ("test-evidence-index", [sys.executable, "tools/rgb-visual-matrix/check_test_evidence_index.py"]),
    ("chunk-load-guards", [sys.executable, "tools/rgb-visual-matrix/check_chunk_load_guards.py"]),
]
GIT_AUDITS = [
    ("git-diff-check", ["git", "diff", "--check", "--", "."]),
    ("git-diff-cached-check", ["git", "diff", "--cached", "--check", "--", "."]),
]
MAVEN_TEST = (
    "maven-test",
    [str(REPO / "build" / "tools" / "apache-maven-3.9.9" / "bin" / "mvn.cmd"), "-q", "test"],
)


def run_command(name: str, command: list[str]) -> dict:
    """运行单个审计命令并返回摘要。"""
    result = subprocess.run(
        command,
        cwd=str(REPO),
        text=True,
        encoding="utf-8",
        errors="replace",
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    return {
        "name": name,
        "command": command,
        "returnCode": result.returncode,
        "stdout": result.stdout,
        "stderr": result.stderr,
    }


def run_audits(include_maven_test: bool) -> dict:
    """运行交付前后台审计。"""
    commands = list(PYTHON_AUDITS)
    commands.extend(GIT_AUDITS)
    if include_maven_test:
        commands.append(MAVEN_TEST)
    results = []
    for name, command in commands:
        results.append(run_command(name, command))
    failed = [item for item in results if item["returnCode"] != 0]
    return {
        "auditCount": len(results),
        "failedCount": len(failed),
        "results": results,
    }


def print_text_result(result: dict) -> None:
    """输出人类可读审计结果。"""
    print("audits:", result["auditCount"])
    print("failed:", result["failedCount"])
    for item in result["results"]:
        status = "OK" if item["returnCode"] == 0 else "FAIL"
        print(status + " " + item["name"])
        if item["returnCode"] != 0:
            if item["stdout"].strip():
                print(item["stdout"].rstrip())
            if item["stderr"].strip():
                print(item["stderr"].rstrip())


def main() -> int:
    """命令行入口。"""
    parser = argparse.ArgumentParser(description="运行 BLWorldTrashCan 交付前后台审计。")
    parser.add_argument("--with-maven-test", action="store_true", help="额外运行 Maven 单元测试。")
    parser.add_argument("--json", action="store_true", help="输出机器可读 JSON。")
    args = parser.parse_args()
    result = run_audits(args.with_maven_test)
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print_text_result(result)
    return 1 if result["failedCount"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
