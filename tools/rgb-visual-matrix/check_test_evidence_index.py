import argparse
import json
import re
import subprocess
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
EVIDENCE_ROOT = REPO / "docs" / "test-evidence"
INDEX_FILE = EVIDENCE_ROOT / "README.md"
GITIGNORE_FILE = REPO / ".gitignore"
UPPER_DOCS = [
    REPO / "README.md",
    REPO / "docs" / "重构版新增功能说明.md",
    REPO / "docs" / "重构版完整功能与测试矩阵.md",
    REPO / "docs" / "长期硬化缺口清单.md",
    REPO / "docs" / "重构执行记录.md",
]
FINAL_HEADING = "## 当前最终证据"
FAILURE_HEADING = "## 当前失败对照"
FAILURE_MARKERS = ("FAIL", "失败", "不作为最终", "暴露", "误判", "未稳定", "遮挡")
EVIDENCE_REFERENCE_PATTERN = re.compile(r"docs[\\/]+test-evidence[\\/]+([^`)\]\s\\/]+)")


def read_text(path: Path) -> str:
    """按 UTF-8 读取文本文件。"""
    return path.read_text(encoding="utf-8", errors="replace")


def docs_are_local_only() -> bool:
    """判断当前插件是否明确把 docs 目录设置为本地资料。"""
    if not GITIGNORE_FILE.is_file():
        return False
    lines = read_text(GITIGNORE_FILE).splitlines()
    return any(line.strip() in ("docs/", "/docs/") for line in lines)


def local_files(path: Path) -> list[Path]:
    """返回证据目录内的本地文件。"""
    return [item for item in path.rglob("*") if item.is_file()]


def markdown_section(text: str, heading: str) -> str:
    """截取指定二级标题下的正文。"""
    start = text.find(heading)
    if start < 0:
        return ""
    next_heading = text.find("\n## ", start + len(heading))
    if next_heading < 0:
        return text[start:]
    return text[start:next_heading]


def parse_markdown_table(section: str) -> list[list[str]]:
    """解析简单 Markdown 表格行。"""
    rows = []
    for line in section.splitlines():
        line = line.strip()
        if not line.startswith("|") or not line.endswith("|"):
            continue
        cells = [cell.strip() for cell in line.strip("|").split("|")]
        if not cells or all(set(cell) <= {"-", " "} for cell in cells):
            continue
        if cells[0] in ("功能范围", "优先级"):
            continue
        rows.append(cells)
    return rows


def extract_backtick_dir(cell: str) -> str:
    """从表格单元格中提取反引号包裹的证据目录。"""
    match = re.search(r"`([^`]+/?)`", cell)
    if not match:
        return ""
    value = match.group(1).replace("\\", "/")
    return value.rstrip("/")


