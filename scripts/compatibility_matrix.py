#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path

from compatibility_v1 import generate_matrix, load_v1_report, vendor_extension_report


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate v1 JDBC compatibility matrix reports.")
    parser.add_argument("reports", nargs="+", help="Path(s) to compatibility-report-v1.json")
    parser.add_argument("--matrix-out", default="compatibility-matrix.json")
    parser.add_argument("--vendor-out", default="vendor-extension-report.json")
    args = parser.parse_args()

    reports = [load_v1_report(Path(path)) for path in args.reports]
    matrix = generate_matrix(reports)
    vendor = vendor_extension_report(reports)

    Path(args.matrix_out).write_text(json.dumps(matrix, ensure_ascii=False, indent=2), encoding="utf-8")
    Path(args.vendor_out).write_text(json.dumps(vendor, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[生成] Matrix -> {args.matrix_out}")
    print(f"[生成] Vendor Extensions -> {args.vendor_out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
