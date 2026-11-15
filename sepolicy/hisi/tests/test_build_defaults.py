# SPDX-FileCopyrightText: 2026 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
"""Evaluate the real policy include: it must not weaken caller build defaults."""
import pathlib
import subprocess
import unittest

POLICY = pathlib.Path(__file__).resolve().parents[1]


class PolicyBuildDefaultsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        fragment = ("BOARD_KERNEL_CMDLINE := console=ttyAMA0\n"
                    "SELINUX_IGNORE_NEVERALLOWS := false\n"
                    f"include {POLICY / 'SEPolicy.mk'}\n"
                    "$(info observed-cmdline=$(BOARD_KERNEL_CMDLINE))\n"
                    "$(info observed-ignore=$(SELINUX_IGNORE_NEVERALLOWS))\n"
                    "all:\n\t@:\n")
        result = subprocess.run(["make", "-rR", "-s", "-f", "-"], input=fragment,
                                text=True, capture_output=True, check=True, timeout=10)
        cls.values = dict(line.split("=", 1) for line in result.stdout.splitlines()
                          if line.startswith("observed-"))

    def test_policy_include_does_not_force_permissive_boot(self):
        self.assertEqual(self.values["observed-cmdline"], "console=ttyAMA0")

    def test_policy_include_preserves_neverallow_validation(self):
        self.assertEqual(self.values["observed-ignore"], "false")


if __name__ == "__main__":
    unittest.main()
