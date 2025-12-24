#!/usr/bin/env python3
"""
Interactive helper to dump the current Android UI hierarchy and list clickable nodes.

How it works:
- Uses `adb shell uiautomator dump --compressed` to grab the view hierarchy.
- Saves the raw XML and a text summary of clickable nodes (text/content-desc/id/bounds).
- Prompts you between captures so you can manually navigate to the next screen.
"""

from __future__ import annotations

import argparse
import datetime as _dt
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Iterable, List, Tuple


def run(cmd: List[str], *, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, check=check)


def ensure_device(adb: str) -> None:
    try:
        result = run([adb, "get-state"])
    except (FileNotFoundError, subprocess.CalledProcessError) as exc:
        sys.exit(f"[error] adb not available or no device: {exc}")
    if "device" not in result.stdout:
        sys.exit(f"[error] no device/emulator detected (got: {result.stdout.strip()})")


def parse_bounds(bounds: str) -> Tuple[int, int, int, int]:
    # Bounds format: "[left,top][right,bottom]"
    try:
        parts = bounds.replace("[", "").replace("]", ",").split(",")
        left, top, right, bottom = [int(p) for p in parts if p]
        return left, top, right, bottom
    except Exception:
        return 0, 0, 0, 0


def collect_clickables(root: ET.Element) -> Iterable[Tuple[int, int, ET.Element]]:
    stack: List[ET.Element] = [root]
    while stack:
        node = stack.pop()
        if node.attrib.get("clickable") == "true":
            _, top, _, _ = parse_bounds(node.attrib.get("bounds", ""))
            yield top, len(stack), node
        stack.extend(list(node))


def describe_node(node: ET.Element, idx: int) -> str:
    text = node.attrib.get("text", "") or ""
    desc = node.attrib.get("content-desc", "") or ""
    res_id = node.attrib.get("resource-id", "") or ""
    cls = node.attrib.get("class", "") or ""
    bounds = node.attrib.get("bounds", "") or ""
    long_clickable = node.attrib.get("long-clickable") == "true"
    parts = [
        f"{idx:02d}",
        f"text='{text}'" if text else "text=<empty>",
        f"desc='{desc}'" if desc else "desc=<empty>",
        f"id={res_id or '<none>'}",
        f"class={cls or '<none>'}",
        f"bounds={bounds or '<unknown>'}",
    ]
    if long_clickable:
        parts.append("long-clickable")
    return " | ".join(parts)


def dump_ui(adb: str) -> str:
    run([adb, "shell", "uiautomator", "dump", "--compressed", "/sdcard/window_dump.xml"])
    result = run([adb, "shell", "cat", "/sdcard/window_dump.xml"])
    return result.stdout


def write_dump(output_dir: Path, label: str, index: int, xml_data: str, summaries: List[str]) -> None:
    xml_path = output_dir / f"{index:03d}_{label}.xml"
    summary_path = output_dir / f"{index:03d}_{label}_clickables.txt"
    xml_path.write_text(xml_data, encoding="utf-8")
    summary_path.write_text("\n".join(summaries), encoding="utf-8")
    print(f"[saved] {xml_path}")
    print(f"[saved] {summary_path}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Dump Android UI and list clickable nodes.")
    parser.add_argument("--adb", default="adb", help="Path to adb (default: adb on PATH)")
    parser.add_argument(
        "--output-dir",
        default="ui_dumps",
        help="Directory to store dumps and summaries (default: ui_dumps)",
    )
    parser.add_argument(
        "--label",
        help="Label prefix for files (default: timestamp-based)",
    )
    args = parser.parse_args()

    ensure_device(args.adb)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    label = args.label or _dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    print("[info] Connected device detected. Navigate to a screen and press Enter to capture; Ctrl+C to stop.")

    index = 1
    try:
        while True:
            input("Press Enter to capture this screen...")
            xml_data = dump_ui(args.adb)
            try:
                root = ET.fromstring(xml_data)
            except ET.ParseError as exc:
                print(f"[error] Failed to parse UI XML: {exc}")
                continue

            clickables = sorted(collect_clickables(root), key=lambda entry: entry[0])
            summaries = [
                f"# Clickable nodes for capture {index:03d} ({label})",
                "# Format: idx | text | desc | id | class | bounds",
            ]
            for idx, (_, _, node) in enumerate(clickables, start=1):
                summaries.append(describe_node(node, idx))

            write_dump(output_dir, label, index, xml_data, summaries)
            for line in summaries[:25]:
                print(line)
            if len(summaries) > 25:
                print(f"... ({len(summaries) - 25} more clickables in summary file)")
            index += 1
    except KeyboardInterrupt:
        print("\n[done] Stopped by user.")


if __name__ == "__main__":
    main()
