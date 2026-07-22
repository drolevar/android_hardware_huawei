#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Offline audit; reads private references but never copies them into this project."""

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parent
CANDIDATES = {
    "arm": (109176, "a9754c39d798438a2c8ef6324379322324df08b5ebb71bee6ef0e9aabe076332", 40, 1),
    "arm64": (150352, "9119fd660144b062d46b35a63ab17fd2f445c893f77ff1bacf3d9a31e5efa703", 183, 2),
}


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(command):
    return subprocess.run([str(x) for x in command], text=True, capture_output=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", type=Path, required=True)
    parser.add_argument("--vndk30", type=Path, required=True)
    parser.add_argument("--firmware", type=Path, required=True,
                        help="original-filesystems directory; read only")
    parser.add_argument("--generated", type=Path, required=True,
                        help="generated proprietary/vendor directory; read only")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    top = args.platform.resolve()
    checker_path = top / "build/make/tools/check_elf_file.py"
    llvm = top / "prebuilts/clang/host/linux-x86/clang-r596125/bin/llvm-readobj"
    patchelf = top / "prebuilts/extract-tools/linux-x86/bin/patchelf-0_9"
    spec = importlib.util.spec_from_file_location("platform_elf_checker", checker_path)
    checker = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(checker)
    report = {
        "scope": "offline structural checks; not target linking or runtime validation",
        "aosp_vndk30_revision": run(["git", "-C", args.vndk30.resolve(),
                                     "rev-parse", "HEAD"]).stdout.strip(),
        "platform_build_revision": run(["git", "-C", top / "build/make",
                                        "rev-parse", "HEAD"]).stdout.strip(),
        "tested_page_size": 4096,
        "tools": {str(p.relative_to(top)): sha256(p)
                  for p in (checker_path, llvm, patchelf)},
        "abis": {},
    }

    def require(condition, message):
        if not condition:
            raise RuntimeError(message)

    for abi, (size, digest, machine, elf_class) in CANDIDATES.items():
        lib, arch, triplet = (("lib", "arch-arm-armv8-a", "arm") if abi == "arm"
                              else ("lib64", "arch-arm64-armv8-a", "aarch64"))
        candidate = ROOT / abi / "libjsoncpp-v30.so"
        snapshot = args.vndk30 / "arm64" / arch / "shared/vndk-sp/libjsoncpp.so"
        system = args.firmware / "system" / lib
        paths = {
            "candidate": candidate,
            "aosp_vndk30": snapshot,
            "original_hiview": args.firmware / "vendor" / lib / "libhiview.so",
            "generated_hiview": args.generated / lib / "libhiview.so",
            "stock_vndk29_jsoncpp": system / "vndk-sp-29/libjsoncpp.so",
            "stock_libcpp": system / "libc++.so",
            "platform_libcpp": top / "prebuilts/clang/host/linux-x86/clang-r596125"
                                 / "android_libc++/platform" / triplet / "lib/libc++.so",
            **{"stock_" + name: system / "bootstrap" / (name + ".so")
               for name in ("libc", "libm", "libdl")},
        }
        elfs = {k: checker.ELFParser.open(str(p), str(llvm)) for k, p in paths.items()}
        row = {"files": {}, "checker_runs": []}
        report["abis"][abi] = row
        for name, path in paths.items():
            elf = elfs[name]
            row["files"][name] = {
                "sha256": sha256(path), "bytes": path.stat().st_size,
                "machine": elf.header.e_machine, "class": elf.header.ei_class,
                "soname": elf.dt_soname, "needed": elf.dt_needed,
                "load_alignments": elf.alignments,
            }
        c = elfs["candidate"]
        require((candidate.stat().st_size, sha256(candidate), c.header.e_machine,
                 c.header.ei_class) == (size, digest, machine, elf_class), abi + " identity")
        require(c.dt_soname == "libjsoncpp-v30.so", abi + " SONAME")
        require(c.dt_needed == ["libc++.so", "libc.so", "libm.so", "libdl.so"], abi + " NEEDED")
        require(c.imported == elfs["aosp_vndk30"].imported, abi + " snapshot imports")
        require(c.exported == elfs["aosp_vndk30"].exported, abi + " snapshot exports")
        require(all(n % 4096 == 0 for n in c.alignments), abi + " 4K alignment")
        row["candidate_export_count"] = len(c.exported)
        row["candidate_import_versions"] = sorted({v for vs in c.imported.values() for v in vs})
        with tempfile.TemporaryDirectory(dir=ROOT) as temporary:
            renamed = Path(temporary) / candidate.name
            shutil.copyfile(snapshot, renamed)
            result = run([patchelf, "--set-soname", candidate.name, renamed])
            require(result.returncode == 0, result.stderr)
            require(renamed.read_bytes() == candidate.read_bytes(), abi + " AOSP reproduction")
        row["aosp_soname_only_reproduction"] = True
        original, generated = elfs["original_hiview"], elfs["generated_hiview"]
        require(original.imported == generated.imported, abi + " changed consumer imports")
        require(generated.dt_needed == ["libjsoncpp-v30.so" if n == "libjsoncpp.so" else n
                                       for n in original.dt_needed], abi + " unexpected fixup")
        symbols = {n: sorted(vs) for n, vs in original.imported.items()
                   if n.startswith(("_ZN4Json", "_ZNK4Json", "_ZTVN4Json", "_ZTIN4Json", "_ZTSN4Json"))}
        require(len(symbols) == 21, abi + " unexpected Json import count")
        row["json_imports"] = symbols
        row["missing_json_symbols"] = [n + "@" + v for n, vs in symbols.items() for v in vs
                                       if not checker.Checker._find_symbol(c, n, v)]
        require(not row["missing_json_symbols"], abi + " missing Json exports")
        for label in ("stock_libcpp", "platform_libcpp"):
            deps = [paths[label], *[paths["stock_" + n] for n in ("libc", "libm", "libdl")]]
            command = [sys.executable, "-B", checker_path, "--llvm-readobj", llvm,
                       "--soname", candidate.name, "--max-page-size", "4096"]
            for dep in deps:
                command += ["--shared-lib", dep]
            command += [candidate]
            result = run(command)
            row["checker_runs"].append({"libcpp_reference": label,
                "bionic_reference": "stock29 bootstrap", "command": [str(x) for x in command],
                "exit_status": result.returncode, "stderr": result.stderr})
            require(result.returncode == 0, result.stderr)
        # Exercise the real checker failure paths; no check suppression flags.
        for label, extra, error in (
            ("wrong_soname", ["--soname", "wrong.so"], "DT_SONAME"),
            ("undeclared_dependencies", [], "is not specified in shared_libs"),
        ):
            command = [sys.executable, "-B", checker_path, "--llvm-readobj", llvm, *extra, candidate]
            result = run(command)
            require(result.returncode == 2 and error in result.stderr, abi + " negative " + label)
            row["checker_runs"].append({"negative_case": label, "expected_exit_status": 2,
                                        "actual_exit_status": result.returncode})
        print(f"{abi}: 21 Json imports resolved; exact AOSP reproduction; "
              "two ELF checks and two negative checks passed")

    args.output.write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
