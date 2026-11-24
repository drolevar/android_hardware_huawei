# SPDX-FileCopyrightText: 2026 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
"""Host tests for Kirin 970 EMUI 12 .225 releasetools assertions and image updates."""

from __future__ import annotations

import importlib.util
import io
import os
import sys
import types
import unittest
import zipfile
from pathlib import Path
from types import SimpleNamespace

HARDWARE = Path(__file__).resolve().parents[1]
RELEASETOOLS_PY = HARDWARE / "releasetools/releasetools.py"


def _find_android_releasetools() -> Path | None:
    candidates = []
    env_top = os.environ.get("ANDROID_BUILD_TOP")
    if env_top:
        candidates.append(Path(env_top).resolve())
    candidates.extend([HARDWARE.parents[2] / "src/lineage-24.0", HARDWARE.parents[1]])
    for top in candidates:
        rt = top / "build/make/tools/releasetools"
        if (rt / "edify_generator.py").is_file():
            return rt
    return None


def _load_releasetools():
    rt_dir = _find_android_releasetools()
    if rt_dir is not None and str(rt_dir) not in sys.path:
        sys.path.insert(0, str(rt_dir))

    # Provide a minimal fallback `common` module if running standalone outside AOSP.
    injected_common = False
    if "common" not in sys.modules:
        try:
            import common as _real_common  # noqa: F401
        except ImportError:
            stub = types.ModuleType("common")
            stub.ZipWriteStr = lambda zf, name, data: zf.writestr(name, data)
            sys.modules["common"] = stub
            injected_common = True

    spec = importlib.util.spec_from_file_location("huawei_releasetools", RELEASETOOLS_PY)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    if injected_common:
        sys.modules.pop("common", None)
    return mod


class _ScriptRecorder:
    def __init__(self) -> None:
        self.lines: list[str] = []

    def Comment(self, text: str) -> None:
        self.lines.append(f"# {text}")

    def Print(self, text: str) -> None:
        self.lines.append(f'ui_print("{text}");')

    def AppendExtra(self, text: str) -> None:
        self.lines.append(text)


def _make_zip(entries: dict[str, bytes]) -> zipfile.ZipFile:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for name, data in entries.items():
            zf.writestr(name, data)
    buf.seek(0)
    return zipfile.ZipFile(buf, "r")


def _make_output_zip() -> tuple[zipfile.ZipFile, io.BytesIO]:
    buf = io.BytesIO()
    return zipfile.ZipFile(buf, "w"), buf


def _assert_valid_edify_syntax(tc: unittest.TestCase, statements: list[str]) -> None:
    for stmt in statements:
        if stmt.startswith("#"):
            continue
        in_str = False
        escaped = False
        depth = 0
        for ch in stmt:
            if escaped:
                escaped = False
                continue
            if ch == "\\":
                escaped = True
                continue
            if ch == '"':
                in_str = not in_str
                continue
            if in_str:
                tc.assertNotEqual(ch, "\n", f"Raw newline inside Edify string literal: {stmt!r}")
                continue
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                tc.assertGreaterEqual(depth, 0, f"Unbalanced ')' in: {stmt!r}")
        tc.assertFalse(in_str, f"Unterminated string literal in: {stmt!r}")
        tc.assertEqual(depth, 0, f"Unbalanced parentheses in: {stmt!r}")


