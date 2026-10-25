#!/usr/bin/env python3
"""Check modular TV UI translations before Android compilation.

Only Android's resource resolver chooses the active locale. This script checks
both catalogues and their Kotlin references; it does not change runtime locale.
"""
from collections import Counter
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
JAVA = ROOT / "app/src/main/java"
SLOTS = re.compile(r"%\d+\$(?:[-+# 0,(]*\d*(?:\.\d+)?)[sdf]")
REFERENCE = re.compile(r"R\.string\.(i18n_[A-Za-z0-9_]+)")

def catalogue(folder: str) -> dict[str, str]:
    root = RES / folder
    files = sorted(root.glob("i18n_*.xml")) + [root / "strings.xml"]
    result: dict[str, str] = {}
    for file in files:
        assert file.is_file(), f"Missing resource: {file}"
        for value in ET.parse(file).getroot().findall("string"):
            key = value.attrib["name"]
            assert key not in result, f"Duplicate string: {key}"
            result[key] = "".join(value.itertext())
    return result

def main() -> None:
    english = catalogue("values")
    chinese = catalogue("values-zh")
    assert english.keys() == chinese.keys(), (
        f"Only English: {sorted(english.keys() - chinese.keys())}; "
        f"only Chinese: {sorted(chinese.keys() - english.keys())}"
    )
    for key in english:
        assert Counter(SLOTS.findall(english[key])) == Counter(SLOTS.findall(chinese[key])), (
            f"Format arguments differ between locales: {key}"
        )
    kotlin = "\n".join(p.read_text(encoding="utf-8") for p in JAVA.rglob("*.kt"))
    missing = set(REFERENCE.findall(kotlin)) - english.keys()
    assert not missing, f"Unresolved translation references: {sorted(missing)}"
    assert len(english) > 250, f"Unexpectedly incomplete UI catalogue: {len(english)} strings"
    print(f"i18n validated: {len(english)} English + {len(chinese)} Chinese resources.")

if __name__ == "__main__":
    main()
