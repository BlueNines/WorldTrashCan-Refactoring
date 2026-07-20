import argparse
import json
import re
from collections import Counter
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
MATRIX_DOC = REPO / "docs" / "重构版完整功能与测试矩阵.md"
LONG_TERM_DOC = REPO / "docs" / "长期硬化缺口清单.md"
README_DOC = REPO / "README.md"
MIN_EXPECTED_FEATURE_ID = 91
UNRESOLVED_PREFIX = "当前仍未收敛的通用专项项："
SKIP_MARKER = "2026-06-08 当轮仍为 `SKIP` 的通用专项夹具项："
SETTLED_MARKER = "截至 2026-07-02，以下当轮 `SKIP` 项已由后续专项收敛为 PASS："
FEATURE_ID_PATTERN = re.compile(r"\bF-(\d{3})\b")


def read_text(path: Path) -> str:
    """按 UTF-8 读取文本文件。"""
    return path.read_text(encoding="utf-8", errors="replace")


def extract_section(text: str, heading: str) -> str:
    """截取指定二级标题下的正文。"""
    start = text.find(heading)
    if start < 0:
        return ""
    next_heading = text.find("\n## ", start + len(heading))
    if next_heading < 0:
        return text[start:]
    return text[start:next_heading]


def parse_feature_rows(text: str) -> list[list[str]]:
    """解析功能清单表格里的功能行。"""
    section = extract_section(text, "## 功能清单")
    rows = []
    for line in section.splitlines():
        stripped = line.strip()
        if not stripped.startswith("| F-"):
            continue
        rows.append([cell.strip() for cell in stripped.strip("|").split("|")])
    return rows


def feature_id_value(feature_id: str) -> int:
    """把 F-001 这类功能 ID 转成数字。"""
    match = re.fullmatch(r"F-(\d{3})", feature_id)
    if not match:
        return -1
    return int(match.group(1))


def check_feature_rows(rows: list[list[str]]) -> tuple[list[str], int]:
    """检查功能 ID 连续性和表格基本字段。"""
    errors = []
    feature_ids = [row[0] for row in rows if row]
    counts = Counter(feature_ids)
    for feature_id, count in sorted(counts.items()):
        if count > 1:
            errors.append("功能 ID 重复: " + feature_id)

    values = [feature_id_value(feature_id) for feature_id in feature_ids]
    invalid_ids = [feature_id for feature_id, value in zip(feature_ids, values) if value < 0]
    for feature_id in invalid_ids:
        errors.append("功能 ID 格式错误: " + feature_id)

    valid_values = sorted(value for value in values if value > 0)
    highest = valid_values[-1] if valid_values else 0
    if highest < MIN_EXPECTED_FEATURE_ID:
        errors.append("功能清单最高 ID 小于 F-%03d: F-%03d" % (MIN_EXPECTED_FEATURE_ID, highest))

    expected = set(range(1, highest + 1))
    actual = set(valid_values)
    missing = sorted(expected - actual)
    if missing:
        errors.append("功能 ID 不连续，缺少: " + ", ".join("F-%03d" % value for value in missing))

    for row in rows:
        if len(row) < 5:
            errors.append("功能行列数不足: " + " | ".join(row))
            continue
        feature_id = row[0]
        for index, field_name in ((1, "功能域"), (2, "功能"), (3, "默认状态"), (4, "验收方式")):
            if not row[index]:
                errors.append(feature_id + ": " + field_name + " 为空")
    return errors, highest


def extract_code_block_after_marker(text: str, marker: str) -> str:
    """提取指定标记后紧跟的 Markdown 代码块。"""
    marker_index = text.find(marker)
    if marker_index < 0:
        return ""
    block_start = text.find("```", marker_index)
    if block_start < 0:
        return ""
    content_start = text.find("\n", block_start)
    if content_start < 0:
        return ""
    block_end = text.find("```", content_start + 1)
    if block_end < 0:
        return ""
    return text[content_start + 1:block_end]