class ReleasetoolsTests(unittest.TestCase):
    def test_blanc_full_ota_assertions_and_install_end(self) -> None:
        mod = _load_releasetools()
        in_zip = _make_zip(
            {
                "SYSTEM/build.prop": b"ro.product.system.device=blanc\n",
                "RADIO/boot_ramdisk.img": b"SYNTH_RAMDISK",
            }
        )
        out_zip, out_buf = _make_output_zip()
        script = _ScriptRecorder()
        info = SimpleNamespace(input_zip=in_zip, output_zip=out_zip, script=script)

        mod.FullOTA_Assertions(info)
        mod.FullOTA_InstallEnd(info)
        out_zip.close()

        joined = "\n".join(script.lines)
        _assert_valid_edify_syntax(self, script.lines)
        for needle in (
            'is_substring("fastboot_version=Chipset-boston102.0.0.", read_file("/proc/cmdline"))',
            'is_substring("devmodel=BLA-L29", read_file("/proc/cmdline"))',
            'is_substring("devmodel=BLA-L09", read_file("/proc/cmdline"))',
            'is_substring("PARTNAME=super\\n", read_file("/sys/block/sdd/sdd67/uevent"))',
            'read_file("/sys/block/sdd/sdd67/size") == "11272192\\n"',
            'is_substring("PARTNAME=kernel\\n", read_file("/sys/block/sdd/sdd38/uevent"))',
            'read_file("/sys/block/sdd/sdd38/size") == "49152\\n"',
            'is_substring("PARTNAME=ramdisk\\n", read_file("/sys/block/sdd/sdd53/uevent"))',
            'read_file("/sys/block/sdd/sdd53/size") == "4096\\n"',
            'package_extract_file("boot_ramdisk.img", "/dev/block/by-name/ramdisk");',
        ):
            self.assertIn(needle, joined)

        for forbidden in ("recovery_vendor", "recovery_ramdisk", "patch_hw", "rrecord"):
            self.assertNotIn(forbidden, joined)

        out_buf.seek(0)
        with zipfile.ZipFile(out_buf, "r") as check_out:
            self.assertEqual(check_out.namelist(), ["boot_ramdisk.img"])
            self.assertEqual(check_out.read("boot_ramdisk.img"), b"SYNTH_RAMDISK")

    def test_blanc_incremental_ota_via_metadata_device(self) -> None:
        mod = _load_releasetools()
        tgt_zip = _make_zip({"RADIO/boot_ramdisk.img": b"INC_RAMDISK"})
        out_zip, out_buf = _make_output_zip()
        script = _ScriptRecorder()
        metadata = SimpleNamespace(postcondition=SimpleNamespace(device=["blanc"]))
        info = SimpleNamespace(target_zip=tgt_zip, output_zip=out_zip, script=script, metadata=metadata)

        mod.IncrementalOTA_Assertions(info)
        mod.IncrementalOTA_InstallEnd(info)
        out_zip.close()

        joined = "\n".join(script.lines)
        _assert_valid_edify_syntax(self, script.lines)
        self.assertIn("Chipset-boston102.0.0.", joined)
        self.assertIn('package_extract_file("boot_ramdisk.img", "/dev/block/by-name/ramdisk");', joined)

    def test_blanc_rejects_recovery_vendor_or_missing_boot_ramdisk(self) -> None:
        mod = _load_releasetools()
        metadata = SimpleNamespace(postcondition=SimpleNamespace(device=["blanc"]))

        with self.assertRaisesRegex(RuntimeError, "recovery_vendor"):
            bad_zip = _make_zip(
                {
                    "RADIO/boot_ramdisk.img": b"R",
                    "RADIO/recovery_vendor.img": b"RV",
                }
            )
            out_zip, _ = _make_output_zip()
            mod.FullOTA_InstallEnd(
                SimpleNamespace(input_zip=bad_zip, output_zip=out_zip, script=_ScriptRecorder(), metadata=metadata)
            )
            out_zip.close()

        with self.assertRaisesRegex(RuntimeError, "boot_ramdisk.img"):
            missing_zip = _make_zip({})
            out_zip, _ = _make_output_zip()
            mod.FullOTA_InstallEnd(
                SimpleNamespace(input_zip=missing_zip, output_zip=out_zip, script=_ScriptRecorder(), metadata=metadata)
            )
            out_zip.close()

    def test_other_huawei_device_preserves_legacy_recovery_vendor_path(self) -> None:
        mod = _load_releasetools()
        in_zip = _make_zip(
            {
                "SYSTEM/build.prop": b"ro.product.system.device=charlotte\n",
                "RADIO/boot_ramdisk.img": b"R",
                "RADIO/recovery_vendor.img": b"RV",
            }
        )
        out_zip, out_buf = _make_output_zip()
        script = _ScriptRecorder()
        info = SimpleNamespace(input_zip=in_zip, output_zip=out_zip, script=script)

        mod.FullOTA_Assertions(info)
        mod.FullOTA_InstallEnd(info)
        out_zip.close()

        joined = "\n".join(script.lines)
        self.assertNotIn("Chipset-boston102.0.0.", joined)
        self.assertIn('package_extract_file("boot_ramdisk.img", "/dev/block/by-name/ramdisk");', joined)
        self.assertIn('package_extract_file("recovery_vendor.img", "/dev/block/by-name/recovery_vendor");', joined)

    def test_device_detection_from_info_dict_build_prop(self) -> None:
        mod = _load_releasetools()
        info = SimpleNamespace(
            input_zip=_make_zip({}),
            info_dict={"build.prop": SimpleNamespace(GetProp=lambda k: "blanc" if k == "ro.product.device" else None)},
        )
        self.assertEqual(mod._GetTargetDevice(info), "blanc")

    def test_blanc_fallback_ota_cache_size(self) -> None:
        mod = _load_releasetools()
        sentinel = object()
        had_options = hasattr(mod.common, "OPTIONS")
        orig_options = getattr(mod.common, "OPTIONS", sentinel)
        try:
            # Case 1: import-time / OPTIONS.target_info_dict detection for blanc when cache_size is None
            mod.common.OPTIONS = SimpleNamespace(
                cache_size=None,
                target_info_dict={"build.prop": SimpleNamespace(GetProp=lambda k: "blanc" if k == "ro.product.device" else None)},
                info_dict=None,
            )
            mod._EnsureBlancCacheSize()
            self.assertEqual(mod.common.OPTIONS.cache_size, 134217728)

            # Case 2: existing non-None cache_size is preserved unchanged
            mod.common.OPTIONS.cache_size = 67108864
            mod._EnsureBlancCacheSize()
            self.assertEqual(mod.common.OPTIONS.cache_size, 67108864)

            # Case 3: FullOTA_Assertions sets cache_size for blanc when cache_size is None
            mod.common.OPTIONS = SimpleNamespace(cache_size=None, target_info_dict=None, info_dict=None)
            in_zip = _make_zip({"SYSTEM/build.prop": b"ro.product.system.device=blanc\n"})
            out_zip, _ = _make_output_zip()
            mod.FullOTA_Assertions(SimpleNamespace(input_zip=in_zip, output_zip=out_zip, script=_ScriptRecorder()))
            out_zip.close()
            self.assertEqual(mod.common.OPTIONS.cache_size, 134217728)

            # Case 4: non-blanc device leaves cache_size=None untouched
            mod.common.OPTIONS = SimpleNamespace(
                cache_size=None,
                target_info_dict={"build.prop": SimpleNamespace(GetProp=lambda k: "charlotte" if k == "ro.product.device" else None)},
                info_dict=None,
            )
            mod._EnsureBlancCacheSize()
            self.assertIsNone(mod.common.OPTIONS.cache_size)
            in_other = _make_zip({"SYSTEM/build.prop": b"ro.product.system.device=charlotte\n"})
            out_zip2, _ = _make_output_zip()
            mod.FullOTA_Assertions(SimpleNamespace(input_zip=in_other, output_zip=out_zip2, script=_ScriptRecorder()))
            out_zip2.close()
            self.assertIsNone(mod.common.OPTIONS.cache_size)
        finally:
            if had_options and orig_options is not sentinel:
                mod.common.OPTIONS = orig_options
            elif hasattr(mod.common, "OPTIONS"):
                delattr(mod.common, "OPTIONS")


if __name__ == "__main__":
    unittest.main()
