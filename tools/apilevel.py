#!/usr/bin/env python3
"""Report the API level that introduced a class, method or field.

Reads the SDK's own api-versions.xml, so the answers are the same data lint would
use. Usage:

    ./apilevel.py android/hardware/camera2/CameraManager getConcurrentCameraIds
    ./apilevel.py android/media/MediaCodecInfo isHardwareAccelerated
    ./apilevel.py android/media/MediaFormat MIMETYPE_AUDIO_DRA
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

XML = Path("/root/android-sdk/platforms/android-34/data/api-versions.xml")


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    target = sys.argv[1].replace(".", "/")
    member = sys.argv[2] if len(sys.argv) > 2 else None

    root = ET.parse(XML).getroot()
    for cls in root.iter("class"):
        if cls.get("name") != target:
            continue
        print(f"class {target}: since {cls.get('since', '1')}")
        if member is None:
            return 0
        found = False
        for kind in ("method", "field"):
            for node in cls.findall(kind):
                name = node.get("name", "")
                bare = name.split("(")[0]
                if bare == member:
                    found = True
                    print(f"  {kind} {name}: since {node.get('since', cls.get('since', '1'))}"
                          + (f" deprecated {node.get('deprecated')}" if node.get("deprecated") else ""))
        if not found:
            print(f"  !! no member named {member} declared directly on {target}")
        return 0
    print(f"!! class not found: {target}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
