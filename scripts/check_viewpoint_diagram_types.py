#!/usr/bin/env python3
"""Guard the static viewpoint->diagram-type table in saf_tools.groovy.

The table is derived from _data/viewpoints.json, so the two can drift. This
fails when a viewpoint names a diagram but has no table entry, when a table
entry names a viewpoint that no longer exists, or when a value is not a string
com.nomagic.uml2.diagram.DiagramTypes actually defines.

Run from the repo root:  python3 scripts/check_viewpoint_diagram_types.py
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "saf_tools.groovy"
VIEWPOINTS = ROOT / "_data" / "viewpoints.json"

# Authoritative values from com.nomagic.uml2.diagram.DiagramTypes, read off the
# shipped jar. A value not in this set would be rejected by
# ModelElementsManager.createDiagram at runtime.
DIAGRAM_TYPES = {
    "Class Diagram", "Use Case Diagram", "Object Diagram", "Package Diagram",
    "Communication Diagram", "Sequence Diagram", "State Machine Diagram",
    "Protocol State Machine Diagram", "Activity Diagram", "Component Diagram",
    "Deployment Diagram", "Composite Structure Diagram",
    "Interaction Overview Diagram", "Profile Diagram", "Any Diagram",
    "Static Diagram", "Behavior Diagram", "Interaction Diagram",
    "Dependency Matrix", "Content Diagram", "Relation Map Diagram",
    "Generic Table", "Glossary Table", "Instance Table",
    "User Interface Modeling Diagram",
}

# First match wins, mirroring the derivation the table was built from.
KINDS = [
    (r"internal block diagram|\bIBD\b", "Composite Structure Diagram"),
    (r"block definition diagram|\bBDD\b", "Class Diagram"),
    (r"sequence diagram", "Sequence Diagram"),
    (r"activity diagram", "Activity Diagram"),
    (r"use case diagram", "Use Case Diagram"),
    (r"state machine diagram|statechart", "State Machine Diagram"),
    (r"package diagram", "Package Diagram"),
    (r"component diagram", "Component Diagram"),
    (r"deployment diagram", "Deployment Diagram"),
    (r"communication diagram", "Communication Diagram"),
    (r"object diagram", "Object Diagram"),
    (r"profile diagram", "Profile Diagram"),
    (r"content diagram", "Content Diagram"),
]


def read_table() -> dict[str, str]:
    text = SCRIPT.read_text(encoding="utf-8")
    m = re.search(
        r"VIEWPOINT_DIAGRAM_TYPE\s*=\s*\[(.*?)\n    \]", text, re.S)
    if not m:
        sys.exit("VIEWPOINT_DIAGRAM_TYPE not found in saf_tools.groovy")
    return dict(re.findall(r'"([A-Z0-9_]+)"\s*:\s*"([^"]+)"', m.group(1)))


def expected_table() -> dict[str, str]:
    data = json.loads(VIEWPOINTS.read_text(encoding="utf-8"))
    vs = data["Viewpoints"] if isinstance(data, dict) and "Viewpoints" in data else data
    out: dict[str, str] = {}
    for v in vs:
        text = " ".join(v.get("Presentation") or [])
        for pattern, value in KINDS:
            if re.search(pattern, text, re.I):
                out[v["VP_ID"]] = value
                break
    return out


def main() -> int:
    actual, expected = read_table(), expected_table()
    errors: list[str] = []

    for vp_id, value in sorted(actual.items()):
        if vp_id not in expected:
            errors.append(f"{vp_id}: in the table but its viewpoint names no diagram")
        elif expected[vp_id] != value:
            errors.append(
                f"{vp_id}: table says {value!r}, viewpoints.json implies {expected[vp_id]!r}")
        if value not in DIAGRAM_TYPES:
            errors.append(f"{vp_id}: {value!r} is not a DiagramTypes constant")

    for vp_id, value in sorted(expected.items()):
        if vp_id not in actual:
            errors.append(
                f"{vp_id}: viewpoints.json implies {value!r} but the table has no entry "
                f"(it will silently fall back)")

    for name, value in (
        ("DEFAULT_DIAGRAM_TYPE", "Content Diagram"),
        ("TABULAR_VIEWPOINT_FALLBACK", "Class Diagram"),
    ):
        m = re.search(rf'{name}\s*=\s*"([^"]+)"', SCRIPT.read_text(encoding="utf-8"))
        if not m:
            errors.append(f"{name} not found")
        elif m.group(1) != value:
            errors.append(f"{name} is {m.group(1)!r}, expected {value!r}")
        elif m.group(1) not in DIAGRAM_TYPES:
            errors.append(f"{name} is not a DiagramTypes constant")

    if errors:
        print(f"FAIL  {len(errors)} problem(s) in the viewpoint diagram-type table:")
        for e in errors:
            print("  -", e)
        return 1
    print(f"PASS  {len(actual)} viewpoint diagram types, all DiagramTypes constants")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
