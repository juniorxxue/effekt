#!/usr/bin/env python3
"""Verify the pinned upstream input files, without running or changing coar.

Usage: python3 docs/atm/check_arm_manifest.py /path/to/coar
"""
import hashlib
import json
import sys
from pathlib import Path


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    manifest = json.loads(Path(__file__).with_name("arm-manifest.json").read_text())
    directory = Path(sys.argv[1]) / manifest["path"]
    expected = {entry["source"] for entry in manifest["benchmarks"]}
    actual = {path.name for path in directory.glob("*.ml")}
    errors = [f"File inventory differs: missing={sorted(expected - actual)}, extra={sorted(actual - expected)}"] if actual != expected else []
    for entry in manifest["benchmarks"]:
        path = directory / entry["source"]
        if path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
            errors.append(f"Source content differs: {entry['source']}")
    if errors:
        sys.exit("\n".join(errors))
    print(f"Verified all {len(expected)} ARM source files against the pinned manifest.")


if __name__ == "__main__":
    main()
