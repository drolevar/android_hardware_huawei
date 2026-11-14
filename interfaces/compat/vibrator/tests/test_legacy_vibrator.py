#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Integrity checks, plus optional real hidl-gen checks (see ../README.md)."""

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
HARDWARE = ROOT.parents[2]
PACKAGE = "android.hardware.vibrator@1.0"
HASHES = {
    "IVibrator": "06ea64cc3565777f3b259e400ffa7100d07f3827ad9357b0c5d3c651384e5553",
    "types": "0fecd34ae64f32eff6aa615fd662349242c0b8b6e303ef05a7cb5776c732f413",
}


class SourceIntegrity(unittest.TestCase):
    def test_unmodified_published_sources_and_hashes(self):
        expected = set()
        for name, digest in HASHES.items():
            with self.subTest(name=name):
                path = ROOT / "1.0" / (name + ".hal")
                self.assertTrue(path.is_file(), f"missing restored definition: {path}")
                source = path.read_bytes()
                self.assertEqual(hashlib.sha256(source).hexdigest(), digest)
                expected.add(f"{digest} {PACKAGE}::{name}")
        self.assertTrue((ROOT / "current.txt").is_file(), "missing frozen API hashes")
        entries = {
            line for line in (ROOT / "current.txt").read_text().splitlines()
            if line and not line.startswith("#")
        }
        self.assertEqual(entries, expected)


@unittest.skipUnless(os.environ.get("HIDL_GEN"), "set HIDL_GEN and ANDROID_BUILD_TOP")
class GeneratorCompatibility(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.compat = self.directory / "vibrator"
        shutil.copytree(ROOT / "1.0", self.compat / "1.0")
        shutil.copyfile(ROOT / "current.txt", self.compat / "current.txt")
        self.top = Path(os.environ["ANDROID_BUILD_TOP"]).resolve()
        self.roots = [
            f"-randroid.hardware.vibrator:{self.compat}",
            f"-randroid.hidl:{self.top / 'system/libhidl/transport'}",
            f"-rvendor.huawei:{HARDWARE / 'interfaces/huawei'}",
        ]

    def generate(self, language, package=PACKAGE, extra=(), defaults=False):
        output = self.directory / language
        output.mkdir(exist_ok=True)
        command = [os.environ["HIDL_GEN"]]
        if not defaults:
            command.append("-R")
        command += ["-p", str(self.top), "-L", language, "-o", str(output)]
        command += self.roots + list(extra) + [package]
        return subprocess.run(command, text=True, capture_output=True), output

    def assert_generated(self, language, package=PACKAGE, extra=()):
        result, output = self.generate(language, package, extra)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result, output

    def test_frozen_hashes(self):
        result, _ = self.assert_generated("hash", extra=("-F",))
        self.assertEqual(set(result.stdout.splitlines()), {
            f"{digest} {PACKAGE}::{name}" for name, digest in HASHES.items()
        })

    def test_cpp_java_and_export_constants(self):
        for language, filename in (
            ("c++-headers", "android/hardware/vibrator/1.0/IVibrator.h"),
            ("c++-sources", "android/hardware/vibrator/1.0/VibratorAll.cpp"),
            ("java", "android/hardware/vibrator/V1_0/IVibrator.java"),
            ("java-constants", "android/hardware/vibrator/V1_0/Constants.java"),
        ):
            with self.subTest(language=language):
                _, output = self.assert_generated(language, extra=("-F",))
                self.assertTrue((output / filename).is_file(), filename)

    def test_huawei_generation_and_inheritance(self):
        for version in ("1.0", "1.1"):
            package = f"vendor.huawei.hardware.hwvibrator@{version}"
            for language in ("c++-headers", "c++-sources", "java"):
                with self.subTest(package=package, language=language):
                    self.assert_generated(language, package)
            # Soong's inheritance rule does not pass -R. Default broad roots
            # must be suppressed by the explicit narrower root, too.
            result, _ = self.generate(
                "inheritance-hierarchy", package + "::IHWVibrator", defaults=True
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            hierarchy = json.loads(result.stdout)
            expected = [PACKAGE + "::IVibrator"]
            if version == "1.1":
                expected.insert(0, "vendor.huawei.hardware.hwvibrator@1.0::IHWVibrator")
            self.assertEqual(hierarchy["interface"], package + "::IHWVibrator")
            self.assertEqual(hierarchy["inheritedInterfaces"], expected)

    def test_corrupted_frozen_hash_is_rejected(self):
        current = self.compat / "current.txt"
        current.write_text(current.read_text().replace(HASHES["IVibrator"], "0" * 64))
        result, _ = self.generate("c++-sources", extra=("-F",))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("does not match hash on record", result.stderr)

    def test_missing_base_is_rejected(self):
        (self.compat / "1.0/IVibrator.hal").unlink()
        result, _ = self.generate("java", "vendor.huawei.hardware.hwvibrator@1.1")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("IVibrator", result.stderr)

    def test_overlapping_explicit_root_is_rejected(self):
        result, _ = self.generate("hash", extra=(
            f"-randroid.hardware:{self.top / 'hardware/interfaces'}",
        ))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("conflicting package roots", result.stderr)


if __name__ == "__main__":
    unittest.main()
