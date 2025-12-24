#!/usr/bin/env python3
"""
Minimal adb-driven UI flow runner for the RocketPlan app.

Features:
- Starts the app, taps elements by text/content-desc/resource-id (via uiautomator dump).
- Types text into focused fields.
- Can tap by an index from a previously generated clickables summary
  (e.g., ui_dumps/001_project_detail_clickables.txt).

Flow format: JSON file with a list of steps. Example in scripts/flows/login_and_open_project.json
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Tuple


def run(cmd: List[str], *, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, check=check)


def ensure_device(adb: str) -> None:
    try:
        result = run([adb, "get-state"])
    except (FileNotFoundError, subprocess.CalledProcessError) as exc:
        sys.exit(f"[error] adb not available or no device: {exc}")
    if "device" not in result.stdout:
        sys.exit(f"[error] no device/emulator detected (got: {result.stdout.strip()})")


def dump_ui(adb: str) -> ET.Element:
    run([adb, "shell", "uiautomator", "dump", "--compressed", "/sdcard/window_dump.xml"])
    result = run([adb, "shell", "cat", "/sdcard/window_dump.xml"])
    try:
        return ET.fromstring(result.stdout)
    except ET.ParseError as exc:
        sys.exit(f"[error] Failed to parse UI XML: {exc}")


def parse_bounds(bounds: str) -> Tuple[int, int, int, int]:
    # Format: "[l,t][r,b]"
    try:
        parts = bounds.replace("[", "").replace("]", ",").split(",")
        left, top, right, bottom = [int(p) for p in parts if p]
        return left, top, right, bottom
    except Exception:
        return 0, 0, 0, 0


def center_of(bounds: str) -> Tuple[int, int]:
    left, top, right, bottom = parse_bounds(bounds)
    return (left + right) // 2, (top + bottom) // 2


def find_node(root: ET.Element, text: Optional[str], desc: Optional[str], res_id_substr: Optional[str]) -> Optional[ET.Element]:
    for node in root.iter():
        res_id = node.attrib.get("resource-id", "")
        node_text = node.attrib.get("text", "")
        node_desc = node.attrib.get("content-desc", "")
        if res_id_substr and res_id_substr not in res_id:
            continue
        if text is not None and text != node_text:
            continue
        if desc is not None and desc != node_desc:
            continue
        return node
    return None


def escape_input_text(text: str) -> str:
    # adb input text treats space as separator; use %s.
    return text.replace(" ", "%s")


def tap(adb: str, x: int, y: int) -> None:
    run([adb, "shell", "input", "tap", str(x), str(y)])


def input_text(adb: str, text: str) -> None:
    run([adb, "shell", "input", "text", escape_input_text(text)])


def start_app(adb: str, package: str, activity: str) -> None:
    component = f"{package}/{activity}"
    run([adb, "shell", "am", "start", "-n", component])


def resolve_env_text(raw: str) -> str:
    if raw.startswith("${") and raw.endswith("}"):
        inner = raw[2:-1]
        if ":-" in inner:
            var, default = inner.split(":-", 1)
        else:
            var, default = inner, ""
        return os.environ.get(var, default)
    return raw


def load_clickable_summary(path: Path) -> Dict[int, str]:
    mapping: Dict[int, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if "|" not in line:
            continue
        parts = line.split("|")
        try:
            idx = int(parts[0].strip())
        except ValueError:
            continue
        bounds_part = [p for p in parts if "bounds=" in p]
        if not bounds_part:
            continue
        bounds_str = bounds_part[0].split("bounds=")[-1].strip()
        mapping[idx] = bounds_str
    return mapping


@dataclass
class Selector:
    text: Optional[str] = None
    desc: Optional[str] = None
    res_id_substr: Optional[str] = None


def tap_by_selector(adb: str, selector: Selector) -> bool:
    root = dump_ui(adb)
    node = find_node(root, selector.text, selector.desc, selector.res_id_substr)
    if node is None:
        print(f"[warn] selector not found: {selector}")
        return False
    x, y = center_of(node.attrib.get("bounds", ""))
    tap(adb, x, y)
    return True


def handle_step(adb: str, step: Dict[str, object]) -> None:
    action = step.get("action")
    if action == "start_app":
        start_app(adb, step["package"], step["activity"])
    elif action == "wait":
        time.sleep(step.get("seconds", 1))
    elif action == "tap":
        selector = Selector(
            text=step.get("text"),
            desc=step.get("desc"),
            res_id_substr=step.get("id_contains"),
        )
        tap_by_selector(adb, selector)
    elif action == "input":
        selector = Selector(
            text=step.get("text_selector"),
            desc=step.get("desc_selector"),
            res_id_substr=step.get("id_contains"),
        )
        text_value = resolve_env_text(step.get("value", ""))
        if tap_by_selector(adb, selector):
            input_text(adb, text_value)
    elif action == "tap_from_summary":
        summary = Path(step["summary"])
        mapping = load_clickable_summary(summary)
        idx = int(step["index"])
        bounds = mapping.get(idx)
        if not bounds:
            print(f"[warn] index {idx} not found in {summary}")
            return
        x, y = center_of(bounds)
        tap(adb, x, y)
    elif action == "back":
        run([adb, "shell", "input", "keyevent", "4"])
    else:
        print(f"[warn] unknown action: {action}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Run simple adb UI flows defined in JSON.")
    parser.add_argument("--adb", default="adb", help="Path to adb binary (default: adb)")
    parser.add_argument("--flow", required=True, help="Path to flow JSON file")
    args = parser.parse_args()

    ensure_device(args.adb)
    flow_path = Path(args.flow)
    steps = json.loads(flow_path.read_text(encoding="utf-8"))
    if not isinstance(steps, list):
        sys.exit("[error] Flow file must be a list of step objects.")

    print(f"[info] Running flow from {flow_path} with {len(steps)} steps.")
    for i, step in enumerate(steps, start=1):
        print(f"[step {i}] {step.get('action')}")
        handle_step(args.adb, step)
    print("[done] Flow complete.")


if __name__ == "__main__":
    main()
