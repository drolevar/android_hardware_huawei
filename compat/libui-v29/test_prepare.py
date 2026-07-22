#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run with WORKSPACE_ROOT; tests public artifacts and fail-closed guards only."""

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import prepare


WORKSPACE = Path(sys.argv[1]).resolve() if len(sys.argv) == 2 else None


class PreparationGuards(unittest.TestCase):
    def test_current_artifacts_reproduce_both_abis(self):
        self.assertIsNotNone(WORKSPACE, "Pass WORKSPACE_ROOT")
        result = subprocess.run([sys.executable, "-B", str(prepare.ROOT / "prepare.py"),
                                 str(WORKSPACE)], text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout),
                         json.loads((prepare.ROOT / "TRANSFORM.json").read_text()))

    def test_changed_carrier_source_is_rejected(self):
        with tempfile.TemporaryDirectory(dir=prepare.ROOT) as directory:
            top = Path(directory)
            path = top / "system/libhidl/Android.bp"
            path.parent.mkdir(parents=True)
            path.write_text('cc_library { name: "libhidltransport", srcs: ["new.cpp"], }')
            with self.assertRaisesRegex(RuntimeError, "Source/tool contract changed"):
                prepare.verify_sources(top)

    def test_unrecognized_public_input_is_rejected_before_transform(self):
        with tempfile.TemporaryDirectory(dir=prepare.ROOT) as directory:
            source = Path(directory) / "changed.so"
            source.write_bytes(b"not the pinned public snapshot")
            destination = Path(directory) / "libui-v29.so"
            with self.assertRaisesRegex(RuntimeError, "Unexpected public VNDK29 input"):
                prepare.transform(source, destination, "0" * 64, "0" * 64, None, None, None)
            self.assertFalse(destination.exists())


if __name__ == "__main__":
    unittest.main(argv=[sys.argv[0]], verbosity=2)
