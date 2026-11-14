#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Verify imported bytes; optionally generate APIs and check four C++ translation units."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parent


def run(command):
    subprocess.run([str(x) for x in command], check=True)


def main():
    provenance = json.loads((ROOT / "legacy-defaults-provenance.json").read_text())
    for name, record in provenance["unmodified_files"].items():
        actual = hashlib.sha256((ROOT / name).read_bytes()).hexdigest()
        if actual != record["sha256"]:
            raise RuntimeError("Imported source changed: " + name)
    current = {line for line in (ROOT / "memtrack/current.txt").read_text().splitlines()
               if line and not line.startswith("#")}
    expected = {f"{provenance['unmodified_files']['memtrack/1.0/' + name + '.hal']['sha256']} "
                f"android.hardware.memtrack@1.0::{name}" for name in ("IMemtrack", "types")}
    if current != expected:
        raise RuntimeError("Memtrack frozen API hashes changed")
    print("Verified ten unchanged upstream source/rc/IDL files and memtrack API hashes", flush=True)
    if not os.environ.get("HIDL_GEN"):
        print("API generation and C++ checks not run: set HIDL_GEN and ANDROID_BUILD_TOP")
        return
    top = Path(os.environ["ANDROID_BUILD_TOP"]).resolve()
    generator = Path(os.environ["HIDL_GEN"]).resolve()
    clang = top / "prebuilts/clang/host/linux-x86/clang-r596125/bin/clang++"
    roots = [f"-randroid.hardware.{f}:{ROOT / f}" for f in ("memtrack", "vibrator")]
    roots += [f"-randroid.hidl:{top / 'system/libhidl/transport'}"]
    with tempfile.TemporaryDirectory(dir=ROOT) as temporary:
        generated = Path(temporary) / "generated"
        generated.mkdir()
        for package in ("android.hidl.base@1.0", "android.hidl.manager@1.0",
                        "android.hidl.manager@1.1", "android.hidl.manager@1.2",
                        "android.hardware.memtrack@1.0", "android.hardware.vibrator@1.0"):
            run([generator, "-R", "-F", "-p", top, "-L", "c++-headers",
                 "-o", generated, *roots, package])
        includes = [
            "hardware/libhardware/include_all", "system/libhidl/base/include",
            "system/libhidl/transport/include", "system/libhwbinder/include",
            "system/core/libutils/include", "system/core/libutils/binder/include",
            "system/core/libsystem/include", "system/core/libcutils/include",
            "system/core/libprocessgroup/include", "system/logging/liblog/include",
            "system/libbase/include", "system/libfmq/base", "system/libfmq/include",
        ]
        flags = ["-std=gnu++23", "-stdlib=libc++", "-fsyntax-only", "-Wall", "-Werror",
                 "-I" + str(generated), *["-I" + str(top / p) for p in includes]]
        for family, stem in (("memtrack", "Memtrack"), ("vibrator", "Vibrator")):
            for name in (stem + ".cpp", "service.cpp"):
                source = ROOT / family / "1.0/default" / name
                run([clang, *flags, source])
                print("Host C++ syntax check passed: " + family + "/" + name, flush=True)
    print("Current-source API/header checks passed; no target link or runtime check was performed")


if __name__ == "__main__":
    main()
