#!/usr/bin/env python3
"""Batch-check the `since` API level of many members at once.

Reads pairs from stdin, one per line: `class/path member`. Prints the API level
the SDK's own api-versions.xml records, which is what the detectors' `minApi`
arguments must match if the app is to tell the truth about why a value is missing.
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

XML = Path("/root/android-sdk/platforms/android-34/data/api-versions.xml")


def main() -> int:
    root = ET.parse(XML).getroot()
    classes = {c.get("name"): c for c in root.iter("class")}

    for raw in sys.stdin:
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        cls_name = parts[0].replace(".", "/")
        member = parts[1] if len(parts) > 1 else None
        cls = classes.get(cls_name)
        if cls is None:
            print(f"{line}\t!! class not found")
            continue
        cls_since = cls.get("since", "1")
        if member is None:
            print(f"{line}\tclass since {cls_since}")
            continue
        hits = []
        for kind in ("method", "field"):
            for node in cls.findall(kind):
                if node.get("name", "").split("(")[0] == member:
                    hits.append((kind, node.get("name"), node.get("since", cls_since)))
        if not hits:
            print(f"{line}\t!! member missing (class since {cls_since})")
        else:
            levels = sorted({h[2] for h in hits})
            print(f"{line}\tsince {','.join(levels)} (class {cls_since})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
