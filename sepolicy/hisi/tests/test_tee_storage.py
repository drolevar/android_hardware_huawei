# SPDX-FileCopyrightText: 2026 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
"""Keep TEE labels separate across the secure partition, core and vendor data."""
import pathlib
import re
import unittest

POLICY = pathlib.Path(__file__).resolve().parents[1]


def attributes(name):
    text = (POLICY / "vendor/file.te").read_text()
    declarations = re.findall(r"^type\s+(\w+)\s*,([^;]+);", text, re.MULTILINE)
    return {item.strip() for key, values in declarations if key == name
            for item in values.split(",")}


def label(path):
    matches = []
    for raw in (POLICY / "vendor/file_contexts").read_text().splitlines():
        line = raw.partition("#")[0].strip()
        if not line:
            continue
        pattern, context = line.split()
        if re.fullmatch(pattern, path):
            matches.append(context.split(":")[2])
    if len(matches) != 1:
        raise AssertionError(f"Expected one explicit label for {path}: {matches}")
    return matches[0]


class TeeStorageTests(unittest.TestCase):
    def test_legacy_core_paths_have_core_data_ownership(self):
        for base in ("/data/sec_storage_data", "/data/sec_storage_data_users"):
            for path in (base, base + "/private/object"):
                with self.subTest(path=path):
                    attrs = attributes(label(path))
                    self.assertIn("core_data_file_type", attrs)
                    self.assertIn("data_file_type", attrs)
                    self.assertNotIn("vendor_file_type", attrs)

    def test_vendor_paths_keep_vendor_data_ownership(self):
        for base in ("/data/vendor/sec_storage_data", "/data/vendor/sec_storage_data_users"):
            for path in (base, base + "/private/object"):
                with self.subTest(path=path):
                    attrs = attributes(label(path))
                    self.assertIn("file_type", attrs)
                    self.assertIn("data_file_type", attrs)
                    # Writable /data/vendor is not the read-only /vendor partition.
                    self.assertNotIn("vendor_file_type", attrs)
                    self.assertNotIn("core_data_file_type", attrs)

    def test_secure_partition_does_not_share_core_or_vendor_data_type(self):
        secure = label("/sec_storage/private/object")
        self.assertEqual(secure, label("/sec_storage"))
        self.assertNotEqual(secure, label("/data/sec_storage_data/private/object"))
        self.assertNotEqual(secure, label("/data/vendor/sec_storage_data/private/object"))
        self.assertNotIn("core_data_file_type", attributes(secure))


if __name__ == "__main__":
    unittest.main()
