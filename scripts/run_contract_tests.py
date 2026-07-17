#!/usr/bin/env python3
from __future__ import annotations

import sys
import unittest


MODULES = ("test_compatibility_v1", "test_adapter_runtime")


def main() -> int:
    loader = unittest.defaultTestLoader
    suite = loader.loadTestsFromNames(MODULES)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    for test, traceback in [*result.failures, *result.errors]:
        message = " ".join(traceback.strip().splitlines()[-4:])
        message = message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::error file=scripts/run_contract_tests.py,title={test.id()}::{message}")
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
