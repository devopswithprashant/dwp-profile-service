#!/usr/bin/env python3

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


SOURCE_ROOT = Path("src/main/scala")


def source_file(repo_root: Path, filename: str) -> Path:
    normalized = filename.replace("\\", "/")
    marker = f"{SOURCE_ROOT.as_posix()}/"
    if marker in normalized:
        normalized = normalized.split(marker, 1)[1]
    elif normalized.startswith("/"):
        raise ValueError(f"Scoverage source is outside {SOURCE_ROOT}: {filename}")

    candidate = (repo_root / SOURCE_ROOT / normalized).resolve()
    source_root = (repo_root / SOURCE_ROOT).resolve()
    if not candidate.is_relative_to(source_root) or not candidate.is_file():
        raise ValueError(f"Could not map Scoverage source to a file under {SOURCE_ROOT}: {filename}")
    return candidate


def convert(report_path: Path, output_path: Path, repo_root: Path) -> tuple[int, int]:
    root = ET.parse(report_path).getroot()
    files: dict[str, dict[int, dict[str, int | bool]]] = {}

    for class_node in root.iter("class"):
        filename = class_node.get("filename")
        if not filename:
            raise ValueError("Scoverage class is missing its filename")

        source = source_file(repo_root, filename)
        relative_source = source.relative_to(repo_root.resolve()).as_posix()
        line_data = files.setdefault(relative_source, {})

        for statement in class_node.iter("statement"):
            if statement.get("ignored", "false").lower() == "true":
                continue

            try:
                line_number = int(statement.attrib["line"])
                hits = int(statement.attrib["invocation-count"])
            except (KeyError, ValueError) as error:
                raise ValueError(f"Invalid Scoverage statement in {filename}: {error}") from error

            line = line_data.setdefault(
                line_number,
                {"covered": False, "branches": 0, "covered_branches": 0},
            )
            line["covered"] = bool(line["covered"]) or hits > 0
            if statement.get("branch", "false").lower() == "true":
                line["branches"] = int(line["branches"]) + 1
                if hits > 0:
                    line["covered_branches"] = int(line["covered_branches"]) + 1

    if not files or not any(lines for lines in files.values()):
        raise ValueError("Scoverage report contains no source statements to import")

    coverage = ET.Element("coverage", {"version": "1"})
    total_lines = 0
    for filename, lines in sorted(files.items()):
        file_node = ET.SubElement(coverage, "file", {"path": filename})
        for line_number, data in sorted(lines.items()):
            attributes = {
                "lineNumber": str(line_number),
                "covered": str(bool(data["covered"])).lower(),
            }
            if data["branches"]:
                attributes["branchesToCover"] = str(data["branches"])
                attributes["coveredBranches"] = str(data["covered_branches"])
            ET.SubElement(file_node, "lineToCover", attributes)
            total_lines += 1

    output_path.parent.mkdir(parents=True, exist_ok=True)
    ET.indent(coverage)
    ET.ElementTree(coverage).write(output_path, encoding="utf-8", xml_declaration=True)
    return len(files), total_lines


def main() -> int:
    parser = argparse.ArgumentParser(description="Convert Scoverage XML to SonarQube generic coverage XML.")
    parser.add_argument("report", type=Path, help="Input Scoverage report")
    parser.add_argument("output", type=Path, help="Output SonarQube generic coverage report")
    arguments = parser.parse_args()

    try:
        file_count, line_count = convert(arguments.report, arguments.output, Path.cwd())
    except (OSError, ET.ParseError, ValueError) as error:
        print(f"Coverage conversion failed: {error}", file=sys.stderr)
        return 1

    print(f"Converted {line_count} executable lines across {file_count} source files for SonarQube.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