def git_tracked_files(path: Path) -> list[str]:
    """返回指定路径下已被 Git 跟踪的文件。"""
    relative = path.relative_to(REPO).as_posix()
    result = subprocess.run(
        ["git", "ls-files", "--", relative],
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


def all_git_tracked_evidence_files() -> list[str]:
    """返回测试证据目录下所有已被 Git 跟踪的文件。"""
    result = subprocess.run(
        ["git", "ls-files", "--", EVIDENCE_ROOT.relative_to(REPO).as_posix()],
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


def load_json(path: Path) -> dict:
    """读取 JSON 文件，失败时返回错误结构。"""
    try:
        return json.loads(path.read_text(encoding="utf-8", errors="replace"))
    except Exception as exc:
        return {"__json_error__": str(exc)}


def has_machine_summary(path: Path) -> bool:
    """判断证据目录是否包含机器可读摘要。"""
    if (path / "summary.json").exists():
        return True
    if any(path.glob("*-summary.json")):
        return True
    return any(path.rglob("result.json"))


def summary_declares_failure(path: Path) -> str:
    """读取根 summary，判断是否明确声明失败。"""
    summary_path = path / "summary.json"
    if not summary_path.exists():
        return ""
    summary = load_json(summary_path)
    if summary.get("__json_error__"):
        return "summary.json 无法解析: " + summary["__json_error__"]
    if summary.get("allPassed") is False:
        return "summary.json allPassed=false"
    if str(summary.get("status", "")).upper() == "FAIL":
        return "summary.json status=FAIL"
    return ""


def upper_doc_references(evidence_dir: str) -> list[str]:
    """查找最终证据是否被上层文档引用。"""
    hits = []
    needle = evidence_dir.replace("\\", "/").rstrip("/")
    for path in UPPER_DOCS:
        if path.exists() and needle in read_text(path).replace("\\", "/"):
            hits.append(path.relative_to(REPO).as_posix())
    return hits


def doc_evidence_references() -> dict[str, set[str]]:
    """提取上层文档中显式写出的测试证据目录引用。"""
    references = {}
    for path in UPPER_DOCS:
        if not path.exists():
            continue
        text = read_text(path)
        names = {
            match.group(1).replace("\\", "/").rstrip("/")
            for match in EVIDENCE_REFERENCE_PATTERN.finditer(text)
            if is_evidence_reference_name(match.group(1))
        }
        if names:
            references[path.relative_to(REPO).as_posix()] = names
    return references


def is_evidence_reference_name(name: str) -> bool:
    """判断匹配到的路径片段是否是真实证据目录名。"""
    lowered = name.lower()
    if lowered == "readme.md":
        return False
    if "<" in name or ">" in name:
        return False
    return True


def final_evidence_checks(name: str, evidence_dir: str, description: str) -> list[str]:
    """检查最终 PASS 证据目录的最低要求。"""
    errors = []
    path = EVIDENCE_ROOT / evidence_dir
    local_only = docs_are_local_only()
    if not path.is_dir():
        return [name + ": 最终证据目录不存在: " + evidence_dir]
    tracked = git_tracked_files(path)
    files = local_files(path)
    if not tracked and not local_only:
        errors.append(name + ": 最终证据目录没有 Git 跟踪内容: " + evidence_dir)
    if local_only and not files:
        errors.append(name + ": 本地最终证据目录为空: " + evidence_dir)
    if any(item.lower().endswith(".jar") for item in tracked):
        errors.append(name + ": 最终证据目录包含已跟踪 jar: " + evidence_dir)
    if not (path / "README.md").exists():
        errors.append(name + ": 最终证据缺少 README.md: " + evidence_dir)
    if not has_machine_summary(path):
        errors.append(name + ": 最终证据缺少 summary.json 或 result.json: " + evidence_dir)
    failure = summary_declares_failure(path)
    if failure:
        errors.append(name + ": 最终证据摘要声明失败: " + failure)
    readme = read_text(path / "README.md") if (path / "README.md").exists() else ""
    if "结论: FAIL" in readme or "结论: `FAIL`" in readme:
        errors.append(name + ": 最终证据 README 声明 FAIL: " + evidence_dir)
    if not upper_doc_references(evidence_dir):
        errors.append(name + ": 最终证据未被上层文档引用: " + evidence_dir)
    if not description:
        errors.append(name + ": 最终证据说明为空: " + evidence_dir)
    return errors


def failure_evidence_checks(name: str, evidence_dir: str, purpose: str) -> list[str]:
    """检查失败对照证据目录的最低要求。"""
    errors = []
    path = EVIDENCE_ROOT / evidence_dir
    local_only = docs_are_local_only()
    if not path.is_dir():
        return [name + ": 失败对照目录不存在: " + evidence_dir]
    tracked = git_tracked_files(path)
    if not tracked and not local_only:
        errors.append(name + ": 失败对照目录没有 Git 跟踪内容: " + evidence_dir)
    if local_only and not local_files(path):
        errors.append(name + ": 本地失败对照目录为空: " + evidence_dir)
    if any(item.lower().endswith(".jar") for item in tracked):
        errors.append(name + ": 失败对照目录包含已跟踪 jar: " + evidence_dir)
    if not (path / "README.md").exists():
        errors.append(name + ": 失败对照缺少 README.md: " + evidence_dir)
    readme = read_text(path / "README.md") if (path / "README.md").exists() else ""
    combined = purpose + "\n" + readme
    if not any(marker in combined for marker in FAILURE_MARKERS):
        errors.append(name + ": 失败对照未写明失败或不作为最终的原因: " + evidence_dir)
    return errors


def global_evidence_checks() -> list[str]:
    """检查全局证据目录中不应出现的 Git 跟踪内容。"""
    errors = []
    tracked = all_git_tracked_evidence_files()
    for item in tracked:
        if item.lower().endswith(".jar"):
            errors.append("测试证据目录包含已跟踪 jar: " + item)
    return errors


def doc_reference_checks() -> tuple[list[str], int]:
    """检查上层文档引用的证据目录是否真实可交付。"""
    errors = []
    references = doc_evidence_references()
    unique_refs = set()
    for doc, names in references.items():
        for name in sorted(names):
            unique_refs.add(name)
            path = EVIDENCE_ROOT / name
            if not path.is_dir():
                errors.append(doc + ": 引用的证据目录不存在: " + name)
                continue
            if not docs_are_local_only() and not git_tracked_files(path):
                errors.append(doc + ": 引用的证据目录没有 Git 跟踪内容，疑似本地缓存: " + name)
    return errors, len(unique_refs)


def parse_index() -> tuple[list[tuple[str, str, str]], list[tuple[str, str, str]]]:
    """解析证据索引中的最终证据和失败对照表。"""
    text = read_text(INDEX_FILE)
    final_rows = parse_markdown_table(markdown_section(text, FINAL_HEADING))
    failure_rows = parse_markdown_table(markdown_section(text, FAILURE_HEADING))
    finals = []
    failures = []
    for row in final_rows:
        if len(row) >= 3:
            finals.append((row[0], extract_backtick_dir(row[1]), row[2]))
    for row in failure_rows:
        if len(row) >= 3:
            failures.append((row[0], extract_backtick_dir(row[1]), row[2]))
    return finals, failures


def run_checks() -> dict:
    """执行证据索引一致性审计。"""
    finals, failures = parse_index()
    errors = []
    errors.extend(global_evidence_checks())
    final_dirs = {item[1] for item in finals if item[1]}
    failure_dirs = {item[1] for item in failures if item[1]}
    overlap = final_dirs & failure_dirs
    for evidence_dir in sorted(overlap):
        errors.append("同一证据目录不能同时是最终证据和失败对照: " + evidence_dir)
    for name, evidence_dir, description in finals:
        if not evidence_dir:
            errors.append(name + ": 最终证据表格缺少反引号目录")
            continue
        errors.extend(final_evidence_checks(name, evidence_dir, description))
    for name, evidence_dir, purpose in failures:
        if not evidence_dir:
            errors.append(name + ": 失败对照表格缺少反引号目录")
            continue
        errors.extend(failure_evidence_checks(name, evidence_dir, purpose))
    reference_errors, reference_count = doc_reference_checks()
    errors.extend(reference_errors)
    return {
        "finalCount": len(finals),
        "failureCount": len(failures),
        "docReferenceCount": reference_count,
        "errorCount": len(errors),
        "errors": errors,
    }


def main() -> int:
    """命令行入口。"""
    parser = argparse.ArgumentParser(description="检查 WorldListTrashCan 测试证据索引。")
    parser.add_argument("--json", action="store_true", help="输出机器可读 JSON。")
    args = parser.parse_args()
    result = run_checks()
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print("final evidence:", result["finalCount"])
        print("failure evidence:", result["failureCount"])
        print("doc references:", result["docReferenceCount"])
        print("errors:", result["errorCount"])
        for error in result["errors"]:
            print("- " + error)
    return 1 if result["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