def feature_ids_in_text(text: str) -> set[str]:
    """提取文本中的功能 ID 集合。"""
    return {"F-" + match.group(1) for match in FEATURE_ID_PATTERN.finditer(text)}


def check_skip_settlement(text: str) -> tuple[list[str], int, int]:
    """检查历史 SKIP 是否全部写明后续收敛。"""
    errors = []
    skip_block = extract_code_block_after_marker(text, SKIP_MARKER)
    if not skip_block:
        errors.append("缺少 2026-06-08 历史 SKIP 代码块")
        return errors, 0, 0

    skipped = feature_ids_in_text(skip_block)
    settled_start = text.find(SETTLED_MARKER)
    if settled_start < 0:
        errors.append("缺少历史 SKIP 后续收敛说明")
        return errors, len(skipped), 0

    settled_end = text.find("\n\n", settled_start)
    if settled_end < 0:
        settled_end = len(text)
    settled_paragraph = text[settled_start:settled_end]
    settled = feature_ids_in_text(settled_paragraph)
    missing = sorted(skipped - settled)
    if missing:
        errors.append("历史 SKIP 未写明已收敛: " + ", ".join(missing))
    return errors, len(skipped), len(settled)


def check_unresolved_marker(text: str) -> list[str]:
    """检查未收敛通用专项声明没有回退。"""
    errors = []
    lines = [line.strip() for line in text.splitlines() if line.strip().startswith(UNRESOLVED_PREFIX)]
    if not lines:
        return ["缺少当前未收敛通用专项声明"]
    for line in lines:
        if line != UNRESOLVED_PREFIX + "无。":
            errors.append("当前未收敛通用专项声明不是无: " + line)
    return errors


def check_upper_docs(highest: int) -> list[str]:
    """检查上层文档没有把完整矩阵口径写回旧状态。"""
    errors = []
    long_term = read_text(LONG_TERM_DOC)
    readme = read_text(README_DOC)
    current_highest = "F-%03d" % highest

    if "当前无未收敛通用专项项" not in long_term:
        errors.append("长期硬化清单缺少当前无未收敛通用专项项说明")
    if "check_function_matrix_doc.py" not in long_term:
        errors.append("长期硬化清单缺少功能矩阵文档审计脚本说明")
    if current_highest not in readme:
        errors.append("README 未写明当前最高功能 ID " + current_highest)
    if "check_function_matrix_doc.py" not in readme:
        errors.append("README 缺少功能矩阵文档审计脚本说明")
    return errors


def run_checks() -> dict:
    """执行完整功能矩阵文档审计。"""
    text = read_text(MATRIX_DOC)
    rows = parse_feature_rows(text)
    errors = []
    row_errors, highest = check_feature_rows(rows)
    errors.extend(row_errors)
    skip_errors, skipped_count, settled_count = check_skip_settlement(text)
    errors.extend(skip_errors)
    errors.extend(check_unresolved_marker(text))
    if highest:
        errors.extend(check_upper_docs(highest))
    return {
        "featureCount": len(rows),
        "highestFeatureId": "F-%03d" % highest if highest else "",
        "historicalSkipCount": skipped_count,
        "settledSkipReferenceCount": settled_count,
        "errorCount": len(errors),
        "errors": errors,
    }


def main() -> int:
    """命令行入口。"""
    parser = argparse.ArgumentParser(description="检查 WorldListTrashCan 完整功能矩阵文档。")
    parser.add_argument("--json", action="store_true", help="输出机器可读 JSON。")
    args = parser.parse_args()
    result = run_checks()
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print("features:", result["featureCount"])
        print("highest feature:", result["highestFeatureId"])
        print("historical skip:", result["historicalSkipCount"])
        print("settled skip refs:", result["settledSkipReferenceCount"])
        print("errors:", result["errorCount"])
        for error in result["errors"]:
            print("- " + error)
    return 1 if result["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
