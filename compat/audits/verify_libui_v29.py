#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Replay this recorded offline audit, including its expected reference failures."""

import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


def run(*command):
    return subprocess.run([str(x) for x in command], capture_output=True, text=True, check=True)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python3 -B verify_libui_v29.py WORKSPACE_ROOT")
    workspace = Path(sys.argv[1]).resolve()
    here = Path(__file__).resolve().parent
    recorded = json.loads((here / "libui-v29-20261114.json").read_text())
    top = workspace / "src/lineage-24.0"
    snapshot = workspace / "src/twrp-14.1/prebuilts/vndk/v29"
    firmware = workspace / "firmware/current/original-filesystems"
    generated = top / "vendor/huawei/kirin970-common/proprietary/vendor"
    for path, pin in recorded["source_revisions"].items():
        require(run("git", "-C", top / path, "rev-parse", "HEAD").stdout.strip() == pin,
                "Changed source revision: " + path)
    require(run("git", "-C", snapshot, "rev-parse", "HEAD").stdout.strip()
            == recorded["aosp_vndk29"]["revision"], "Changed VNDK29 snapshot")
    checker_path = top / "build/make/tools/check_elf_file.py"
    spec = importlib.util.spec_from_file_location("recorded_elf_checker", checker_path)
    checker = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(checker)
    clang = top / "prebuilts/clang/host/linux-x86/clang-r596125"
    llvm = clang / "bin/llvm-readobj"
    patchelf = top / "prebuilts/extract-tools/linux-x86/bin/patchelf-0_9"

    for abi, lib, arch, triplet in (("arm", "lib", "arch-arm-armv8-a", "arm"),
                                  ("arm64", "lib64", "arch-arm64-armv8-a", "aarch64")):
        row = recorded["abis"][abi]
        paths = {
            "aosp_snapshot": snapshot / "arm64" / arch / "shared/vndk-core/libui.so",
            "original_hwc": firmware / "vendor" / lib / "hw/hwcomposer.kirin970.so",
            "generated_hwc": generated / lib / "hw/hwcomposer.kirin970.so",
            "stock_ui": firmware / "system" / lib / "vndk-29/libui.so",
        }
        for name, path in paths.items():
            require(digest(path) == row["files"][name]["sha256"], abi + " changed " + name)
        parsed = {n: checker.ELFParser.open(str(p), str(llvm)) for n, p in paths.items()}
        original, consumer = parsed["original_hwc"], parsed["generated_hwc"]
        require(original.imported == consumer.imported, abi + " changed consumer imports")
        require(consumer.dt_needed == ["libui-v29.so" if n == "libui.so" else n
                                       for n in original.dt_needed], abi + " changed fixup")
        symbols = {n: sorted(vs) for n, vs in consumer.imported.items()
                   if n in parsed["stock_ui"].exported}
        require(symbols == row["stock_ui_matched_hwc_imports"], abi + " changed UI imports")
        with tempfile.TemporaryDirectory(dir=here) as temporary:
            candidate = Path(temporary) / "libui-v29.so"
            shutil.copyfile(paths["aosp_snapshot"], candidate)
            run(patchelf, "--set-soname", candidate.name, candidate)
            require(digest(candidate) == row["files"]["candidate"]["sha256"],
                    abi + " maintainer reproduction differs")
            before = checker.ELFParser.open(str(candidate), str(llvm))
            require(all(checker.Checker._find_symbol(before, n, v)
                        for n, vs in symbols.items() for v in vs), abi + " missing UI exports")
            for name in row["experimental_removed_needed"]:
                run(patchelf, "--remove-needed", name, candidate)
            after = checker.ELFParser.open(str(candidate), str(llvm))
            require(before.imported == after.imported and before.exported == after.exported,
                    abi + " changed symbol contract during experiment")
            command = [sys.executable, "-B", checker_path, "--llvm-readobj", llvm,
                       "--soname", candidate.name, "--max-page-size", "4096"]
            for reference in row["dependency_references"]:
                name = reference["name"]
                if name in ("libc.so", "libm.so", "libdl.so"):
                    path = firmware / "system" / lib / "bootstrap" / name
                elif name in ("libsync.so", "liblog.so"):
                    path = firmware / "system" / lib / name
                elif name == "libc++.so":
                    path = clang / "android_libc++/platform" / triplet / "lib/libc++.so"
                else:
                    path = next(p for p in (top / "prebuilts/vndk/v34/arm64" / arch / "shared"
                                           / kind / name for kind in ("vndk-sp", "vndk-core"))
                                if p.is_file())
                require(digest(path) == reference["sha256"], abi + " changed reference " + name)
                command += ["--shared-lib", path]
            result = subprocess.run([str(x) for x in command + [candidate]],
                                    text=True, capture_output=True)
            errors = [line.split("error:", 1)[1].strip() for line in result.stderr.splitlines()
                      if ": error:" in line]
            require(result.returncode == row["experimental_strict_check"]["exit_status"]
                    and errors == row["experimental_strict_check"]["errors"],
                    abi + " reference outcome changed: " + result.stderr)
        print(f"{abi}: public bytes reproduced, {len(symbols)}/17 UI imports covered; "
              "four expected LIBSYNC reference failures reproduced")
    print("Audit replay complete. No target graph, target ABI, or runtime success is claimed.")


if __name__ == "__main__":
    main()
