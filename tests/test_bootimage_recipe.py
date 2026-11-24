# SPDX-FileCopyrightText: 2026 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
"""Run the actual Huawei Make recipe and Android image tools on host fixtures.

ANDROID_BUILD_TOP must identify the selected Android tree. Synthetic payloads
are packaging inputs only; these tests do not execute a kernel or prove trust.
"""
import gzip
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

HARDWARE = Path(__file__).resolve().parents[1]


def make_fixture(top, work, *, descriptor=None, ramdisk_descriptor=None,
                 build_recovery_vendor=None, signed=False, partition_size=1048576, kernel=None):
    work.mkdir(parents=True, exist_ok=True)
    payload = work / "Image.gz"
    payload.write_bytes(kernel if kernel is not None else gzip.compress(b"synthetic kernel", mtime=0))
    ramdisk = work / "ramdisk.gz"
    ramdisk.write_bytes(gzip.compress(b"synthetic ramdisk", mtime=0))
    definitions = (top / "build/make/core/definitions.mk").read_text()
    macros = []
    for name in ("get-file-size", "assert-max-image-size"):
        match = re.search(r"^define " + name + r"\n.*?^endef$", definitions, re.M | re.S)
        if match is None:
            raise ValueError(f"Missing actual Android Make macro: {name}")
        macros.append(match.group())
    avb = top / "external/avb/avbtool.py"
    key = top / "external/avb/test/data/testkey_rsa2048.pem"
    signing = f"--algorithm SHA256_RSA2048 --key {key}" if signed else "--algorithm NONE"
    setting = "" if descriptor is None else f"BOARD_HUAWEI_KERNEL_AVB_PARTITION_NAME := {descriptor}\n"
    if ramdisk_descriptor is not None:
        setting += f"BOARD_HUAWEI_RAMDISK_AVB_PARTITION_NAME := {ramdisk_descriptor}\n"
    if build_recovery_vendor is not None:
        setting += f"BOARD_HUAWEI_BUILD_RECOVERY_VENDOR := {build_recovery_vendor}\n"
    text = "\n".join(macros) + f"""
PRODUCT_OUT := {work}
TARGET_KERNEL_SOURCE := {top / 'kernel/huawei/kirin970'}
INSTALLED_KERNEL_TARGET := {payload}
INSTALLED_BOOTIMAGE_TARGET := {work / 'boot.img'}
INSTALLED_RECOVERYIMAGE_TARGET := {work / 'recovery.img'}
BUILT_RAMDISK_TARGET := {ramdisk}
recovery_ramdisk := {ramdisk}
BOARD_KERNEL_BASE := 0x00078000
BOARD_KERNEL_PAGESIZE := 2048
BOARD_KERNEL_CMDLINE := loglevel=7
BOARD_MKBOOTIMG_ARGS := --kernel_offset 0x8000 --ramdisk_offset 0x37588000 --second_offset 0xe88000 --tags_offset 0x37d88000 --header_version 1
BOARD_BOOTIMAGE_PARTITION_SIZE := {partition_size}
BOARD_BOOT_RAMDISKIMAGE_PARTITION_SIZE := {partition_size}
BOARD_RECOVERYIMAGE_PARTITION_SIZE := {partition_size}
BOARD_RECOVERY_VENDORIMAGE_PARTITION_SIZE := {partition_size}
TARGET_BUILD_VARIANT := userdebug
INTERNAL_MKBOOTIMG_VERSION_ARGS := --os_version 17.0.0 --os_patch_level 2026-11-01
INTERNAL_AVB_BOOT_SIGNING_ARGS := {signing}
INTERNAL_AVB_RECOVERY_SIGNING_ARGS := {signing}
AVBTOOL := {avb}
MKBOOTIMG := {top / 'system/tools/mkbootimg/mkbootimg.py'}
{setting}include {HARDWARE / 'mkbootimg.mk'}
print-radio-targets:
	@echo "$(INSTALLED_RADIOIMAGE_TARGET)"
"""
    makefile = work / "Makefile"
    makefile.write_text(text)
    return makefile


class BootImageRecipeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        value = os.environ.get("ANDROID_BUILD_TOP")
        if not value:
            raise unittest.SkipTest("Set ANDROID_BUILD_TOP to run real Android packaging tools")
        cls.top = Path(value).resolve()
        cls.avb = cls.top / "external/avb/avbtool.py"
        if not cls.avb.is_file():
            raise RuntimeError("ANDROID_BUILD_TOP does not contain avbtool.py")

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.work = Path(self.temp.name)

    def build(self, descriptor=None, *, ramdisk_descriptor=None, build_recovery_vendor=None,
              target="boot.img", signed=False, kernel=None):
        makefile = make_fixture(self.top, self.work, descriptor=descriptor,
                                ramdisk_descriptor=ramdisk_descriptor,
                                build_recovery_vendor=build_recovery_vendor,
                                signed=signed, kernel=kernel)
        image = self.work / target
        proc = subprocess.run(["make", "-rR", "-s", "-f", str(makefile), str(image)],
                              text=True, capture_output=True, timeout=30)
        return image, proc

    def test_recovery_vendor_opt_out_omits_radioimage_target(self):
        mk_default = make_fixture(self.top, self.work / "default")
        out_default = subprocess.check_output(
            ["make", "-rR", "-s", "-f", str(mk_default), "print-radio-targets"],
            text=True, timeout=20,
        )
        self.assertIn("boot_ramdisk.img", out_default)
        self.assertIn("recovery_vendor.img", out_default)

        mk_disabled = make_fixture(self.top, self.work / "disabled", build_recovery_vendor="false")
        out_disabled = subprocess.check_output(
            ["make", "-rR", "-s", "-f", str(mk_disabled), "print-radio-targets"],
            text=True, timeout=20,
        )
        self.assertIn("boot_ramdisk.img", out_disabled)
        self.assertNotIn("recovery_vendor.img", out_disabled)

    def info(self, image):
        return subprocess.check_output([str(self.avb), "info_image", "--image", str(image)],
                                       text=True, stderr=subprocess.STDOUT, timeout=20)

    def verify(self, image, *, key=None):
        # avbtool resolves a nonempty descriptor name as <name>.img. Android's
        # build output name is not necessarily the physical partition name.
        name = re.search(r"Partition Name:\s+(\S+)", self.info(image))[1]
        alias = image.with_name(name + image.suffix)
        if alias != image:
            self.assertFalse(alias.exists(), "Do not overwrite another image")
            alias.symlink_to(image.name)
        argv = [str(self.avb), "verify_image", "--image", str(image)]
        if key:
            argv += ["--key", str(key)]
        try:
            return subprocess.run(argv, text=True, capture_output=True, timeout=20)
        finally:
            if alias != image:
                alias.unlink()

    def test_default_keeps_existing_boot_descriptor_and_capacity(self):
        image, proc = self.build()
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertRegex(self.info(image), r"Partition Name:\s+boot\n")
        self.assertEqual(image.stat().st_size, 1048576)
        self.assertEqual(self.verify(image).returncode, 0)

    def test_bla_opt_in_sets_kernel_descriptor_and_preserves_payload(self):
        image, proc = self.build("kernel")
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertRegex(self.info(image), r"Partition Name:\s+kernel\n")
        self.assertEqual(image.stat().st_size, 1048576)
        out = self.work / "unpacked"
        report = subprocess.check_output([
            "python3", str(self.top / "system/tools/mkbootimg/unpack_bootimg.py"),
            "--boot_img", str(image), "--out", str(out)], text=True, timeout=20)
        self.assertEqual((out / "kernel").read_bytes(), (self.work / "Image.gz").read_bytes())
        self.assertIn("kernel load address: 0x00080000", report)
        self.assertIn("boot image header version: 1", report)
        self.assertIn("loglevel=7", report)
        self.assertEqual(self.verify(image).returncode, 0)

    def test_kernel_override_does_not_change_normal_ramdisk_descriptor(self):
        image, proc = self.build("kernel", target="boot_ramdisk.img")
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertRegex(self.info(image), r"Partition Name:\s+boot\n")
        self.assertEqual(self.verify(image).returncode, 0)

    def test_bla_opt_in_sets_ramdisk_descriptor_and_preserves_recovery(self):
        image, proc = self.build("kernel", ramdisk_descriptor="ramdisk", target="boot_ramdisk.img")
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertRegex(self.info(image), r"Partition Name:\s+ramdisk\n")
        self.assertEqual(image.stat().st_size, 1048576)
        self.assertEqual(self.verify(image).returncode, 0)
        rec_img, rec_proc = self.build("kernel", ramdisk_descriptor="ramdisk", target="recovery.img")
        self.assertEqual(rec_proc.returncode, 0, rec_proc.stdout + rec_proc.stderr)
        self.assertRegex(self.info(rec_img), r"Partition Name:\s+recovery\n")
        self.assertEqual(self.verify(rec_img).returncode, 0)

    def test_kernel_override_does_not_change_recovery_descriptor(self):
        image, proc = self.build("kernel", target="recovery.img")
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertRegex(self.info(image), r"Partition Name:\s+recovery\n")
        self.assertEqual(self.verify(image).returncode, 0)

    def test_existing_signing_arguments_are_preserved(self):
        image, proc = self.build("kernel", signed=True)
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        info = self.info(image)
        self.assertRegex(info, r"Partition Name:\s+kernel\n")
        self.assertRegex(info, r"Algorithm:\s+SHA256_RSA2048\n")
        key = self.top / "external/avb/test/data/testkey_rsa2048.pem"
        self.assertEqual(self.verify(image, key=key).returncode, 0)
        with image.open("r+b") as stream:
            stream.seek(2048)
            first = stream.read(1)
            stream.seek(2048)
            stream.write(bytes([first[0] ^ 1]))
        self.assertNotEqual(self.verify(image, key=key).returncode, 0)

    def test_oversized_kernel_is_rejected(self):
        _, proc = self.build("kernel", kernel=b"x" * 1048576)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("too large", proc.stdout + proc.stderr)

    def test_bpf_kver_override_property_contexts_and_sepolicy_mk(self):
        sepolicy_mk = (HARDWARE / "sepolicy/hisi/SEPolicy.mk").read_text()
        self.assertIn("SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS", sepolicy_mk)
        self.assertIn("hardware/huawei/sepolicy/hisi/private", sepolicy_mk)
        prop_ctx = (HARDWARE / "sepolicy/hisi/private/property_contexts").read_text()
        lines = [
            line.strip().split()
            for line in prop_ctx.splitlines()
            if line.strip() and not line.lstrip().startswith("#")
        ]
        self.assertIn(
            ["ro.bpf.kver_override", "u:object_r:exported_default_prop:s0", "exact", "string"],
            lines,
        )


if __name__ == "__main__":
    unittest.main()
